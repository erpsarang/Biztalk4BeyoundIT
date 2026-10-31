package kr.blueward.biztalk.capture

import android.app.Notification
import android.app.Person
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kr.blueward.biztalk.data.MessageStore
import kr.blueward.biztalk.data.Settings
import kr.blueward.biztalk.parse.KakaoParser
import kr.blueward.biztalk.parse.RawMessage
import kr.blueward.biztalk.parse.RawNotification
import kr.blueward.biztalk.sync.Sync
import java.util.concurrent.Executors

/**
 * 카톡 알림을 받아 메시지로 바꾸고 보관함에 넣는다.
 * 알림 해석 규칙은 KakaoParser에 있고, 여기서는 알림에서 값을 꺼내는 일만 한다.
 */
class KakaoListenerService : NotificationListenerService() {

    private val worker = Executors.newSingleThreadExecutor()

    override fun onListenerConnected() {
        Settings(this).recordListenerConnected(true)
    }

    override fun onListenerDisconnected() {
        Settings(this).recordListenerConnected(false)
        // 시스템이 연결을 끊으면 다시 붙여 달라고 요청한다.
        requestRebind(ComponentName(this, KakaoListenerService::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != KakaoParser.KAKAO_PACKAGE) return
        val raw = try {
            extract(sbn)
        } catch (e: Exception) {
            Log.w(TAG, "알림 읽기 실패", e)
            return
        }
        worker.execute {
            try {
                val parsed = KakaoParser.parse(raw)
                val added = MessageStore.get(this).insertIfNew(parsed)
                if (added > 0) {
                    Settings(this).recordCapture(added)
                    Sync.scheduleUpload(this)
                }
            } catch (e: Exception) {
                Log.w(TAG, "메시지 저장 실패", e)
                Settings(this).recordError("메시지 저장 실패: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "KakaoListener"

        fun extract(sbn: StatusBarNotification): RawNotification {
            val n = sbn.notification
            val e = n.extras
            return RawNotification(
                packageName = sbn.packageName,
                postTime = sbn.postTime,
                whenMs = n.`when`,
                category = n.category,
                isGroupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
                title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
                text = e.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
                bigText = e.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
                subText = e.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
                conversationTitle = e.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString(),
                isGroupConversation = if (e.containsKey(Notification.EXTRA_IS_GROUP_CONVERSATION)) {
                    e.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION)
                } else {
                    null
                },
                messages = messages(e),
            )
        }

        @Suppress("DEPRECATION")
        private fun messages(extras: Bundle): List<RawMessage> {
            val arr: Array<Parcelable> = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return emptyList()
            return arr.mapNotNull { p ->
                val b = p as? Bundle ?: return@mapNotNull null
                val person: Person? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    b.getParcelable("sender_person", Person::class.java)
                } else {
                    b.getParcelable("sender_person")
                }
                RawMessage(
                    text = b.getCharSequence("text")?.toString(),
                    timeMs = b.getLong("time"),
                    senderName = person?.name?.toString() ?: b.getCharSequence("sender")?.toString(),
                )
            }
        }
    }
}
