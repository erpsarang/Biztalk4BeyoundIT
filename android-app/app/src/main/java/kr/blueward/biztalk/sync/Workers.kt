package kr.blueward.biztalk.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import kr.blueward.biztalk.data.MessageStore
import kr.blueward.biztalk.data.Settings
import kr.blueward.biztalk.net.ApiClient
import kr.blueward.biztalk.net.ApiProtocol
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 보관함의 메시지를 서버로 보낸다. 인터넷이 연결되어 있을 때만 실행된다. */
class UploadWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val settings = Settings(applicationContext)
        if (!settings.isConfigured) return Result.success()
        val client = ApiClient(settings.endpoint)
        val outcome = Uploader(
            outbox = MessageStore.get(applicationContext),
            log = settings,
            token = settings.token,
            appVersion = settings.appVersion,
            device = settings.device,
            send = client::post,
        ).run()
        return when (outcome) {
            Uploader.Outcome.FINISHED, Uploader.Outcome.NEEDS_SETTINGS -> Result.success()
            Uploader.Outcome.RETRY_LATER -> Result.retry()
        }
    }
}

/**
 * 한 시간마다 서버에 생존 신호를 보낸다. 서버는 신호가 끊기면 경고 메일을 보낸다.
 * 혹시 남아 있는 메시지가 있으면 전송도 다시 예약한다.
 */
class HeartbeatWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val settings = Settings(applicationContext)
        val store = MessageStore.get(applicationContext)
        store.pruneSeen(System.currentTimeMillis() - SEEN_KEEP_MS)
        if (!settings.isConfigured) return Result.success()
        if (store.pendingCount() > 0) Sync.scheduleUpload(applicationContext, delayMinutes = 0)
        return try {
            val res = ApiProtocol.parseResponse(
                ApiClient(settings.endpoint).post(
                    ApiProtocol.heartbeatRequest(settings.token, settings.appVersion, settings.device)
                )
            )
            if (res.ok) {
                settings.recordHeartbeat()
                Result.success()
            } else {
                settings.recordError("생존 신호 실패: ${res.error}")
                if (res.isUnauthorized) Result.success() else Result.retry()
            }
        } catch (e: IOException) {
            settings.recordError("생존 신호 실패: ${e.message}")
            Result.retry()
        }
    }

    private companion object {
        const val SEEN_KEEP_MS = 7L * 24 * 3600 * 1000
    }
}

object Sync {
    private const val UPLOAD_WORK = "upload"
    private const val HEARTBEAT_WORK = "heartbeat"

    /** 새 메시지가 들어올 때마다 몇 분 뒤 전송을 예약한다. 이미 예약되어 있으면 그대로 두어 묶어서 보낸다. */
    const val BATCH_DELAY_MINUTES = 3L

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun scheduleUpload(context: Context, delayMinutes: Long = BATCH_DELAY_MINUTES) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(network)
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UPLOAD_WORK, ExistingWorkPolicy.KEEP, request)
    }

    /** "지금 보내기" 버튼용. 기다리던 예약을 지우고 바로 보낸다. */
    fun uploadNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UPLOAD_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    fun ensureHeartbeat(context: Context) {
        val request = PeriodicWorkRequestBuilder<HeartbeatWorker>(1, TimeUnit.HOURS)
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(HEARTBEAT_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
