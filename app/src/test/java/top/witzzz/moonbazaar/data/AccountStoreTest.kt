package top.witzzz.moonbazaar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 账号存档的编解码测试：三套凭证（Worker session / 官网 zlex_token / mb_token）必须能一起存、一起读回。
 */
class AccountStoreTest {

    private fun sample() = Account(
        uid = "33158",
        username = "witzzz523",
        token = "session-token-abc",
        siteToken = "8f4f46a5426e641a16362642a519b9e551f6ebd24a98c16e6db9d6449174f098",
        mbAccessToken = "mb_at_wMx",
        mbRefreshToken = "mb_rt_yZ",
        mbExpiresAt = 1766145600L
    )

    @Test
    fun `三套 token 一起存取不丢字段`() {
        val raw = Session.encodeAccounts(listOf(sample()))
        val back = Session.decodeAccounts(raw)
        assertEquals(1, back.size)
        assertEquals(sample(), back[0])
        assertTrue(back[0].hasSiteToken)
        assertTrue(back[0].hasMbToken)
    }

    @Test
    fun `老版本 JSON 缺少新字段时降级为空串`() {
        val legacy = """[{"uid":"1","username":"old","token":"tok"}]"""
        val back = Session.decodeAccounts(legacy)
        assertEquals(1, back.size)
        assertEquals("tok", back[0].token)
        assertEquals("", back[0].siteToken)
        assertEquals("", back[0].mbAccessToken)
    }

    @Test
    fun `没有 token 的条目被丢弃`() {
        val raw = """[{"uid":"1","token":"a"},{"uid":"2","token":""}]"""
        assertEquals(1, Session.decodeAccounts(raw).size)
    }

    @Test
    fun `坏 JSON 返回空列表而不是抛异常`() {
        assertTrue(Session.decodeAccounts("not-json").isEmpty())
        assertTrue(Session.decodeAccounts("").isEmpty())
    }

    @Test
    fun `key 优先用 uid，登录早期退回 token 前缀`() {
        assertEquals("uid:33158", sample().key)
        assertEquals("tok:abc123456789", Account(token = "abc123456789xyz").key)
    }
}
