package com.piru.app.notifications

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.piru.app.MainActivity
import com.piru.app.R
import com.piru.app.data.DailyDoseItem
import com.piru.app.data.UserDatabase
import java.util.Calendar

/**
 * Med-reminder scheduling — port of NotificationPreferencesStore + the routine
 * reminder layer onto AlarmManager. One exact alarm per (item, today's remaining
 * reminder time); rescheduled after each fire / boot / med edit.
 */
object RoutineScheduler {
    private const val CHANNEL = "piru_meds"

    fun rescheduleAll(context: Context) {
        val app = context.applicationContext
        ensureChannel(app)
        cancelAll(app)
        val db = UserDatabase(app)
        val items = db.allDailyItems()
        val now = Calendar.getInstance()
        var id = 100
        for (item in items) {
            if (item.isAsNeeded) continue
            for (minutes in item.reminderMinutes) {
                val cal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, minutes / 60)
                    set(Calendar.MINUTE, minutes % 60)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                if (cal.before(now)) cal.add(Calendar.DAY_OF_YEAR, 1)
                schedule(app, id, item, cal.timeInMillis)
                id++
            }
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = context.getSystemService<NotificationManager>()!!
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Medication reminders", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    private fun cancelAll(context: Context) {
        val am = context.getSystemService<AlarmManager>() ?: return
        for (id in 100 until 500) {
            am.cancel(pendingIntent(context, id, null, 0))
        }
    }

    private fun schedule(context: Context, id: Int, item: DailyDoseItem, atMillis: Long) {
        val am = context.getSystemService<AlarmManager>() ?: return
        val pi = pendingIntent(context, id, item, atMillis)
        try {
            if (Build.VERSION.SDK_INT >= 31 && !context.getSystemService<AlarmManager>()!!.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        }
    }

    private fun pendingIntent(context: Context, id: Int, item: DailyDoseItem?, atMillis: Long): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra("id", id)
            item?.let { putExtra("substance", it.substance); putExtra("amount", it.amount); putExtra("unit", it.unit) }
        }
        return PendingIntent.getBroadcast(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("id", -1)
        val substance = intent.getStringExtra("substance")
        if (substance == null) return
        val amount = intent.getDoubleExtra("amount", 0.0)
        val unit = intent.getStringExtra("unit") ?: "mg"
        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, "piru_meds")
            .setSmallIcon(R.drawable.ic_stat_piru)
            .setContentTitle("$substance · $amount $unit")
            .setContentText("Time for your med")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id, n)
        // reschedule the same item for tomorrow
        RoutineScheduler.rescheduleAll(context)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) RoutineScheduler.rescheduleAll(context)
    }
}
