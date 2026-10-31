package kr.blueward.biztalk

import kr.blueward.biztalk.parse.RawMessage
import kr.blueward.biztalk.parse.RawNotification
import org.json.JSONObject

/**
 * 진단 앱(diag-app)이 남긴 기록 한 줄을 RawNotification으로 바꾼다.
 * 0단계 기록을 받으면 실제 메시지 내용을 가린 뒤 이 형식 그대로 테스트에 붙여 넣는다.
 */
object DiagFixtures {
    fun fromDiagLine(line: String): RawNotification {
        val o = JSONObject(line)
        val sbn = o.getJSONObject("sbn")
        val n = o.getJSONObject("notification")
        val ex = o.getJSONObject("extras")
        val msgs = ex.optJSONArray("android.messages")
        val messages = if (msgs == null) emptyList() else (0 until msgs.length()).mapNotNull { i ->
            val m = msgs.optJSONObject(i) ?: return@mapNotNull null
            RawMessage(
                text = m.optStringOrNull("text"),
                timeMs = m.optLong("time"),
                senderName = m.optJSONObject("sender_person")?.optStringOrNull("name") ?: m.optStringOrNull("sender"),
            )
        }
        return RawNotification(
            packageName = "com.kakao.talk",
            postTime = sbn.optLong("postTime"),
            whenMs = n.optLong("when"),
            category = n.optStringOrNull("category"),
            isGroupSummary = n.optBoolean("isGroupSummary"),
            title = ex.optStringOrNull("android.title"),
            text = ex.optStringOrNull("android.text"),
            bigText = ex.optStringOrNull("android.bigText"),
            subText = ex.optStringOrNull("android.subText"),
            conversationTitle = ex.optStringOrNull("android.conversationTitle"),
            isGroupConversation = if (ex.has("android.isGroupConversation")) ex.optBoolean("android.isGroupConversation") else null,
            messages = messages,
        )
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key)
}
