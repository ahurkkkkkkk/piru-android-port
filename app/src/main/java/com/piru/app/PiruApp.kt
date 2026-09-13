package com.piru.app

import android.app.Application
import com.piru.app.data.SubstanceStore
import com.piru.app.data.SubstanceStoreHolder
import com.piru.app.data.UserDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PiruApp : Application() {
    val userDb by lazy { UserDatabase(this) }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            SubstanceStoreHolder.set(SubstanceStore.get(this@PiruApp))
            com.piru.app.notifications.RoutineScheduler.rescheduleAll(this@PiruApp)
        }
    }
}
