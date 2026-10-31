package kr.blueward.biztalk.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kr.blueward.biztalk.parse.ParsedMessage
import kr.blueward.biztalk.sync.Outbox

/**
 * 폰 안의 보관함.
 * - pending: 서버가 "처리 완료(done)"라고 답할 때까지 보관하는 메시지
 * - seen: 이미 받은 메시지 ID. 카톡이 이전 메시지를 다시 담아 알림을 올려도 또 쌓이지 않게 한다
 */
class MessageStore private constructor(context: Context) :
    SQLiteOpenHelper(context, "biztalk.db", null, 1), Outbox {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE pending (
                id TEXT PRIMARY KEY,
                received_at INTEGER NOT NULL,
                sender TEXT NOT NULL,
                room TEXT NOT NULL,
                is_group INTEGER NOT NULL,
                text TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                attempts INTEGER NOT NULL DEFAULT 0
            )"""
        )
        db.execSQL("CREATE INDEX pending_received ON pending(received_at)")
        db.execSQL("CREATE TABLE seen (id TEXT PRIMARY KEY, seen_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX seen_at_idx ON seen(seen_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** 처음 보는 메시지만 보관함에 넣는다. @return 새로 넣은 개수 */
    @Synchronized
    fun insertIfNew(messages: List<ParsedMessage>, now: Long = System.currentTimeMillis()): Int {
        if (messages.isEmpty()) return 0
        val db = writableDatabase
        var inserted = 0
        db.beginTransaction()
        try {
            for (m in messages) {
                val seen = ContentValues().apply {
                    put("id", m.id)
                    put("seen_at", now)
                }
                if (db.insertWithOnConflict("seen", null, seen, SQLiteDatabase.CONFLICT_IGNORE) == -1L) continue
                val row = ContentValues().apply {
                    put("id", m.id)
                    put("received_at", m.receivedAt)
                    put("sender", m.sender)
                    put("room", m.room)
                    put("is_group", if (m.isGroup) 1 else 0)
                    put("text", m.text)
                    put("created_at", now)
                }
                db.insertWithOnConflict("pending", null, row, SQLiteDatabase.CONFLICT_IGNORE)
                inserted++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return inserted
    }

    /** 오래된 메시지부터 꺼낸다. exclude에 든 ID는 건너뛴다(이번 전송에서 이미 재시도로 돌아온 것). */
    @Synchronized
    override fun nextBatch(limit: Int, exclude: Set<String>): List<ParsedMessage> {
        val out = ArrayList<ParsedMessage>(limit)
        readableDatabase.rawQuery(
            "SELECT id, received_at, sender, room, is_group, text FROM pending ORDER BY received_at LIMIT ?",
            arrayOf((limit + exclude.size).toString())
        ).use { c ->
            while (c.moveToNext() && out.size < limit) {
                val id = c.getString(0)
                if (id in exclude) continue
                out += ParsedMessage(id, c.getLong(1), c.getString(2), c.getString(3), c.getInt(4) == 1, c.getString(5))
            }
        }
        return out
    }

    @Synchronized
    override fun markDone(ids: Collection<String>) = inChunks(ids) { db, chunk ->
        db.delete("pending", "id IN (${placeholders(chunk.size)})", chunk.toTypedArray())
    }

    @Synchronized
    override fun markRetry(ids: Collection<String>) = inChunks(ids) { db, chunk ->
        db.execSQL("UPDATE pending SET attempts = attempts + 1 WHERE id IN (${placeholders(chunk.size)})", chunk.toTypedArray())
    }

    @Synchronized
    override fun pendingCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM pending", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** 일정 기간이 지난 seen 기록을 지운다. 보관함(pending)은 건드리지 않는다. */
    @Synchronized
    fun pruneSeen(olderThan: Long): Int =
        writableDatabase.delete("seen", "seen_at < ? AND id NOT IN (SELECT id FROM pending)", arrayOf(olderThan.toString()))

    private fun inChunks(ids: Collection<String>, block: (SQLiteDatabase, List<String>) -> Unit) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.chunked(500).forEach { block(db, it) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun placeholders(n: Int) = List(n) { "?" }.joinToString(",")

    companion object {
        @Volatile private var instance: MessageStore? = null

        fun get(context: Context): MessageStore =
            instance ?: synchronized(this) {
                instance ?: MessageStore(context.applicationContext).also { instance = it }
            }
    }
}
