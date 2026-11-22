package top.witzzz.moonbazaar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `/home/mb_token` 响应解析单测。
 *
 * 该接口是 App 每次启动申请 MoonBazaar token 的入口，
 * 这里覆盖正常返回、`?hide_refresh=1`、错误响应和字段缺失等情况。
 */
class MbTokenParseTest {

    private fun parse(body: String) = MoonRepository.parseMbToken(body)

    @Test
    fun fullToken_parsedWithRefresh() {
        val body = """
            {"ok":true,"data":{
              "uid":"31791",
              "access_token":"mb_at_xxxx",
              "refresh_token":"mb_rt_yyyy",
              "token_type":"Bearer",
              "expires_at":1751625600000,
              "expires_in":2591999,
              "scope":"account.read balance.read games.read",
              "expired":false
            }}
        """.trimIndent()

        val (t, err) = parse(body)
        assertEquals("", err)
        val token = t!!
        assertEquals("31791", token.uid)
        assertEquals("mb_at_xxxx", token.accessToken)
        assertEquals("mb_rt_yyyy", token.refreshToken)
        assertEquals("Bearer", token.tokenType)
        assertEquals(1751625600000L, token.expiresAt)
        assertEquals(2591999L, token.expiresIn)
        assertEquals(3, token.scopeCount)
        assertFalse(token.expired)
    }

    @Test
    fun hideRefresh_hasNoRefreshToken() {
        val body = """{"ok":true,"data":{"uid":"1","access_token":"mb_at_a","expires_at":1,"expires_in":2,"scope":"account.read"}}"""
        val (t, _) = parse(body)
        assertEquals("mb_at_a", t!!.accessToken)
        assertEquals("", t.refreshToken)
        assertEquals(1, t.scopeCount)
    }

    @Test
    fun expiredFlag_isKept() {
        val body = """{"ok":true,"data":{"access_token":"mb_at_a","expired":true,"scope":""}}"""
        val (t, _) = parse(body)
        assertTrue(t!!.expired)
        assertEquals(0, t.scopeCount)
        assertEquals("Bearer", t.tokenType)   // 缺省回退
    }

    @Test
    fun errorResponse_returnsMessage() {
        val body = """
            {"ok":false,"error":{"code":"SESSION_INVALID",
             "message":"Session not found or expired — re-authenticate via /auth/login"}}
        """.trimIndent()
        val (t, err) = parse(body)
        assertNull(t)
        assertTrue(err.contains("Session not found"))
    }

    @Test
    fun missingAccessToken_isRejected() {
        val (t, err) = parse("""{"ok":true,"data":{"uid":"1","refresh_token":"r"}}""")
        assertNull(t)
        assertEquals("响应缺少 access_token", err)
    }

    @Test
    fun missingData_isRejected() {
        val (t, err) = parse("""{"ok":true}""")
        assertNull(t)
        assertEquals("响应缺少 data", err)
    }

    @Test
    fun blankBody_isRejected() {
        val (t, err) = parse("")
        assertNull(t)
        assertTrue(err.isNotBlank())
    }
}
