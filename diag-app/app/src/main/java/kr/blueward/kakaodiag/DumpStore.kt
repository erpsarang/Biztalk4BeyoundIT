package kr.blueward.kakaodiag

import android.content.Context
import org.json.JSONObject
import java.io.File

/** 알림 기록을 앱 내부 저장소의 JSON Lines 파일에 순서대로 쌓는다. */
object DumpStore {
    private const val FILE_NAME = "kakao-dump.jsonl"
    private val lock = Any()

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    fun append(context: Context, entry: JSONObject) {
        synchronized(lock) {
            file(context).appendText(entry.toString() + "\n")
        }
    }

    fun readAll(context: Context): List<JSONObject> = synchronized(lock) {
        val f = file(context)
        if (!f.exists()) return emptyList()
        f.readLines().filter { it.isNotBlank() }.mapNotNull {
            try { JSONObject(it) } catch (e: Exception) { null }
        }
    }

    fun clear(context: Context) {
        synchronized(lock) { file(context).delete() }
    }

    /** 공유용 파일을 만든다. 첫 줄에는 기기와 카톡 버전 정보를 넣는다. */
    fun export(context: Context, header: JSONObject): File = synchronized(lock) {
        val dir = File(context.cacheDir, "export").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "kakao-diag-${System.currentTimeMillis()}.jsonl")
        out.writeText(header.toString() + "\n")
        val f = file(context)
        if (f.exists()) out.appendText(f.readText())
        out
    }
}
