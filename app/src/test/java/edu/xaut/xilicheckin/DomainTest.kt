package edu.xaut.xilicheckin

import edu.xaut.xilicheckin.domain.FieldInfo
import edu.xaut.xilicheckin.domain.KeyFields
import edu.xaut.xilicheckin.domain.QrParser
import edu.xaut.xilicheckin.domain.SignEngine
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯逻辑单测：二维码解析 + 真实表单 schema 解析 + 提交体拼装。
 * 断言全部对齐 2026-10 用真实账号抓到的接口形状（见 src/api.js 注释）。
 */
class DomainTest {

    /** 真实签到二维码（内容是「考勤扫码上报明细」页面链接） */
    private val realQr =
        "https://kqtj.xaut.edu.cn/adp/runtime/685488207969587200/form/690161299459346432" +
            "?id=878186883417313280&keyCur=1792454469500"

    /** 从真实 /form 响应里裁出来的 schema 片段（结构一字未改） */
    private val schemaBody = """
    {"code":"0","msg":"成功","response":{"form":{"config":{"buttonText":"考勤打卡"},
      "schemas":[{"children":[{"children":[
        {"field":"inputField_k81gytpo","label":"姓名","component":"Input","required":false,
         "defaultValueProps":{"type":"name","value":"张三"}},
        {"field":"inputField_d31z1d78","label":"学号","component":"Input","required":false,
         "defaultValueProps":{"type":"uid","value":"2024000000"}},
        {"field":"selectField_8d3token","label":"考勤类型","component":"Select","required":true,
         "defaultValueProps":{"type":"-1","value":""}},
        {"field":"selectField_mtdl36ss","label":"考勤轮次","component":"Select","required":true,
         "defaultValueProps":{"type":"-1","value":""}},
        {"field":"selectField_2ai1975l","label":"不可见-考勤上报记录","component":"Select","required":true,
         "defaultValueProps":{"type":"-1","value":""}},
        {"field":"inputField_ry586mok","label":"不可见-考勤上报id","component":"Input","required":false,
         "defaultValueProps":{"type":"-2","value":""}}
      ]}]}]}}}
    """.trimIndent()

    @Test
    fun `解析真实签到链接`() {
        val t = QrParser.parse(realQr)
        assertEquals("url", t.kind)
        assertEquals("685488207969587200", t.appId)
        assertEquals("690161299459346432", t.pageId)
        assertEquals("form", t.verb)
        assertTrue(t.ok)
        assertEquals("878186883417313280", SignEngine.recordIdOf(t))
    }

    @Test
    fun `链接带 token 时能取出来`() {
        val t = QrParser.parse("$realQr&token=abc123def&from=wechat")
        assertEquals("abc123def", t.token)
        assertEquals("wechat", t.query["from"])
        assertTrue(t.ok)
    }

    @Test
    fun `JSON 载荷`() {
        val t = QrParser.parse("""{"appId":"685488207969587200","formId":"690161299459346432","id":"878186883417313280"}""")
        assertEquals("json", t.kind)
        assertEquals("685488207969587200", t.appId)
        assertEquals("690161299459346432", t.pageId)
        assertEquals("878186883417313280", SignEngine.recordIdOf(t))
        assertTrue(t.ok)
    }

    @Test
    fun `短数字不是雪花ID不误判`() {
        val t = QrParser.parse("https://kqtj.xaut.edu.cn/adp/runtime/1001/form/2002")
        assertFalse(t.ok)
    }

    @Test
    fun `裸 token 兜底`() {
        val t = QrParser.parse("8f2c1d9e4b7a6c5d")
        assertEquals("token", t.kind)
        assertEquals("8f2c1d9e4b7a6c5d", t.token)
    }

    @Test
    fun `从真实 schema 里掏出字段和服务端默认值`() {
        val snap = SignEngine.parseFormModel(schemaBody)!!
        assertEquals(6, snap.fields.size)
        assertEquals("张三", snap.model.getString("inputField_k81gytpo"))
        assertEquals("2024000000", snap.model.getString("inputField_d31z1d78"))
        // 空值不写进模型，避免把服务端预填覆盖成空
        assertFalse(snap.model.has("selectField_8d3token"))
    }

