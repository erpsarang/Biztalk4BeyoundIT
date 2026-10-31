package kr.blueward.kakaodiag

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        status = TextView(this).apply { textSize = 15f }
        root.addView(status)
        root.addView(TextView(this).apply {
            textSize = 13f
            text = "\n이 앱은 카톡 알림을 가공 없이 기록하는 진단용 앱입니다. " +
                "기록에는 실제 메시지 내용이 담기니 신뢰하는 사람에게만 공유하세요.\n\n" +
                "권한 설정 화면에서 이 앱이 회색으로 막혀 있으면: 설정 → 애플리케이션 → " +
                "카톡 알림 진단 → 오른쪽 위 ⋮ → '제한된 설정 허용'을 누른 뒤 다시 시도하세요.\n"
        })
        root.addView(button("1. 알림 접근 권한 설정 열기") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        root.addView(button("2. 최근 기록 요약 보기") { showSummary() })
        root.addView(button("3. 기록 파일 공유하기") { share() })
        root.addView(button("기록 모두 지우기") { confirmClear() })
        output = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
        }
        root.addView(output)
        setContentView(ScrollView(this).apply { addView(root, MATCH_PARENT, WRAP_CONTENT) })
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    private fun refreshStatus() {
        val granted = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        val entries = DumpStore.readAll(this)
        val posted = entries.count { it.optString("event") == "posted" }
        status.text = buildString {
            append("알림 접근 권한: ").append(if (granted) "허용됨" else "허용 안 됨 (1번 버튼)").append('\n')
            append("카톡 버전: ").append(kakaoVersion() ?: "설치 안 됨").append('\n')
            append("기록된 카톡 알림: ").append(posted).append("건 (제거 이벤트 ")
            append(entries.size - posted).append("건)")
        }
    }

    private fun kakaoVersion(): String? = try {
        packageManager.getPackageInfo(KakaoListener.KAKAO_PACKAGE, 0).versionName
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    /** 사람이 바로 읽을 수 있게 핵심 필드만 뽑아 최근 20건을 보여 준다. */
    private fun showSummary() {
        refreshStatus()
        val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.KOREA)
        val posted = DumpStore.readAll(this).filter { it.optString("event") == "posted" }.takeLast(20).reversed()
        if (posted.isEmpty()) {
            output.text = "\n아직 기록이 없습니다. 카톡 메시지를 받아 보세요."
            return
        }
        output.text = posted.joinToString("\n\n", prefix = "\n") { e ->
            val ex = e.optJSONObject("extras") ?: JSONObject()
            val n = e.optJSONObject("notification") ?: JSONObject()
            val sbn = e.optJSONObject("sbn") ?: JSONObject()
            val messages = ex.optJSONArray("android.messages")
            buildString {
                append("[").append(fmt.format(Date(sbn.optLong("postTime")))).append("]")
                if (n.optBoolean("isGroupSummary")) append(" (그룹 요약 알림)")
                append("\n제목(title): ").append(ex.optString("android.title"))
                append("\n본문(text): ").append(ex.optString("android.text"))
                append("\nsubText: ").append(ex.optString("android.subText"))
                append("\n대화 제목(conversationTitle): ").append(ex.optString("android.conversationTitle"))
                append("\n단톡 여부(isGroupConversation): ").append(ex.opt("android.isGroupConversation"))
                append("\nmessages 개수: ").append(messages?.length() ?: 0)
                if (messages != null && messages.length() > 0) {
                    val last = messages.optJSONObject(messages.length() - 1)
                    val sender = last?.optJSONObject("sender_person")?.optString("name")
                        ?: last?.optString("sender")
                    append("\n  마지막 메시지 보낸 사람: ").append(sender)
                    append("\n  마지막 메시지 내용: ").append(last?.optString("text"))
                    append("\n  마지막 메시지 시각: ").append(last?.optLong("time")?.let { fmt.format(Date(it)) })
                }
            }
        }
    }

    private fun share() {
        val header = JSONObject()
            .put("event", "header")
            .put("exportedAt", System.currentTimeMillis())
            .put("device", deviceInfo())
            .put("kakaoVersion", kakaoVersion())
        val file = DumpStore.export(this, header)
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "카톡 알림 진단 기록")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "진단 기록 공유"))
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setMessage("기록을 모두 지울까요?")
            .setPositiveButton("지우기") { _, _ ->
                DumpStore.clear(this)
                output.text = ""
                refreshStatus()
            }
            .setNegativeButton("취소", null)
            .show()
    }
}
