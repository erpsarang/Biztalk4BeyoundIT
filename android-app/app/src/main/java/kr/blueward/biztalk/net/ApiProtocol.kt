package kr.blueward.biztalk.net

import kr.blueward.biztalk.parse.ParsedMessage
import org.json.JSONArray
import org.json.JSONObject

/** Apps Script 서버와 주고받는 JSON 규격. apps-script/README.md의 "앱 연동 규격"과 같아야 한다. */
object ApiProtocol {
    const val MAX_BATCH = 50

    data class Response(
        val ok: Boolean,
        val done: List<String>,
        val retry: List<String>,
        val stored: Int,
        val error: String?,
        val aiError: String?,
    ) {
        val isUnauthorized get() = error == "unauthorized"
    }

    fun heartbeatRequest(token: String, appVersion: String, device: String): String =
        base(token, "heartbeat", appVersion, device).toString()

    fun messagesRequest(token: String, appVersion: String, device: String, messages: List<ParsedMessage>): String {
        require(messages.size <= MAX_BATCH) { "한 번에 최대 $MAX_BATCH 건" }
        val arr = JSONArray()
        messages.forEach { m ->
            arr.put(
                JSONObject()
                    .put("id", m.id)
                    .put("receivedAt", m.receivedAt)
                    .put("sender", m.sender)
                    .put("room", m.room)
                    .put("isGroup", m.isGroup)
                    .put("text", m.text)
            )
        }
        return base(token, "messages", appVersion, device).put("messages", arr).toString()
    }

    /** 서버 응답을 읽는다. JSON이 아니면(로그인 페이지 등) ok=false로 돌려준다. */
    fun parseResponse(body: String): Response {
        val o = try {
            JSONObject(body)
        } catch (e: Exception) {
            val head = body.trim().take(80).replace(Regex("\\s+"), " ")
            return Response(false, emptyList(), emptyList(), 0, "not_json: $head", null)
        }
        return Response(
            ok = o.optBoolean("ok", false),
            done = strings(o.optJSONArray("done")),
            retry = strings(o.optJSONArray("retry")),
            stored = o.optInt("stored", 0),
            error = o.optString("error").takeIf { it.isNotEmpty() },
            aiError = o.optString("aiError").takeIf { it.isNotEmpty() },
        )
    }

    private fun base(token: String, type: String, appVersion: String, device: String) = JSONObject()
        .put("token", token)
        .put("type", type)
        .put("appVersion", appVersion)
        .put("device", device)

    private fun strings(arr: JSONArray?): List<String> =
        if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }
}
