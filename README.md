# i西理签到 · XAUT Check-in

> 西安理工大学（XAUT）「i西理 / 考勤统计」系统（`kqtj.xaut.edu.cn`）的安卓签到客户端。
> **一次扫码，多个账号同时签到。**

![platform](https://img.shields.io/badge/platform-Android%208.0%2B-3ddc84)
![kotlin](https://img.shields.io/badge/Kotlin-1.9.24-7f52ff)
![compose](https://img.shields.io/badge/Compose-BOM%202024.06-4285f4)

---

## ⚠️ 免责声明

本项目**仅供个人学习与技术研究**，仅用于方便学生本人（及其明确授权的同学）为自己的账号完成签到。

- 与**西安理工大学无关**，非官方产品，未经学校授权或认可。
- 请遵守学校考试纪律、课堂考勤规定及学生手册相关条款。**是否使用、如何使用，由使用者自行判断并承担全部后果。**
- 作者不对任何因使用本工具导致的考勤异常、纪律处分或其他损失负责。
- 接口来自对本人账号的公开前端行为观察，仓库内**不含**任何真实学号、密码、票据。

如果你在找学校官方客户端，请走「i西理」App 或教务系统官网。

---

## 它解决什么问题

课堂签到通常是：老师投出二维码 → 大家各自打开手机"i西理" → 一人一次登录 → 点"考勤打卡"。

帮同宿舍/同班几个同学一起签的时候很难受：**一台手机只能登一个账号，切号要重新认证，慢一步就过期了**。

这个 App 做的是：**扫一次码，把所有账号并发提交完**。

---

## 功能

| 功能 | 说明 |
| --- | --- |
| **一次扫码 · 多账号签到** | 扫一次，账号池里每个账号各自登录、各自提交，3 路并发 |
| **7 天免登录** | 登录后把 CAS 的 `CASTGC` 票据加密存进本地库，之后两周内不用再输密码 |
| **摄像头内直接扫码** | ML Kit 离线识别（模型打进 APK，不联网、不上传图片） |
| **变焦 + 长焦 / 超广角切换** | 双指缩放 + 变焦滑块 + 镜头切换，坐后排拍投影也能扫上 |
| **从相册选图识别** | 同学发来的二维码截图，直接从相册读 |
| **表单字段可视化** | 「读取字段 / 体检 / 演练一次」——签到前先把这张表单摊开看清楚 |
| **图形验证码** | 同一会话连续 3 次密码错误才会触发，App 内弹窗涂图 |
| **签到历史** | 每次提交的结果、时间、失败原因落库可查 |

---

## 多账号签到是怎么做到不串号的

多账号并发 ≠ 循环调接口，稍不注意就会拿 A 的会话提交 B 的字段。这里的做法是：

1. **每个账号独立 `CookieJar` + `HttpSession`** —— 没有共享的 cookie 容器，`SESSION` / `CASTGC` 各存各的。
2. **每个账号各自拉自己的表单** —— 提交前对每个账号单独 `GET /runtime/{appId}/page/{pageId}/form`，服务端会按**该账号的身份**预填姓名、学号、院系、专业、班级。
3. **只覆盖用户改过的字段** —— 提交体 = 该账号自己的表单快照 + 用户显式改动，绝不拿 A 的快照去提交 B。
4. **并发上限 3**（`Semaphore`），互不影响，逐条回报成功/失败原因。
5. **凭据本地加密**：密码走 Android Keystore（AES-256-GCM）；`CASTGC` 单独加密一列存储，超过 13 天视为过期，自动回落到密码登录。
6. 换密码 / 断网 / 票据失效都有明确错误分类，不会把"登录失败"错报成"签到成功"。

---

## 原理：签到 = 提交一张表单

服务端是金智 ADP 低代码平台（前端 Vue3）。签到不是独立接口，而是**往一张考勤表单提交一次记录**。相关请求都在 `/adp/api` 下（2026-10 抓包 → 浏览器对拍确认）：

| 用途 | 请求 |
| --- | --- |
| 登录入口 | `GET kqtj.../adp/api/login` → 302 到 CAS |
| 统一身份认证 | `ids.xaut.edu.cn/authserver/login`，密码 AES-128-CBC 加密（key/iv 取页面隐藏域 `pwdEncryptSalt`） |
| 免密复用 | 带 `CASTGC` cookie 直连 → `caslogin?ticket=ST-...` → 换 `SESSION`（全程不碰密码） |
| 当前身份 | `GET /adp/api/currentUser` → `{uid, name, roles}` |
| 表单结构 | `GET /adp/api/runtime/{appId}/page/{pageId}/form`（字段在 `schemas[].children[].children[]`，含服务端预填值） |
| 考勤回填 | `POST .../component/selectField_2ai1975l/correlationData` → 课程 / 老师 / 日期 / 轮次 / 截止时间 |
| **提交签到** | `POST /adp/api/runtime/{appId}/page/{pageId}/form`，body 是**扁平字段字典**，下拉值须为 `{label, value}` 对象 |

关于「网页上还得点一下确认按钮，你凭什么说等价」——用 Playwright 在真实浏览器里对同一张过期考勤记录抓包：

- 点「考勤打卡」只发 **1 个 POST**，就是这个 `/form`；
- 无 `beforeSubmit` 钩子、无自定义签名头、无业务二次确认弹窗（弹出的 `.ant-modal-confirm` 是"已过期"的前置拦截）；
- 我们的提交与之逐字段比对一致，服务端返回**逐字相同**：`{"code":"-1","msg":"当前考勤期限已过期，无法打卡"}`。

服务端校验顺序（排错有用）：`考勤类型不能为空` → `考勤轮次不能为空` → `未生成考勤记录，请稍后重试` → `当前考勤期限已过期，无法打卡`。

> 时间窗口由服务端判定，**改手机本地时间无效**。

---

## 构建

仓库**不含 `gradlew`**（开发机用的是系统 Gradle），需要自备：

- JDK 17
- Android SDK：platform 34 + build-tools 34.0.0
- Gradle 8.9

```bash
# 1) 建 local.properties（不进仓库）
echo "sdk.dir=/path/to/android-sdk" > local.properties

# 2) 单测 + 编译（依赖走阿里云镜像，见 settings.gradle.kts）
gradle testDebugUnitTest assembleDebug --offline

# 3) 产物
# app/build/outputs/apk/debug/app-debug.apk
```

Windows 上可直接双击 `打包.bat`（会读环境变量里的 `JAVA_HOME` / `ANDROID_HOME`）。

**不想自己编译？** 下载预编译安装包：[Releases → iXiliCheckin-v1.2.0.apk](https://github.com/xiebrooooo/ixaut-checkin/releases/latest)（Android 8.0+，debug 签名，安装时需允许「未知来源应用」）。

单测是纯 JVM 的（不需要模拟器）：加密算法 CBC 行为回归、表单解析、字段 label 识别。

---

## 使用

1. **账号页**：填「学号 密码」，一行一个；点「测试密码」确认每个账号都能登进去（会回显真实姓名）。
2. **签到页**：点扫码，对着二维码在摄像头内识别（支持双指缩放 / 变焦条 / 长焦切换），或用「相册选图」。
3. 识别成功后可以先点 **读取字段** 看这张表单长什么样，或 **演练一次**（只读不提交）。
4. 点 **签到全部账号**，逐条看结果。

首次建议先「演练一次」把链路确认通，再真签。

---

## 已知限制

1. **只能在考勤有效期内成功**。过期二维码一律返回"当前考勤期限已过期"，这是服务端校验，无法绕过（也不需要绕过）。
2. 二维码里的 `id` 绑定**当次**考勤上报记录，换一节课就是另一个 id，所以必须扫当堂的码。
3. **变焦 / 长焦在模拟器上无效**：MuMu 等模拟器的虚拟摄像头 `maxZoomRatio` 恒为 1 且只有一颗摄像头，该能力需真机验证。
4. 字段识别用 label 正则匹配（不写死雪花 id），如果学校改了表单结构或字段命名，可能需要相应调整。
5. 非官方实现，接口随时可能变更；教务系统升级后可能失效。
6. 图形验证码由服务端按会话风控触发，程序只负责弹出与重试，字符仍需人眼识别。
7. `keyCur` 取不到时 `inputField_oljsq26s` 不提交（不伪造值），若平台将来强校验该字段可能失败。
8. Release 里的 APK 是 **debug 签名**，仅供自用分发；如需正式签名请自行生成 keystore 并配置 `signingConfigs`。

---

## 项目结构

```
app/src/main/java/edu/xaut/xilicheckin/
├── data/
│   ├── net/          CasAuth（登录 / 免密 / 验证码）、AdpApi（业务接口）、HttpSession（会话与 CookieJar）
│   ├── crypto/       PasswordCipher（Keystore AES-256-GCM）
│   ├── local/        Room（账号、配置、签到记录）
│   └── repo/         账号 / 配置 / 记录仓库
├── domain/           Models、QrParser（二维码解析）、SignEngine（签到编排）、CaptchaBridge
├── ui/
│   ├── scan/         ScannerScreen（CameraX + ML Kit + 变焦 + 相册）
│   ├── sign/         签到页与 ViewModel
│   ├── accounts/     账号池
│   ├── history/      历史记录
│   └── theme/        主题
└── di/               Hilt 模块
app/src/test/java/... 纯 JVM 单测（加密算法、表单解析、字段识别）
```

---

## 更新记录

### v1.2.0
- 扫码引擎 ZXing → **ML Kit**（bundled，不依赖 GMS，多尺度搜索对小/糊/低对比的投影码更稳）
- 扫码页支持 **双指缩放 / 变焦滑块 / 长焦·超广角镜头切换**
- 支持 **从相册选图** 识别
- 移除「调系统相机拍照再识别」路径
- 修复 `不可见-缓存key` 字段永不被提交的问题
- 单测改用 `java.util.Base64`，脱离 Android framework 纯 JVM 可跑

### v1.1.0
- 接入 **7 天免登录**（`CASTGC` 加密落库，13 天过期）
- 提交体与浏览器逐字段对拍验证
- 字段卡新增「这栏是什么？」说明；不生效字段默认折叠

### v1.0.0
- 首个可安装版本：CAS 登录、图形验证码、一次扫码多账号并发签到、演练 / 体检、历史记录

---

## 许可证

[MIT](LICENSE)
