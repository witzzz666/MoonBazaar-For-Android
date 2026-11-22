package top.witzzz.moonbazaar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 官网 `get_surveys.php` 响应的解析测试。
 *
 * 该端点返回的 JSON 里带「转义用的反斜杠」（形如 `{\"ok\":true,...}`），
 * 需要 [MoonRepository.parseSiteJson] 兼容后才能当普通 JSON 用。
 */
class SiteJsonParseTest {

    @Test
    fun plainJson() {
        val o = MoonRepository.parseSiteJson("""{"ok":true,"surveys":[]}""")
        assertNotNull(o)
        assertTrue(o!!.optBoolean("ok"))
    }

    @Test
    fun escapedQuotes() {
        val body = """{\"ok\":true,\"surveys\":[{\"id\":7,\"payout\":1.5}]}"""
        val o = MoonRepository.parseSiteJson(body)
        assertNotNull(o)
        assertEquals(1, o!!.optJSONArray("surveys")!!.length())
        assertEquals(7, o.optJSONArray("surveys")!!.optJSONObject(0)!!.optInt("id"))
    }

    @Test
    fun escapedJsonString() {
        val body = "\"{\\\"ok\\\":true,\\\"surveys\\\":[{\\\"id\\\":9}]}\""
        val o = MoonRepository.parseSiteJson(body)
        assertNotNull(o)
        assertEquals(9, o!!.optJSONArray("surveys")!!.optJSONObject(0)!!.optInt("id"))
    }

    @Test
    fun blankIsNull() {
        assertNull(MoonRepository.parseSiteJson("   "))
        assertNull(MoonRepository.parseSiteJson(""))
    }

    @Test
    fun garbageIsNull() {
        assertNull(MoonRepository.parseSiteJson("<html>502 Bad Gateway</html>"))
    }

    @Test
    fun payloadDetection() {
        assertTrue(MoonRepository.isSurveyPayload(MoonRepository.parseSiteJson("""{"surveys":[]}""")))
        assertTrue(
            MoonRepository.isSurveyPayload(
                MoonRepository.parseSiteJson("""{"data":{"surveys":[]}}""")
            )
        )
        assertFalse(MoonRepository.isSurveyPayload(MoonRepository.parseSiteJson("""{"ok":false}""")))
        assertFalse(MoonRepository.isSurveyPayload(null))
    }

    /** 官网真实形态：`data` 直接就是问卷数组，开关是扁平的 `cpx_enabled` 等字段。 */
    @Test
    fun siteEnvelopeWithDataArray() {
        val body = """{"status":"success","country_code":"CN","cpx_enabled":true,""" +
            """"quality_score":54,"data":[{"id":"82112983","provider":"cpx",""" +
            """"provider_full":"CPX Research","payout":3.4,"loi":1,""" +
            """"link":"https:\/\/click.cpx-research.com\/?k=abc"}]}"""
        val o = MoonRepository.parseSiteJson(body)
        assertNotNull(o)
        assertTrue(MoonRepository.isSurveyPayload(o))
        val arr = o!!.optJSONArray("data")!!
        assertEquals(1, arr.length())
        val item = arr.optJSONObject(0)!!
        assertEquals("cpx", item.optString("provider"))
        assertEquals("CPX Research", item.optString("provider_full"))
        assertEquals(3.4, item.optDouble("payout"), 0.0001)
        assertEquals("https://click.cpx-research.com/?k=abc", item.optString("link"))
        assertTrue(o.optBoolean("cpx_enabled"))
    }

    /** 未登录时站点返回小 JSON，不能当成问卷负载。 */
    @Test
    fun notLoggedInIsNotPayload() {
        val o = MoonRepository.parseSiteJson("""{"status":"error","message":"Not logged in"}""")
        assertNotNull(o)
        assertFalse(MoonRepository.isSurveyPayload(o))
    }
}
