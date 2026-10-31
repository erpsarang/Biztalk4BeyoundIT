package kr.blueward.biztalk

import kr.blueward.biztalk.parse.KakaoParser
import kr.blueward.biztalk.parse.RawMessage
import kr.blueward.biztalk.parse.RawNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 아래 입력은 안드로이드 MessagingStyle 규격을 기준으로 만든 가정이다.
 * 0단계 진단 기록이 오면 실제 기록(내용은 가림)으로 바꾸거나 추가한다.
 */
class KakaoParserTest {

    private fun notification(
        title: String? = "홍길동",
        text: String? = "안녕하세요",
        subText: String? = null,
        conversationTitle: String? = null,
        isGroup: Boolean? = null,
        messages: List<RawMessage> = emptyList(),
        category: String? = "msg",
        isGroupSummary: Boolean = false,
        bigText: String? = null,
        packageName: String = "com.kakao.talk",
    ) = RawNotification(
        packageName = packageName, postTime = 2_000L, whenMs = 1_000L, category = category,
        isGroupSummary = isGroupSummary, title = title, text = text, bigText = bigText, subText = subText,
        conversationTitle = conversationTitle, isGroupConversation = isGroup, messages = messages,
    )

    @Test
    fun `1대1 MessagingStyle 메시지는 보낸 사람과 시각을 메시지에서 가져온다`() {
        val result = KakaoParser.parse(notification(messages = listOf(RawMessage("투입 가능하세요?", 1234L, "김부장"))))
        assertEquals(1, result.size)
        with(result[0]) {
            assertEquals("김부장", sender)
            assertEquals("", room)
            assertEquals(false, isGroup)
            assertEquals(1234L, receivedAt)
            assertEquals("투입 가능하세요?", text)
        }
    }

    @Test
    fun `단톡방은 대화 제목을 채팅방 이름으로 쓴다`() {
        val result = KakaoParser.parse(
            notification(
                title = "ERP 모임", conversationTitle = "ERP 모임", isGroup = true,
                messages = listOf(RawMessage("SAP 컨설턴트 구합니다", 10L, "이대리"), RawMessage("저요", 11L, "박과장")),
            )
        )
        assertEquals(listOf("이대리", "박과장"), result.map { it.sender })
        assertTrue(result.all { it.isGroup && it.room == "ERP 모임" })
    }

    @Test
    fun `알림이 다시 올라와도 같은 메시지는 같은 ID를 가진다`() {
        val first = KakaoParser.parse(notification(messages = listOf(RawMessage("첫 메시지", 10L, "김부장"))))
        val second = KakaoParser.parse(
            notification(messages = listOf(RawMessage("첫 메시지", 10L, "김부장"), RawMessage("두 번째", 20L, "김부장")))
        )
        assertEquals(first[0].id, second[0].id)
        assertNotEquals(second[0].id, second[1].id)
    }

    @Test
    fun `같은 내용이라도 시각이나 방이 다르면 다른 메시지다`() {
        val a = KakaoParser.messageId(10L, "김부장", "", "네")
        assertNotEquals(a, KakaoParser.messageId(11L, "김부장", "", "네"))
        assertNotEquals(a, KakaoParser.messageId(10L, "김부장", "방", "네"))
        assertEquals(40, a.length)
    }

    @Test
    fun `보낸 사람이 없는 메시지는 내 답장으로 보고 건너뛴다`() {
        val result = KakaoParser.parse(
            notification(messages = listOf(RawMessage("문의드립니다", 10L, "김부장"), RawMessage("네 확인할게요", 11L, null)))
        )
        assertEquals(listOf("문의드립니다"), result.map { it.text })
    }

    @Test
    fun `1대1에서 모든 메시지에 보낸 사람이 없으면 알림 제목을 쓴다`() {
        val result = KakaoParser.parse(notification(title = "김부장", messages = listOf(RawMessage("문의드립니다", 10L, null))))
        assertEquals("김부장", result.single().sender)
    }

    @Test
    fun `MessagingStyle이 없으면 제목과 본문을 쓰고, 긴 본문을 우선한다`() {
        val result = KakaoParser.parse(notification(title = "김부장", text = "앞부분…", bigText = "앞부분 전체 내용입니다"))
        with(result.single()) {
            assertEquals("김부장", sender)
            assertEquals("앞부분 전체 내용입니다", text)
            assertEquals(1_000L, receivedAt)
            assertEquals(false, isGroup)
        }
    }

    @Test
    fun `MessagingStyle이 없고 subText가 있으면 단톡방으로 본다`() {
        val result = KakaoParser.parse(notification(title = "이대리", text = "회의 몇 시죠", subText = "프로젝트방"))
        with(result.single()) {
            assertEquals(true, isGroup)
            assertEquals("프로젝트방", room)
        }
    }

    @Test
    fun `카톡이 아니거나 요약 알림이거나 메시지 알림이 아니면 무시한다`() {
        assertTrue(KakaoParser.parse(notification(packageName = "com.other")).isEmpty())
        assertTrue(KakaoParser.parse(notification(isGroupSummary = true)).isEmpty())
        assertTrue(KakaoParser.parse(notification(category = "call")).isEmpty())
        assertTrue(KakaoParser.parse(notification(text = "  ")).isEmpty())
        assertTrue(KakaoParser.parse(notification(messages = listOf(RawMessage("", 1L, "김부장")))).isEmpty())
    }

    @Test
    fun `진단 앱 기록 형식을 그대로 읽을 수 있다`() {
        val line = """
            {"event":"posted","capturedAt":1,"sbn":{"key":"k","postTime":2000,"isGroup":false},
             "notification":{"when":1500,"category":"msg","isGroupSummary":false},
             "extras":{"android.title":"ERP 모임","android.text":"저요","android.conversationTitle":"ERP 모임",
               "android.isGroupConversation":true,
               "android.messages":[{"text":"저요","time":1500,"sender_person":{"name":"박과장"}}]}}
        """.trimIndent()
        val result = KakaoParser.parse(DiagFixtures.fromDiagLine(line))
        with(result.single()) {
            assertEquals("박과장", sender)
            assertEquals("ERP 모임", room)
            assertEquals(1500L, receivedAt)
        }
    }
}
