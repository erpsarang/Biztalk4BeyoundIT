package kr.blueward.biztalk.sync

import kr.blueward.biztalk.net.ApiProtocol
import kr.blueward.biztalk.parse.ParsedMessage
import java.io.IOException

/** 보관함 동작 중 전송에 필요한 부분. 실제로는 MessageStore, 테스트에서는 메모리 구현을 쓴다. */
interface Outbox {
    fun nextBatch(limit: Int, exclude: Set<String>): List<ParsedMessage>
    fun markDone(ids: Collection<String>)
    fun markRetry(ids: Collection<String>)
    fun pendingCount(): Int
}

/** 전송 결과를 기록할 곳. 실제로는 Settings. */
interface UploadLog {
    fun recordUpload(stored: Int, sent: Int)
    fun recordError(message: String?)
    fun clearError()
}

/**
 * 보관함의 메시지를 50건씩 서버로 보낸다.
 * 서버가 done이라고 한 메시지만 지우므로, 중간에 실패해도 메시지를 잃지 않는다.
 */
class Uploader(
    private val outbox: Outbox,
    private val log: UploadLog,
    private val token: String,
    private val appVersion: String,
    private val device: String,
    private val send: (String) -> String,
) {
    enum class Outcome {
        /** 보낼 것을 다 보냈다 */
        FINISHED,
        /** 네트워크·서버·AI 문제로 남은 것이 있다. 나중에 다시 시도한다 */
        RETRY_LATER,
        /** 토큰이 틀리는 등 설정을 고쳐야 한다. 자동 재시도하지 않는다 */
        NEEDS_SETTINGS,
    }

    fun run(maxBatches: Int = 20): Outcome {
        val retried = HashSet<String>()
        repeat(maxBatches) {
            val batch = outbox.nextBatch(ApiProtocol.MAX_BATCH, retried)
            if (batch.isEmpty()) return if (retried.isEmpty()) Outcome.FINISHED else Outcome.RETRY_LATER

            val body = ApiProtocol.messagesRequest(token, appVersion, device, batch)
            val res = try {
                ApiProtocol.parseResponse(send(body))
            } catch (e: IOException) {
                log.recordError("전송 실패: ${e.message}")
                return Outcome.RETRY_LATER
            }
            if (!res.ok) {
                log.recordError("서버 오류: ${res.error}")
                return if (res.isUnauthorized) Outcome.NEEDS_SETTINGS else Outcome.RETRY_LATER
            }

            // 보낸 묶음에 없던 ID가 와도 무시한다.
            val sentIds = batch.mapTo(HashSet()) { it.id }
            outbox.markDone(res.done.filter { it in sentIds })
            val retry = res.retry.filter { it in sentIds }
            outbox.markRetry(retry)
            retried += retry
            // 서버가 아무 답도 안 한 메시지도 이번 실행에서는 다시 보내지 않는다.
            retried += sentIds - res.done.toSet() - res.retry.toSet()

            log.recordUpload(res.stored, batch.size)
            if (res.aiError != null) log.recordError("AI 판단 실패(나중에 다시 보냄): ${res.aiError}") else log.clearError()
        }
        return if (outbox.pendingCount() > 0) Outcome.RETRY_LATER else Outcome.FINISHED
    }
}
