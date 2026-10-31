package kr.blueward.biztalk.parse

import java.security.MessageDigest

/**
 * 알림에서 꺼낸 값만 담은 순수 데이터. 안드로이드 클래스에 의존하지 않아 JVM 단위 테스트로 검증한다.
 * 진단 앱(diag-app)의 기록 필드와 이름을 맞춰 두어, 실제 기록을 그대로 테스트 데이터로 쓸 수 있다.
 */
data class RawNotification(
    val packageName: String,
    val postTime: Long,
    val whenMs: Long,
    val category: String?,
    val isGroupSummary: Boolean,
    val title: String?,
    val text: String?,
    val bigText: String?,
    val subText: String?,
    val conversationTitle: String?,
    val isGroupConversation: Boolean?,
    val messages: List<RawMessage>,
)

/** MessagingStyle 메시지 하나. senderName이 null이면 보낸 사람 정보가 없는 것(보통 나 자신). */
data class RawMessage(
    val text: String?,
    val timeMs: Long,
    val senderName: String?,
)

/** 서버로 보낼 메시지 하나. */
data class ParsedMessage(
    val id: String,
    val receivedAt: Long,
    val sender: String,
    val room: String,
    val isGroup: Boolean,
    val text: String,
)

/**
 * 카톡 알림을 메시지 목록으로 바꾼다.
 *
 * 카톡은 같은 방에 메시지가 쌓이면 이전 메시지까지 담아 알림을 다시 올린다.
 * 그래서 메시지마다 (방, 보낸 사람, 시각, 내용)으로 같은 ID를 만들어 중복을 걸러 낸다.
 *
 * 규칙은 0단계 진단 기록을 보고 다듬는다. 바꿀 때는 KakaoParserTest에 실제 기록을 추가한다.
 */
object KakaoParser {
    const val KAKAO_PACKAGE = "com.kakao.talk"
    private const val CATEGORY_MESSAGE = "msg"
    private const val ID_VERSION = "v1"

    fun parse(n: RawNotification): List<ParsedMessage> {
        if (n.packageName != KAKAO_PACKAGE) return emptyList()
        if (n.isGroupSummary) return emptyList()
        // 메시지가 아닌 알림(업데이트 안내, 통화 등)은 건너뛴다. 카테고리가 없으면 일단 받는다.
        if (n.messages.isEmpty() && n.category != null && n.category != CATEGORY_MESSAGE) return emptyList()

        return if (n.messages.isNotEmpty()) fromMessagingStyle(n) else fromPlainText(n)
    }

    private fun fromMessagingStyle(n: RawNotification): List<ParsedMessage> {
        val isGroup = n.isGroupConversation == true
        val room = if (isGroup) firstNonBlank(n.conversationTitle, n.subText, n.title) else ""
        val anySender = n.messages.any { !it.senderName.isNullOrBlank() }
        return n.messages.mapNotNull { m ->
            val text = m.text?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val sender = when {
                !m.senderName.isNullOrBlank() -> m.senderName.trim()
                // 1:1 대화에서 카톡이 보낸 사람을 비워 두는 경우에 대비해 제목을 쓴다.
                !isGroup && !anySender -> n.title?.trim()?.takeIf { it.isNotEmpty() }
                else -> null // 보낸 사람이 없는 메시지는 내가 보낸 답장으로 본다.
            } ?: return@mapNotNull null
            val time = if (m.timeMs > 0) m.timeMs else fallbackTime(n)
            message(time, sender, room, isGroup, text)
        }
    }

    private fun fromPlainText(n: RawNotification): List<ParsedMessage> {
        val sender = n.title?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val text = longer(n.bigText, n.text)?.takeIf { it.isNotBlank() } ?: return emptyList()
        val room = n.subText?.trim().orEmpty()
        val isGroup = n.isGroupConversation == true || room.isNotEmpty()
        return listOf(message(fallbackTime(n), sender, if (isGroup) room else "", isGroup, text))
    }

    private fun message(time: Long, sender: String, room: String, isGroup: Boolean, text: String) =
        ParsedMessage(
            id = messageId(time, sender, room, text),
            receivedAt = time,
            sender = sender,
            room = room,
            isGroup = isGroup,
            text = text,
        )

    fun messageId(time: Long, sender: String, room: String, text: String): String {
        val key = listOf(ID_VERSION, room, sender, time.toString(), text).joinToString("\u0001")
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(40)
    }

    private fun fallbackTime(n: RawNotification) = if (n.whenMs > 0) n.whenMs else n.postTime

    private fun firstNonBlank(vararg values: String?) =
        values.firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()

    private fun longer(a: String?, b: String?): String? = when {
        a.isNullOrEmpty() -> b
        b.isNullOrEmpty() -> a
        a.length >= b.length -> a
        else -> b
    }
}
