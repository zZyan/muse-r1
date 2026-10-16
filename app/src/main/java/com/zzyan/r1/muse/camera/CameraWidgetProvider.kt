package com.zzyan.r1.muse.camera

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import com.zzyan.r1.muse.R
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class CameraWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.camera_widget)
            val intent = Intent(context, com.zzyan.r1.muse.camera.CameraLauncherActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val pending = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.camera_button, pending)
            appWidgetManager.updateAppWidget(id, views)
        }
    }
}
