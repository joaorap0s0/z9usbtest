package com.z9tether.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.z9tether.MainActivity
import com.z9tether.ptp.PtpEngine
import com.z9tether.usb.UsbCameraManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class Z9UsbService : Service() {

    private val channelId = "Z9UsbServiceChannel"
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Nikon Z9 Connected")
            .setContentText("Listening for incoming photos over USB-C...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()

        startForeground(1, notification)

        val device = intent?.getParcelableExtra<UsbDevice>("device")
        startTetherLoop(device)

        return START_STICKY
    }

    private fun startTetherLoop(providedDevice: UsbDevice?) {
        serviceScope.launch {
            val usbCameraManager = UsbCameraManager(applicationContext)
            val device = providedDevice ?: usbCameraManager.findNikonDevice()

            if (device == null) {
                MainActivity.statusMessage = "Nikon Z9 device not found"
                MainActivity.isConnected = false
                return@launch
            }

            val connData = usbCameraManager.openCameraConnection(device)
            if (connData == null) {
                MainActivity.statusMessage = "Failed to open USB connection"
                MainActivity.isConnected = false
                return@launch
            }

            val engine = PtpEngine(
                connData.connection,
                connData.endpointIn,
                connData.endpointOut,
                connData.endpointEvent
            )

            if (!engine.openSession()) {
                MainActivity.statusMessage = "Failed to establish PTP session"
                MainActivity.isConnected = false
                return@launch
            }

            MainActivity.isConnected = true
            MainActivity.statusMessage = "Nikon Z9 Live Tether Ready"

            var lastHandle: Int? = engine.getLatestObjectHandle()

            while (isActive) {
                delay(1000)
                val currentHandle = engine.getLatestObjectHandle()
                if (currentHandle != null && currentHandle != lastHandle) {
                    lastHandle = currentHandle
                    MainActivity.statusMessage = "New photo detected! Transferring..."
                    val bitmap = engine.fetchBitmap(currentHandle)
                    if (bitmap != null) {
                        MainActivity.currentBitmap = bitmap
                        MainActivity.statusMessage = "Photo transferred successfully"
                    } else {
                        MainActivity.statusMessage = "Transfer failed for handle: $currentHandle"
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        MainActivity.isConnected = false
        MainActivity.statusMessage = "Disconnected"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                channelId,
                "Z9 Tethering Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }
}
