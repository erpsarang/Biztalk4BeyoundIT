package kr.blueward.biztalk.data

import android.content.Context
import android.os.Build
import kr.blueward.biztalk.BuildConfig
import kr.blueward.biztalk.sync.UploadLog

/** 서버 주소·토큰과 화면에 보여 줄 상태 기록. 앱 전용 저장소에만 있다. */
class Settings(context: Context) : UploadLog {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var endpoint: String
        get() = prefs.getString(KEY_ENDPOINT, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_ENDPOINT, v.trim()).apply()

    var token: String
        get() = prefs.getString(KEY_TOKEN, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_TOKEN, v.trim()).apply()

    val isConfigured get() = endpoint.startsWith("https://") && token.isNotEmpty()

    val appVersion: String get() = BuildConfig.VERSION_NAME
    val device: String get() = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})"

    fun recordCapture(count: Int) = prefs.edit()
        .putLong(KEY_LAST_CAPTURE_AT, System.currentTimeMillis())
        .putInt(KEY_CAPTURED_TOTAL, prefs.getInt(KEY_CAPTURED_TOTAL, 0) + count)
        .apply()

    fun recordListenerConnected(connected: Boolean) =
        prefs.edit().putBoolean(KEY_LISTENER_CONNECTED, connected).apply()

    override fun recordUpload(stored: Int, sent: Int) = prefs.edit()
        .putLong(KEY_LAST_UPLOAD_AT, System.currentTimeMillis())
        .putString(KEY_LAST_UPLOAD_RESULT, "보냄 ${sent}건, 시트 저장 ${stored}건")
        .apply()

    fun recordHeartbeat() = prefs.edit().putLong(KEY_LAST_HEARTBEAT_AT, System.currentTimeMillis()).apply()

    override fun recordError(message: String?) = prefs.edit()
        .putLong(KEY_LAST_ERROR_AT, System.currentTimeMillis())
        .putString(KEY_LAST_ERROR, message.orEmpty().take(300))
        .apply()

    override fun clearError() = prefs.edit().remove(KEY_LAST_ERROR).remove(KEY_LAST_ERROR_AT).apply()

    val lastCaptureAt get() = prefs.getLong(KEY_LAST_CAPTURE_AT, 0)
    val capturedTotal get() = prefs.getInt(KEY_CAPTURED_TOTAL, 0)
    val listenerConnected get() = prefs.getBoolean(KEY_LISTENER_CONNECTED, false)
    val lastUploadAt get() = prefs.getLong(KEY_LAST_UPLOAD_AT, 0)
    val lastUploadResult get() = prefs.getString(KEY_LAST_UPLOAD_RESULT, null)
    val lastHeartbeatAt get() = prefs.getLong(KEY_LAST_HEARTBEAT_AT, 0)
    val lastError get() = prefs.getString(KEY_LAST_ERROR, null)
    val lastErrorAt get() = prefs.getLong(KEY_LAST_ERROR_AT, 0)

    private companion object {
        const val KEY_ENDPOINT = "endpoint"
        const val KEY_TOKEN = "token"
        const val KEY_LAST_CAPTURE_AT = "last_capture_at"
        const val KEY_CAPTURED_TOTAL = "captured_total"
        const val KEY_LISTENER_CONNECTED = "listener_connected"
        const val KEY_LAST_UPLOAD_AT = "last_upload_at"
        const val KEY_LAST_UPLOAD_RESULT = "last_upload_result"
        const val KEY_LAST_HEARTBEAT_AT = "last_heartbeat_at"
        const val KEY_LAST_ERROR = "last_error"
        const val KEY_LAST_ERROR_AT = "last_error_at"
    }
}
