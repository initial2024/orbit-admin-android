package de.kango99w.orbitadmin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** No account device grant is an administrator authentication token. */
class NativeLoginFeedbackTest {
    @Test fun local400IsNotMisdiagnosedAsServerAuthorizationFailure() {
        val result = NativeLoginFeedback.describe(400, "NATIVE_LOGIN_INPUT_INVALID")
        assertTrue(result.contains("本机"))
        assertTrue(result.contains("尚未发送"))
        assertFalse(result.contains("功能开关"))
    }

    @Test fun remote400ShowsContractMismatchInsteadOfPasswordAdvice() {
        val result = NativeLoginFeedback.describe(400, "NATIVE_PAIR_PAYLOAD")
        assertTrue(result.contains("HTTP 400"))
        assertTrue(result.contains("协议版本"))
    }

    @Test fun missingNativeRouteDoesNotPromptRepeatedLogin() {
        val result = NativeLoginFeedback.describe(405, "NATIVE_METHOD")
        assertTrue(result.contains("尚未提供"))
        assertTrue(result.contains("重复登录"))
    }

    @Test fun accountAndAdminRealmsAreNotConflated() {
        val result = NativeLoginFeedback.describe(401, "NATIVE_LOGIN_REJECTED")
        assertTrue(result.contains("普通账号"))
        assertTrue(result.contains("管理员"))
        assertFalse(result.contains("已获得管理员"))
    }

    @Test fun credentialLoginFlagIsDistinctFromDeviceReadFlag() {
        val result = NativeLoginFeedback.describe(503, "NATIVE_CREDENTIAL_LOGIN_DISABLED")
        assertTrue(result.contains("独立上线门禁"))
        assertTrue(result.contains("普通账号"))
        assertTrue(result.contains("不要反复提交密码"))
    }

    @Test fun allFailuresExplicitlyRemainUnbound() {
        for (status in listOf(400, 401, 403, 404, 405, 409, 503)) {
            val result = NativeLoginFeedback.describe(status, null)
            assertFalse(result.contains("绑定成功"))
        }
    }
}