    @Test
    fun `按 label 认出关键字段`() {
        val snap = SignEngine.parseFormModel(schemaBody)!!
        assertEquals("inputField_ry586mok", snap.keys.recordIdField)
        assertEquals("selectField_2ai1975l", snap.keys.recordSelectField)
        assertEquals("selectField_8d3token", snap.keys.typeField)
        assertEquals("selectField_mtdl36ss", snap.keys.roundField)
    }

    @Test
    fun `回填值覆盖默认值并补齐必填三项`() {
        val snap = SignEngine.parseFormModel(schemaBody)!!
        val row = JSONObject(
            """{"label":"英语B3-本科生-曲江10-514","value":"878186883417313280",
                "datepickerField_a96wke0u":"2026-10-20",
                "selectField_mtdl36ss":{"label":"第1轮","value":"1"},
                "selectField_8d3token":{"label":"正常签到","value":"ZCQD"}}"""
        )
        val (model, filled) = SignEngine.buildModel(
            template = snap.model,
            row = row,
            keys = snap.keys,
            recordId = "878186883417313280",
            overrides = emptyMap(),
            fields = snap.fields,
        )
        assertEquals("张三", model.getString("inputField_k81gytpo"))
        assertEquals("2026-10-20", model.getString("datepickerField_a96wke0u"))
        assertEquals("1", model.getJSONObject("selectField_mtdl36ss").getString("value"))
        assertEquals("ZCQD", model.getJSONObject("selectField_8d3token").getString("value"))
        assertEquals(
            "878186883417313280",
            model.getJSONObject("selectField_2ai1975l").getString("value"),
        )
        assertEquals("878186883417313280", model.getString("inputField_ry586mok"))
        assertTrue(filled.isNotEmpty())
    }

    @Test
    fun `考勤类型缺失时兜底为正常签到`() {
        val snap = SignEngine.parseFormModel(schemaBody)!!
        val (model, _) = SignEngine.buildModel(
            template = snap.model,
            row = JSONObject("""{"label":"x","value":"878186883417313280"}"""),
            keys = snap.keys,
            recordId = "878186883417313280",
            overrides = emptyMap(),
            fields = snap.fields,
        )
        assertEquals("ZCQD", model.getJSONObject("selectField_8d3token").getString("value"))
    }

    @Test
    fun `用户覆盖下拉字段写成 label value 对象`() {
        val fields = listOf(
            FieldInfo("selectField_8d3token", "考勤类型", "Select", "", required = true)
        )
        val (model, _) = SignEngine.buildModel(
            template = JSONObject(),
            row = JSONObject("""{"label":"x","value":"1"}"""),
            keys = KeyFields(typeField = "selectField_8d3token"),
            recordId = "1",
            overrides = mapOf("selectField_8d3token" to "补签|BQ"),
            fields = fields,
        )
        assertEquals("BQ", model.getJSONObject("selectField_8d3token").getString("value"))
        assertEquals("补签", model.getJSONObject("selectField_8d3token").getString("label"))
    }

    // ==================== ifShow=false 过滤 + keyCur 注入 ====================
    //
    // 依据：2026-10 用 playwright 实测点「考勤打卡」抓到的真实 POST body
    // （见 xili-checkin/.work/pw/click-dump3.json 与 docs/CHECKIN-CLICK.md）。
    // 浏览器**不提交** label 带「（不生效）」的 ifShow=false 字段，但**会提交**
    // show=false / ifShow=true 的缓存 key 字段（inputField_oljsq26s）。

