package kr.blueward.biztalk

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.text.InputType
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import kr.blueward.biztalk.data.MessageStore
import kr.blueward.biztalk.data.Settings
import kr.blueward.biztalk.net.ApiClient
import kr.blueward.biztalk.net.ApiProtocol
import kr.blueward.biztalk.sync.Sync
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.provider.Settings as SystemSettings

/** 상태 확인과 설정 화면. 선배님이 처음 설치할 때와 문제가 생겼을 때만 연다. */
class MainActivity : Activity() {

    private lateinit var settings: Settings
    private lateinit var status: TextView
    private lateinit var endpointInput: EditText
    private lateinit var tokenInput: EditText
    private val main = Handler(Looper.getMainLooper())
    private val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.KOREA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(heading("상태"))
        status = TextView(this).apply { textSize = 15f; setLineSpacing(0f, 1.2f) }
        root.addView(status)
        root.addView(button("새로고침") { refresh() })

        root.addView(heading("1. 권한"))
        root.addView(note("두 항목 모두 '정상'이어야 메시지를 놓치지 않습니다."))
        root.addView(button("알림 접근 권한 설정 열기") {
            startActivity(Intent(SystemSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        root.addView(button("배터리 최적화 제외 요청") { requestBatteryExemption() })
        root.addView(button("앱 정보 열기 (제한된 설정 허용, 배터리 '제한 없음')") {
            startActivity(Intent(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        root.addView(note(
            "알림 접근 권한이 회색으로 막혀 있으면: 앱 정보 → 오른쪽 위 ⋮ → '제한된 설정 허용' 후 다시 시도하세요.\n" +
                "삼성폰은 앱 정보 → 배터리 → '제한 없음'도 함께 설정해 주세요."
        ))

        root.addView(heading("2. 서버 연결"))
        endpointInput = input("웹 앱 URL (https://script.google.com/...)", settings.endpoint, InputType.TYPE_TEXT_VARIATION_URI)
        tokenInput = input("API 토큰", settings.token, InputType.TYPE_TEXT_VARIATION_PASSWORD)
        root.addView(endpointInput)
        root.addView(tokenInput)
        root.addView(button("저장하고 연결 테스트") { saveAndTest() })

        root.addView(heading("3. 전송"))
        root.addView(button("보관 중인 메시지 지금 보내기") {
            Sync.uploadNow(this)
            toast("전송을 시작했습니다. 잠시 뒤 새로고침하세요.")
        })

        setContentView(ScrollView(this).apply { addView(root, MATCH_PARENT, WRAP_CONTENT) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val listenerGranted = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        val batteryExempt = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        val pending = MessageStore.get(this).pendingCount()
        status.text = buildString {
            line("알림 접근 권한", if (listenerGranted) "정상" else "꺼짐 ← 1번에서 허용하세요")
            line("배터리 최적화 제외", if (batteryExempt) "정상" else "아님 ← 1번에서 요청하세요")
            line("서버 설정", if (settings.isConfigured) "입력됨" else "없음 ← 2번에서 입력하세요")
            line("보관 중(전송 대기)", "${pending}건")
            line("지금까지 수집", "${settings.capturedTotal}건, 마지막 ${time(settings.lastCaptureAt)}")
            line("마지막 전송", "${time(settings.lastUploadAt)} ${settings.lastUploadResult.orEmpty()}")
            line("마지막 생존 신호", time(settings.lastHeartbeatAt))
            settings.lastError?.let { line("최근 오류", "${time(settings.lastErrorAt)} $it") }
            append("앱 버전 ").append(settings.appVersion)
        }
    }

    private fun saveAndTest() {
        val endpoint = endpointInput.text.toString().trim()
        val token = tokenInput.text.toString().trim()
        if (!endpoint.startsWith("https://")) {
            toast("웹 앱 URL은 https:// 로 시작해야 합니다.")
            return
        }
        if (token.isEmpty()) {
            toast("API 토큰을 입력하세요.")
            return
        }
        settings.endpoint = endpoint
        settings.token = token
        toast("저장했습니다. 연결을 확인하는 중…")
        Thread {
            val message = try {
                val res = ApiProtocol.parseResponse(
                    ApiClient(endpoint).post(ApiProtocol.heartbeatRequest(token, settings.appVersion, settings.device))
                )
                when {
                    res.ok -> { settings.recordHeartbeat(); settings.clearError(); "연결 성공. 시트의 상태 탭을 확인해 보세요." }
                    res.isUnauthorized -> "토큰이 맞지 않습니다. Apps Script 로그의 API_TOKEN을 다시 확인하세요."
                    else -> "서버 응답 오류: ${res.error}"
                }
            } catch (e: Exception) {
                "연결 실패: ${e.message}"
            }
            main.post {
                toast(message)
                Sync.ensureHeartbeat(this)
                refresh()
            }
        }.start()
    }

    @SuppressLint("BatteryLife") // 상시 수집 앱이라 배터리 최적화 제외가 필요하다. 스토어 배포 앱이 아니다.
    private fun requestBatteryExemption() {
        startActivity(Intent(SystemSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    private fun StringBuilder.line(label: String, value: String) {
        append(label).append(": ").append(value).append('\n')
    }

    private fun time(ms: Long) = if (ms <= 0) "없음" else fmt.format(Date(ms))

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        textSize = 18f
        setPadding(0, dp(20), 0, dp(6))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    private fun input(hint: String, value: String, variation: Int) = EditText(this).apply {
        this.hint = hint
        setText(value)
        inputType = InputType.TYPE_CLASS_TEXT or variation
        isSingleLine = true
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
