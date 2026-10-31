package kr.blueward.biztalk

import android.app.Application
import kr.blueward.biztalk.sync.Sync

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Sync.ensureHeartbeat(this)
    }
}
