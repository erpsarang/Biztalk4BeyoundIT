package kr.blueward.biztalk.net

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Apps Script 웹 앱 호출. 웹 앱은 POST를 받은 뒤 302로 결과 주소를 알려 주고,
 * 그 주소를 GET으로 읽어야 결과가 나온다. 자동 리디렉션은 기기마다 동작이 달라 직접 처리한다.
 */
class ApiClient(private val endpoint: String) {

    @Throws(IOException::class)
    fun post(body: String): String {
        var url = URL(endpoint)
        var method = "POST"
        var payload: ByteArray? = body.toByteArray(Charsets.UTF_8)
        repeat(MAX_REDIRECTS) {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 30_000
                readTimeout = 90_000 // 서버가 Gemini 판단까지 마친 뒤 응답한다
                requestMethod = method
                setRequestProperty("Accept", "application/json")
                if (payload != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
            try {
                payload?.let { bytes -> conn.outputStream.use { it.write(bytes) } }
                val code = conn.responseCode
                if (code in REDIRECT_CODES) {
                    val location = conn.getHeaderField("Location") ?: throw IOException("리디렉션 주소 없음 ($code)")
                    url = URL(url, location)
                    if (code != 307 && code != 308) {
                        method = "GET"
                        payload = null
                    }
                    return@repeat
                }
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw IOException("HTTP $code: ${text.take(200)}")
                return text
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("리디렉션이 너무 많습니다")
    }

    companion object {
        private const val MAX_REDIRECTS = 5
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
