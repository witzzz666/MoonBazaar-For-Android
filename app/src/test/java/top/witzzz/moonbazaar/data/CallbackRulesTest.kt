package top.witzzz.moonbazaar.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 登录回调深链校验单测。
 *
 * 目标：只接受与 AndroidManifest 中 intent-filter 完全一致的地址，
 * 避免任意 `mnb://...` 被当成合法回调注入令牌。
 */
class CallbackRulesTest {

    @Test
    fun exactCallback_isTrusted() {
        assertTrue(isTrustedCallbackUri("mnb", "auth", "/callback"))
    }

    @Test
    fun schemeAndHost_areCaseInsensitive() {
        assertTrue(isTrustedCallbackUri("MNB", "AUTH", "/callback"))
    }

    @Test
    fun trailingSlash_isTolerated() {
        assertTrue(isTrustedCallbackUri("mnb", "auth", "/callback/"))
    }

    // ---------------- 必须拒绝 ----------------

    @Test
    fun wrongScheme_isRejected() {
        assertFalse(isTrustedCallbackUri("https", "auth", "/callback"))
        assertFalse(isTrustedCallbackUri("mnbx", "auth", "/callback"))
    }

    @Test
    fun wrongHost_isRejected() {
        assertFalse(isTrustedCallbackUri("mnb", "evil", "/callback"))
        // 相似但不相同的主机名不能被放过
        assertFalse(isTrustedCallbackUri("mnb", "auth.evil.com", "/callback"))
        assertFalse(isTrustedCallbackUri("mnb", "evil.com", "/callback"))
    }

    @Test
    fun wrongPath_isRejected() {
        assertFalse(isTrustedCallbackUri("mnb", "auth", "/callback2"))
        assertFalse(isTrustedCallbackUri("mnb", "auth", "/callback/../x"))
        assertFalse(isTrustedCallbackUri("mnb", "auth", "/"))
    }

    @Test
    fun missingParts_areRejected() {
        assertFalse(isTrustedCallbackUri("mnb", null, "/callback"))
        assertFalse(isTrustedCallbackUri("mnb", "auth", null))
        assertFalse(isTrustedCallbackUri(null, "auth", "/callback"))
    }
}
