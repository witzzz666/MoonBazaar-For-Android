package top.witzzz.moonbazaar.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 问卷完成 / 退出回调解析单测。
 */
class SurveyResultParseTest {

    private fun parse(query: Map<String, String>) =
        parseSurveyResult("moonbazaar.xyz", "/home/surveys", query)

    // ---------------- 普通形式 status / gc ----------------

    @Test
    fun status1_isSuccess_withGc() {
        val r = parse(mapOf("status" to "1", "gc" to "42"))!!
        assertEquals(SurveyOutcome.SUCCESS, r.outcome)
        assertEquals("42", r.gcText)
        assertFalse(r.checkHistory)
    }

    @Test
    fun status2_isIneligible_withGc() {
        val r = parse(mapOf("status" to "2", "gc" to "5"))!!
        assertEquals(SurveyOutcome.INELIGIBLE, r.outcome)
        assertEquals("5", r.gcText)
    }

    @Test
    fun checkHistory_hasNoAmount() {
        val r = parse(mapOf("status" to "1", "gc" to "check_history"))!!
        assertEquals(SurveyOutcome.SUCCESS, r.outcome)
        assertTrue(r.checkHistory)
        assertNull(r.gcText)   // 不显示具体 GC
    }

    @Test
    fun status1_withoutGc() {
        val r = parse(mapOf("status" to "1"))!!
        assertEquals(SurveyOutcome.SUCCESS, r.outcome)
        assertNull(r.gcText)
        assertFalse(r.checkHistory)
    }

    // ---------------- CPX 形式 type / GC / cpx_message_id ----------------

    @Test
    fun cpxComplete_withAmountAndRatingUrl() {
        val r = parse(
            mapOf(
                "type" to "complete",
                "GC" to "30",
                "cpx_message_id" to "https://cpx.example/rate/123",
                "from" to "cpx"
            )
        )!!
        assertEquals(SurveyOutcome.SUCCESS, r.outcome)
        assertEquals("30", r.gcText)            // GC 大小写均可识别
        assertEquals("https://cpx.example/rate/123", r.ratingUrl)
    }

    @Test
    fun cpxOut_withCompensation() {
        val r = parse(mapOf("type" to "out", "GC" to "10", "from" to "cpx"))!!
        assertEquals(SurveyOutcome.OUT, r.outcome)
        assertEquals("10", r.gcText)
        assertNull(r.ratingUrl)
    }

    @Test
    fun cpxOut_withoutAmount() {
        val r = parse(mapOf("type" to "out", "from" to "cpx"))!!
        assertEquals(SurveyOutcome.OUT, r.outcome)
        assertNull(r.gcText)                    // 没有金额就不显示
    }

    @Test
    fun nonUrlMessageId_hasNoRatingLink() {
        val r = parse(mapOf("type" to "complete", "cpx_message_id" to "MSG-123"))!!
        assertNull(r.ratingUrl)                 // 不是网址则不给评价入口
    }

    // ---------------- 不应误判 ----------------

    @Test
    fun otherHost_isIgnored() {
        assertNull(parseSurveyResult("example.com", "/home/surveys", mapOf("status" to "1")))
    }

    @Test
    fun otherPath_isIgnored() {
        assertNull(parseSurveyResult("moonbazaar.xyz", "/home/other", mapOf("status" to "1")))
    }

    @Test
    fun unrelatedQuery_isIgnored() {
        // 普通浏览导航（没有 status=1/2、type、message_id）不应被拦成回调
        assertNull(parse(mapOf("page" to "2", "status" to "all")))
    }

    @Test
    fun pathWithTrailingSlash_stillMatches() {
        val r = parseSurveyResult(
            "moonbazaar.xyz", "/home/surveys/", mapOf("status" to "2", "gc" to "3")
        )!!
        assertEquals(SurveyOutcome.INELIGIBLE, r.outcome)
    }
}
