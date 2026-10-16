package com.zzyan.r1.muse.core

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.provider.Settings
import android.util.Log

class AutoRotate(private val context: Context): SensorEventListener {
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accel = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var last = -1
    fun start() {
        accel?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }
    fun stop() { sensors.unregisterListener(this) }
    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val wanted = when {
            x > 5f && kotlin.math.abs(y) < 5f -> 1
            x < -5f && kotlin.math.abs(y) < 5f -> 3
            y > 5f -> 2
            else -> 0
        }
        if (wanted != last) {
            last = wanted
            if (Settings.System.canWrite(context)) {
                Settings.System.putInt(context.contentResolver, Settings.System.USER_ROTATION, wanted)
                Settings.System.putInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0)
            }
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    companion object {
        fun canWrite(context: Context): Boolean = Settings.System.canWrite(context)
    }
}
