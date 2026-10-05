package top.witzzz.moonbazaar.data

/**
 * 登录回调地址校验：scheme / host / path 必须与 AndroidManifest.xml 中登记的
 * intent-filter 完全一致，只校验 scheme 会把任意 `mnb://...` 都当成合法回调。
 *
 * 纯函数，便于单测。
 */
internal fun isTrustedCallbackUri(scheme: String?, host: String?, path: String?): Boolean =
    scheme?.equals(AppConfig.CALLBACK_SCHEME, ignoreCase = true) == true &&
        host?.equals(AppConfig.CALLBACK_HOST, ignoreCase = true) == true &&
        path.orEmpty().trimEnd('/').equals(AppConfig.CALLBACK_PATH, ignoreCase = true)
