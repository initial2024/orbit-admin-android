package de.kango99w.orbitadmin

/**
 * Account-only login diagnostics. Local validation failures must never be
 * presented as remote HTTP errors or evidence that the server feature flag is off.
 * Admin authentication is a distinct realm and is not represented here.
 */
object NativeLoginFeedback {
    fun describe(status: Int, code: String?): String = when {
        code == "NATIVE_LOGIN_INPUT_INVALID" ->
            "本机输入校验未通过，请检查完整邮箱格式、密码长度（8～128 位）和设备名称（1～60 字）。请求尚未发送到服务器。"
        code == "NATIVE_TOTP_REQUIRED" ->
            "普通账号已开启 2FA，请输入验证器当前动态码。"
        code == "NATIVE_LOGIN_REJECTED" ->
            "普通账号密码、动态验证码或账户资格验证未通过；不会尝试管理员登录。"
        code == "NATIVE_DEVICE_LIMIT" || code == "NATIVE_PAIR_CONFLICT" ->
            "普通账号设备授权已达上限，或绑定请求发生冲突，请在账户安全中心检查已授权设备。"
        code == "NATIVE_PAIR_PAYLOAD" ->
            "服务器拒绝请求字段（HTTP 400），请核对 APK 与原生登录接口协议版本。"
        code == "NATIVE_PAIR_INVALID" ->
            "服务器拒绝随机数或签名时间（HTTP 400），请确认手机自动时间和时区。"
        code == "NATIVE_DEVICE_API_DISABLED" ->
            "服务器已拒绝原生设备 API（503）；请检查部署版本与功能开关。"
        code == "NATIVE_NETWORK_UNAVAILABLE" ->
            "原生 HTTPS 请求失败。请检查网络或系统时间；没有发放设备授权。"
        status == 400 -> "服务器拒绝原生登录请求格式（HTTP 400）；请核对客户端与接口版本。"
        status == 404 || status == 405 ->
            "服务器尚未提供当前版本的原生账号登录接口（HTTP $status）；不能通过重复登录修复。"
        status == 401 -> "普通账号认证未通过（HTTP 401）；管理员身份必须从独立入口验证。"
        status == 403 -> "原生设备证明或请求来源遭到拒绝（HTTP 403），未发放授权。"
        status == 409 -> "原生设备授权冲突（HTTP 409），请检查设备上限。"
        status == 429 -> "服务器已限流（HTTP 429），请停止重复尝试。"
        status == 503 -> "原生登录服务暂不可用（HTTP 503），请检查生产部署。"
        else -> "普通账号绑定未完成（HTTP $status）；尚未获得设备授权。"
    }
}