    /** 含 ifShow 标记的 schema 片段：3 活 + 1 死 + 1 缓存 key */
    private val ifShowSchemaBody = """
    {"code":"0","msg":"成功","response":{"form":{"config":{"buttonText":"考勤打卡"},
    "schemas":[{"children":[{"children":[
    {"field":"inputField_k81gytpo","label":"姓名","component":"Input","ifShow":true,"show":true,
     "defaultValueProps":{"type":"name","value":"张三"}},
    {"field":"inputnumberField_u8o8vmj6","label":"不可见-周次（不生效）","component":"InputNumber",
     "ifShow":false,"show":false,"defaultValueProps":{"type":"-2","value":"0"}},
    {"field":"selectField_zkg34y4n","label":"不可见-考勤形式（不生效）","component":"Select",
     "ifShow":false,"show":false,"defaultValueProps":{"type":"-1","value":""}},
    {"field":"inputField_oljsq26s","label":"不可见-缓存key","component":"Input",
     "ifShow":true,"show":false,"defaultValueProps":{"type":"-2","value":""}},
    {"field":"selectField_2ai1975l","label":"不可见-考勤上报记录","component":"Select",
     "ifShow":true,"show":false,"defaultValueProps":{"type":"-1","value":""}}
    ]}]}]}}}
    """.trimIndent()

    private val KEYCUR = "1792454469500"

    @Test
    fun `ifShow=false 的不生效字段不进提交体`() {
        val snap = SignEngine.parseFormModel(ifShowSchemaBody)!!
        val (model, _) = SignEngine.buildModel(
            template = snap.model,
            row = JSONObject("""{"label":"英语B3","value":"878186883417313280"}"""),
            keys = snap.keys,
            recordId = "878186883417313280",
            overrides = emptyMap(),
            fields = snap.fields,
            keyCur = KEYCUR,
        )
        assertFalse(model.has("inputnumberField_u8o8vmj6"))
        assertFalse(model.has("selectField_zkg34y4n"))
        // ifShow=false 的字段即便服务端默认值非空、或回填行里塞了脏值，也必须剔除
        val (model2, _) = SignEngine.buildModel(
            template = JSONObject("""{"inputnumberField_u8o8vmj6":"0"}"""),
            row = JSONObject(
                """{"label":"x","value":"1","selectField_zkg34y4n":{"label":"扫码发起","value":"SMFQ"}}"""
            ),
            keys = snap.keys,
            recordId = "1",
            overrides = emptyMap(),
            fields = snap.fields,
            keyCur = KEYCUR,
        )
        assertFalse(model2.has("inputnumberField_u8o8vmj6"))
        assertFalse(model2.has("selectField_zkg34y4n"))
    }

    @Test
    fun `show=false 但 ifShow=true 的必填字段不能被误删`() {
        val snap = SignEngine.parseFormModel(ifShowSchemaBody)!!
        val (model, _) = SignEngine.buildModel(
            template = snap.model,
            row = JSONObject("""{"label":"英语B3","value":"878186883417313280"}"""),
            keys = snap.keys,
            recordId = "878186883417313280",
            overrides = emptyMap(),
            fields = snap.fields,
            keyCur = KEYCUR,
        )
        assertEquals(
            "878186883417313280",
            model.getJSONObject("selectField_2ai1975l").getString("value"),
        )
        assertEquals("张三", model.getString("inputField_k81gytpo"))
    }

    @Test
    fun `keyCur 回填到缓存 key 字段，没有就不填`() {
        val snap = SignEngine.parseFormModel(ifShowSchemaBody)!!
        val (model, filled) = SignEngine.buildModel(
            template = snap.model,
            row = JSONObject("""{"label":"x","value":"1"}"""),
            keys = snap.keys,
            recordId = "1",
            overrides = emptyMap(),
            fields = snap.fields,
            keyCur = KEYCUR,
        )
        assertEquals("inputField_oljsq26s", snap.keys.cacheKeyField)
        assertEquals(KEYCUR, model.getString("inputField_oljsq26s"))
        assertTrue(filled.any { it.startsWith("inputField_oljsq26s") })

        val (model2, _) = SignEngine.buildModel(
            template = snap.model,
            row = JSONObject("""{"label":"x","value":"1"}"""),
            keys = snap.keys,
            recordId = "1",
            overrides = emptyMap(),
            fields = snap.fields,
            keyCur = "",
        )
        assertFalse(model2.has("inputField_oljsq26s"))
    }
}
