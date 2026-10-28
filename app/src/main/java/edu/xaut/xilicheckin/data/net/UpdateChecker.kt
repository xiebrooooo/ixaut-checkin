package edu.xaut.xilicheckin.data.net

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 从 GitHub Releases 检查新版本并下载安装包。
 *
 * 两个坑，代码里已经绕开：
 * 1. `github.com/…/releases/download/…` 这个域名在很多网络下是被墙的，
 *    所以优先走 **API 资产地址** `api.github.com/repos/…/releases/assets/{id}`
 *    （带 `Accept: application/octet-stream`，会 302 到 objects.githubusercontent.com，
 *    api.github.com 与 objects.githubusercontent.com 通常是通的），失败再退回
 *    `browser_download_url`。
 * 2. OkHttp 默认不跟随 302 到不同域名——这里显式 `followRedirects(true)`。
 */
class UpdateChecker(private val ctx: Context) {

    data class Info(
        val version: String,
        val notes: String,
        val htmlUrl: String,
        val apkName: String,
        val assetApiUrl: String,
        val assetUrl: String,
        val size: Long,
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** 当前安装的版本号（不依赖 BuildConfig，省一次 gradle 生成） */
    fun installedVersion(): String = runCatching {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "1.0.0"
    }.getOrDefault("1.0.0")

    /** 查最新 Release。Result.success(null) = 已是最新 */
    suspend fun latest(current: String = installedVersion()): Result<Info?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("https://api.github.com/repos/xiebrooooo/ixaut-checkin/releases/latest")
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "iXiliCheckin/$current")
                    .build()
                http.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("GitHub 返回 ${resp.code}（如 404 说明还没有 Release）")
                    }
                    val o = JSONObject(body)
                    val tag = o.optString("tag_name").trim()
                        .removePrefix("v").removePrefix("V")
                    if (tag.isBlank()) return@use null
                    if (!isNewer(tag, current)) return@use null

                    val assets = o.optJSONArray("assets")
                    var name = ""
                    var apiUrl = ""
                    var dlUrl = ""
                    var size = 0L
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val a = assets.optJSONObject(i) ?: continue
                            val n = a.optString("name")
                            if (!n.endsWith(".apk", ignoreCase = true)) continue
                            name = n
                            apiUrl = a.optString("url")
                            dlUrl = a.optString("browser_download_url")
                            size = a.optLong("size")
                            break
                        }
                    }
                    if (dlUrl.isBlank()) return@use null

                    Info(
                        version = tag,
                        notes = o.optString("body"),
                        htmlUrl = o.optString("html_url"),
                        apkName = name.ifBlank { "iXiliCheckin-$tag.apk" },
                        assetApiUrl = apiUrl,
                        assetUrl = dlUrl,
                        size = size,
                    )
                }
            }
        }

    /** 下载到 cacheDir/update/<name>.apk */
    suspend fun download(
        info: Info,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "update").apply { mkdirs() }
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        val out = File(dir, info.apkName)

        val candidates = listOf(info.assetApiUrl, info.assetUrl).filter { it.isNotBlank() }
        var last: Exception? = null

        for (url in candidates) {
            try {
                val b = Request.Builder()
                    .url(url)
                    .header("User-Agent", "iXiliCheckin")
                if (url == info.assetApiUrl) b.header("Accept", "application/octet-stream")
                http.newCall(b.build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                    val body = resp.body ?: throw IllegalStateException("空响应")
                    val total = body.contentLength()
                    body.byteStream().use { input ->
                        out.outputStream().use { o ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                o.write(buf, 0, n)
                                done += n
                                onProgress(done, if (total > 0) total else info.size)
                            }
                        }
                    }
                }
                // 装不上比装错更糟：太小的文件基本是错误页
                if (out.length() < 1024 * 1024) {
                    throw IllegalStateException("下载到的文件只有 ${out.length()} 字节，不像安装包")
                }
                return@withContext out
            } catch (e: Exception) {
                last = e
                runCatching { out.delete() }
            }
        }
        throw last ?: IllegalStateException("下载失败")
    }

    /** 拉起系统安装器。需要 REQUEST_INSTALL_PACKAGES 权限 + FileProvider */
    fun install(apk: File) {
        val uri: Uri = FileProvider.getUriForFile(
            ctx, ctx.packageName + ".fileprovider", apk,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }

    /** 2024.10 > 2024.9 > 1.2.0 这种比较 */
    private fun isNewer(candidate: String, current: String): Boolean {
        fun parts(s: String) = s.split('.', '-', '+')
            .map { it.takeWhile(Char::isDigit) }
            .map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
