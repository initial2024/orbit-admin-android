package de.kango99w.orbitadmin

/**
 * Pure fail-closed main-frame failure policy: never treat subresource failures as
 * a failed page, never retry auth/form submissions, never log secret URL state.
 * Error constants mirror Android WebViewClient's documented integer values.
 */
object AvenqirLoadFailurePolicy {
    fun shouldHandle(isMainFrame: Boolean, url: String): Boolean =
        isMainFrame && AvenqirUrlPolicy.isFirstParty(url)

    fun safeRetryUrl(url: String?): String? =
        AvenqirResumeRoutePolicy.publicPageWithoutState(url)

    fun isServerError(statusCode: Int): Boolean = statusCode in 500..599

    fun networkFailureText(code: Int): String = when (code) {
        -2 -> "域名解析失败（DNS，错误码 -2）"
        -6 -> "TCP 连接失败（错误码 -6）"
        -7 -> "网络数据传输失败（错误码 -7）"
        -8 -> "连接超时（错误码 -8）"
        -9 -> "重定向循环（错误码 -9）"
        -11 -> "TLS 握手失败（错误码 -11）"
        -12 -> "网址格式错误（错误码 -12）"
        -16 -> "WebView 安全浏览限制（错误码 -16）"
        else -> "网络请求失败（WebView 错误码 $code；具体原因未确认）"
    }

    fun shouldAutoRetryHome(
        url: String, method: String?, code: Int, alreadyRetried: Boolean
    ): Boolean =
        !alreadyRetried && method.equals("GET", ignoreCase = true) &&
            url == AvenqirUrlPolicy.HOME && code in setOf(-1, -2, -6, -7, -8)
}
