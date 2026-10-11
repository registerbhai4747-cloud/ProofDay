package com.proofday.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            scheduleAll(ctx)
            return
        }
        val id = intent.getStringExtra("id") ?: return
        val name = intent.getStringExtra("name") ?: "Task"
        val time = intent.getStringExtra("time") ?: return
        scheduleOne(ctx, id, name, time, true)
        if (alreadyLogged(ctx, id)) return
        ring(ctx, id, name)
    }

    private fun alreadyLogged(ctx: Context, id: String): Boolean {
        val s = ctx.getSharedPreferences("proofday", Context.MODE_PRIVATE).getString("tasks", null) ?: return false
        return try {
            val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            JSONObject(s).optJSONObject("logs")?.optJSONObject(day)?.has(id) ?: false
        } catch (e: Exception) {
            false
        }
    }

    private fun ring(ctx: Context, id: String, name: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel("proofday_alarm", "ProofDay alarms", NotificationManager.IMPORTANCE_HIGH)
        ch.setSound(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        ch.enableVibration(true)
        ch.vibrationPattern = longArrayOf(0, 500, 300, 500)
        ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        nm.createNotificationChannel(ch)

        val open = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(
            ctx, id.hashCode(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(ctx, "proofday_alarm")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(name)
            .setContentText("Time to verify. Capture your proof.")
            .setCategory(Notification.CATEGORY_ALARM)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        n.flags = n.flags or Notification.FLAG_INSISTENT
        nm.notify(id.hashCode(), n)
    }

    companion object {
        private fun tasks(ctx: Context): JSONArray {
            val s = ctx.getSharedPreferences("proofday", Context.MODE_PRIVATE).getString("tasks", null)
                ?: return JSONArray()
            return try {
                JSONObject(s).optJSONArray("tasks") ?: JSONArray()
            } catch (e: Exception) {
                JSONArray()
            }
        }

        private fun pending(ctx: Context, id: String, name: String, time: String): PendingIntent {
            val i = Intent(ctx, AlarmReceiver::class.java)
                .putExtra("id", id).putExtra("name", name).putExtra("time", time)
            return PendingIntent.getBroadcast(
                ctx, id.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        fun scheduleOne(ctx: Context, id: String, name: String, time: String, forceTomorrow: Boolean) {
            val p = time.split(":")
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, p[0].toInt())
                set(Calendar.MINUTE, p[1].toInt())
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (forceTomorrow || cal.timeInMillis <= System.currentTimeMillis()) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            val show = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            val am = ctx.getSystemService(AlarmManager::class.java)
            am.setAlarmClock(AlarmManager.AlarmClockInfo(cal.timeInMillis, show), pending(ctx, id, name, time))
        }

        fun scheduleAll(ctx: Context) {
            val a = tasks(ctx)
            for (i in 0 until a.length()) {
                val t = a.getJSONObject(i)
                scheduleOne(ctx, t.getString("id"), t.getString("name"), t.getString("time"), false)
            }
        }

        fun cancelAll(ctx: Context) {
            val am = ctx.getSystemService(AlarmManager::class.java)
            val a = tasks(ctx)
            for (i in 0 until a.length()) {
                val t = a.getJSONObject(i)
                am.cancel(pending(ctx, t.getString("id"), t.getString("name"), t.getString("time")))
            }
        }
    }
}
