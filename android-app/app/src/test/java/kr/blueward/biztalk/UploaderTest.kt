package kr.blueward.biztalk

import kr.blueward.biztalk.net.ApiProtocol
import kr.blueward.biztalk.parse.ParsedMessage
import kr.blueward.biztalk.sync.Outbox
import kr.blueward.biztalk.sync.UploadLog
import kr.blueward.biztalk.sync.Uploader
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class UploaderTest {

    private class MemoryOutbox(messages: List<ParsedMessage>) : Outbox {
        val pending = LinkedHashMap<String, ParsedMessage>().apply { messages.forEach { put(it.id, it) } }
        val attempts = HashMap<String, Int>()
        override fun nextBatch(limit: Int, exclude: Set<String>) = pending.values.filter { it.id !in exclude }.take(limit)
        override fun markDone(ids: Collection<String>) { ids.forEach { pending.remove(it) } }
        override fun markRetry(ids: Collection<String>) { ids.forEach { attempts[it] = (attempts[it] ?: 0) + 1 } }
        override fun pendingCount() = pending.size
    }

    private class MemoryLog : UploadLog {
        val uploads = ArrayList<Pair<Int, Int>>()
        var error: String? = null
        override fun recordUpload(stored: Int, sent: Int) { uploads += stored to sent }
        override fun recordError(message: String?) { error = message }
        override fun clearError() { error = null }
    }

    private fun msgs(n: Int) = (1..n).map { ParsedMessage("id$it", it.toLong(), "보낸이$it", "", false, "내용$it") }

    private fun ids(body: String): List<String> {
        val arr = JSONObject(body).getJSONArray("messages")
        return (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }
    }

    private fun reply(ok: Boolean = true, done: List<String> = emptyList(), retry: List<String> = emptyList(),
                      stored: Int = 0, error: String? = null, aiError: String? = null) =
        JSONObject().put("ok", ok).put("done", JSONArray(done)).put("retry", JSONArray(retry)).put("stored", stored)
            .apply { error?.let { put("error", it) }; aiError?.let { put("aiError", it) } }.toString()

    private fun uploader(outbox: Outbox, log: UploadLog, send: (String) -> String) =
        Uploader(outbox, log, "tok", "0.1.0", "test", send)

    @Test
    fun `50건씩 나눠 보내고 done을 받은 것만 지운다`() {
        val outbox = MemoryOutbox(msgs(120))
        val log = MemoryLog()
        val sizes = ArrayList<Int>()
        val outcome = uploader(outbox, log) { body ->
            val sent = ids(body)
            sizes += sent.size
            assertEquals("tok", JSONObject(body).getString("token"))
            reply(done = sent, stored = sent.size)
        }.run()
        assertEquals(Uploader.Outcome.FINISHED, outcome)
        assertEquals(listOf(50, 50, 20), sizes)
        assertEquals(0, outbox.pendingCount())
        assertEquals(listOf(50 to 50, 50 to 50, 20 to 20), log.uploads)
    }

    @Test
    fun `네트워크 오류면 아무것도 지우지 않고 나중에 다시 시도한다`() {
        val outbox = MemoryOutbox(msgs(3))
        val log = MemoryLog()
        val outcome = uploader(outbox, log) { throw IOException("timeout") }.run()
        assertEquals(Uploader.Outcome.RETRY_LATER, outcome)
        assertEquals(3, outbox.pendingCount())
        assertTrue(log.error!!.contains("timeout"))
    }

    @Test
    fun `retry로 돌아온 메시지는 남겨 두고 이번 실행에서는 다시 보내지 않는다`() {
        val outbox = MemoryOutbox(msgs(3))
        val log = MemoryLog()
        var calls = 0
        val outcome = uploader(outbox, log) { body ->
            calls++
            val sent = ids(body)
            reply(done = sent.filter { it != "id2" }, retry = sent.filter { it == "id2" }, stored = 2, aiError = "Gemini HTTP 429")
        }.run()
        assertEquals(Uploader.Outcome.RETRY_LATER, outcome)
        assertEquals(1, calls)
        assertEquals(listOf("id2"), outbox.pending.keys.toList())
        assertEquals(1, outbox.attempts["id2"])
        assertTrue(log.error!!.contains("429"))
    }

    @Test
    fun `토큰 오류면 설정을 고칠 때까지 재시도하지 않는다`() {
        val outbox = MemoryOutbox(msgs(2))
        val outcome = uploader(outbox, MemoryLog()) { reply(ok = false, error = "unauthorized") }.run()
        assertEquals(Uploader.Outcome.NEEDS_SETTINGS, outcome)
        assertEquals(2, outbox.pendingCount())
    }

    @Test
    fun `서버가 JSON이 아닌 응답을 주면 다시 시도한다`() {
        val outbox = MemoryOutbox(msgs(1))
        val log = MemoryLog()
        val outcome = uploader(outbox, log) { "<html>Sign in</html>" }.run()
        assertEquals(Uploader.Outcome.RETRY_LATER, outcome)
        assertEquals(1, outbox.pendingCount())
        assertTrue(log.error!!.contains("not_json"))
    }

    @Test
    fun `보낼 것이 없으면 바로 끝난다`() {
        var called = false
        val outcome = uploader(MemoryOutbox(emptyList()), MemoryLog()) { called = true; reply() }.run()
        assertEquals(Uploader.Outcome.FINISHED, outcome)
        assertEquals(false, called)
    }

    @Test
    fun `요청 본문은 서버 규격과 같은 필드를 가진다`() {
        val body = JSONObject(
            ApiProtocol.messagesRequest("t", "1", "dev", listOf(ParsedMessage("a", 5L, "김", "방", true, "본문")))
        )
        assertEquals("messages", body.getString("type"))
        val m = body.getJSONArray("messages").getJSONObject(0)
        assertEquals(setOf("id", "receivedAt", "sender", "room", "isGroup", "text"), m.keys().asSequence().toSet())
        assertEquals(true, m.getBoolean("isGroup"))
        assertEquals("heartbeat", JSONObject(ApiProtocol.heartbeatRequest("t", "1", "dev")).getString("type"))
    }
}
