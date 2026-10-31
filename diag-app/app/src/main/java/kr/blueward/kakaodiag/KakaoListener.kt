package kr.blueward.kakaodiag

import android.app.Notification
import android.app.Person
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject

/**
 * 0단계 진단용: 카카오톡 알림을 가공하지 않고 통째로 기록한다.
 * 목적은 송신자, 채팅방, 메시지, 시각이 알림의 어느 필드에 어떤 형태로 들어오는지 확인하는 것이다.
 */
class KakaoListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != KAKAO_PACKAGE) return
        val entry = JSONObject()
            .put("event", "posted")
            .put("capturedAt", System.currentTimeMillis())
            .put("sbn", describeSbn(sbn))
            .put("notification", describeNotification(sbn.notification))
            .put("extras", bundleToJson(sbn.notification.extras, depth = 0))
        DumpStore.append(this, entry)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName != KAKAO_PACKAGE) return
        val entry = JSONObject()
            .put("event", "removed")
            .put("capturedAt", System.currentTimeMillis())
            .put("sbn", describeSbn(sbn))
        DumpStore.append(this, entry)
    }

    private fun describeSbn(sbn: StatusBarNotification) = JSONObject()
        .put("key", sbn.key)
        .put("id", sbn.id)
        .put("tag", sbn.tag)
        .put("postTime", sbn.postTime)
        .put("isGroup", sbn.isGroup)
        .put("groupKey", sbn.groupKey)
        .put("isOngoing", sbn.isOngoing)

    private fun describeNotification(n: Notification): JSONObject {
        val o = JSONObject()
            .put("when", n.`when`)
            .put("category", n.category)
            .put("group", n.group)
            .put("sortKey", n.sortKey)
            .put("flags", n.flags)
            .put("isGroupSummary", n.flags and Notification.FLAG_GROUP_SUMMARY != 0)
            .put("number", n.number)
            .put("tickerText", n.tickerText?.toString())
            .put("channelId", n.channelId)
            .put("shortcutId", n.shortcutId)
        return o
    }

    @Suppress("DEPRECATION")
    private fun bundleToJson(bundle: Bundle?, depth: Int): JSONObject {
        val o = JSONObject()
        if (bundle == null) return o
        for (key in bundle.keySet().sorted()) {
            val v = try {
                bundle.get(key)
            } catch (e: Exception) {
                "<읽기 실패: ${e.javaClass.simpleName}>"
            }
            o.put(key, valueToJson(v, depth))
        }
        return o
    }

    private fun valueToJson(v: Any?, depth: Int): Any? {
        if (depth > 4) return "<깊이 제한>"
        return when (v) {
            null -> JSONObject.NULL
            is String, is Number, is Boolean -> v
            is CharSequence -> v.toString()
            is Bundle -> bundleToJson(v, depth + 1)
            is Person -> personToJson(v)
            is Bitmap -> "<Bitmap ${v.width}x${v.height}>"
            is Icon -> "<Icon>"
            is Array<*> -> JSONArray().also { arr -> v.forEach { arr.put(valueToJson(it, depth + 1)) } }
            is List<*> -> JSONArray().also { arr -> v.forEach { arr.put(valueToJson(it, depth + 1)) } }
            is IntArray -> JSONArray(v.toList())
            is LongArray -> JSONArray(v.toList())
            is BooleanArray -> JSONArray(v.toList())
            is Parcelable -> "<${v.javaClass.name}>"
            else -> "<${v.javaClass.name}> $v"
        }
    }

    private fun personToJson(p: Person) = JSONObject()
        .put("name", p.name?.toString())
        .put("key", p.key)
        .put("uri", p.uri)
        .put("isBot", p.isBot)
        .put("isImportant", p.isImportant)

    companion object {
        const val KAKAO_PACKAGE = "com.kakao.talk"
    }
}

/** 개발 확인용: Build 정보는 첫 화면에서 함께 내보낸다. */
fun deviceInfo(): JSONObject = JSONObject()
    .put("manufacturer", Build.MANUFACTURER)
    .put("model", Build.MODEL)
    .put("sdkInt", Build.VERSION.SDK_INT)
    .put("release", Build.VERSION.RELEASE)
