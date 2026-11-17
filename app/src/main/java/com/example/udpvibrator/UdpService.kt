package com.example.udpvibrator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

data class UdpState(
    val running: Boolean = false,
    val port: Int = 0,
    val error: String? = null,
    val received: Int = 0,
    val lastFrom: String? = null,
)

class UdpService : Service() {

    companion object {
        val state = MutableStateFlow(UdpState())
        private const val CHANNEL_ID = "udp_listener"
        private const val NOTIF_ID = 1

        fun start(ctx: Context) =
            ContextCompat.startForegroundService(ctx, Intent(ctx, UdpService::class.java))

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, UdpService::class.java))
        }
    }

    @Volatile private var running = false
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val port = SettingsStore(this).load().port
        startInForeground(port)
        if (!running) startListening(port)
        return START_STICKY
    }

    private fun startInForeground(port: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "UDP listener", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notif: Notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("UDP Vibrator")
            .setContentText("Listening on UDP port $port")
            .setContentIntent(open)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    @Suppress("DEPRECATION")
    private fun startListening(port: Int) {
        running = true
        state.value = UdpState(running = true, port = port)

        // Keep CPU and Wi-Fi awake so packets arrive with the screen off.
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "udpvibrator:cpu")
            .apply { acquire() }
        wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "udpvibrator:wifi")
            .apply { acquire() }

        thread = Thread({ listen(port) }, "udp-listener").also { it.start() }
    }

    private fun listen(port: Int) {
        val store = SettingsStore(this)
        try {
            val s = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
            }
            socket = s

            val buf = ByteArray(2048)
            val packet = DatagramPacket(buf, buf.size)
            while (running) {
                packet.length = buf.size
                s.receive(packet)                       // blocks until a packet arrives
                val from = packet.address.hostAddress ?: continue
                val cfg = store.load()                  // settings apply live
                if (cfg.allowedIp.isNotBlank() && cfg.allowedIp != from) continue

                val msg = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                Haptics.vibrate(this, Protocol.apply(cfg, msg))
                state.update { it.copy(received = it.received + 1, lastFrom = from) }
            }
        } catch (e: Exception) {
            if (running) {
                state.value = UdpState(error = e.message ?: "Socket error")
                stopSelf()
            }
        } finally {
            socket?.close()
        }
    }

    override fun onDestroy() {
        running = false
        socket?.close()                                 // unblocks receive()
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        state.update { it.copy(running = false) }
        super.onDestroy()
    }
}
