package com.exp.rhythm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class RhythmVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.exp.rhythm.START"
        const val ACTION_STOP = "com.exp.rhythm.STOP"
        const val EXTRA_TARGET_PACKAGE = "target_package"
        const val EXTRA_MODE = "rhythm_mode"

        val isRunning = AtomicBoolean(false)
        val engine = RhythmEngine()
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var workerThread: Thread? = null
    private var socksServer: LocalSocks5Server? = null
        
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val targetPkg = intent.getStringExtra(EXTRA_TARGET_PACKAGE)?.trim().orEmpty()
                val modeName = intent.getStringExtra(EXTRA_MODE) ?: RhythmEngine.Mode.PASSTHROUGH.name
                engine.mode = runCatching { RhythmEngine.Mode.valueOf(modeName) }
                    .getOrDefault(RhythmEngine.Mode.PASSTHROUGH)

                if (!isRunning.get()) {
                    startVpn(targetPkg)
                }
            }
        }
        return START_STICKY
    }

    private fun startVpn(targetPackage: String) {
        startForeground(101, createNotification())
        engine.reset()

        val builder = Builder()
            .setSession("RhythmTestbed")
            .setMtu(1500)
            .addAddress("10.10.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")

        // Isolasi pengujian hanya ke 1 aplikasi target jika diisi
        if (targetPackage.isNotEmpty()) {
            runCatching { builder.addAllowedApplication(targetPackage) }
        } else {
            // Kecualikan diri sendiri agar tidak loop
            runCatching { builder.addDisallowedApplication(packageName) }
        }

            vpnInterface = builder.establish() ?: return
        socksServer = LocalSocks5Server(this, engine, "127.0.0.1", 1080).apply {
            start()
        }
        isRunning.set(true)

        workerThread = Thread({
            runPacketLoop()
        }, "RhythmVpnWorker").apply { start() }

    }
    private fun runPacketLoop() {
        val pfd = vpnInterface ?: return

        try {
            // TODO: Hubungkan pfd.detachFd() / fileDescriptor ke native user-space stack (tun2socks / hev-socks5-tunnel)
            // dengan target proxy 127.0.0.1:1080 agar paket IPv4/IPv6 TCP & UDP diteruskan ke LocalSocks5Server.
            while (isRunning.get() && !Thread.currentThread().isInterrupted) {
                Thread.sleep(500L)
            }
        } catch (_: InterruptedException) {
            // Normal shutdown
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            stopVpn()
        }
    }

    private fun stopVpn() {
        isRunning.set(false)
        socksServer?.stop()
        socksServer = null
        workerThread?.interrupt()
        workerThread = null
        runCatching { vpnInterface?.close() }
        vpnInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun createNotification(): Notification {
        val channelId = "rhythm_vpn_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Rhythm Experiment VPN",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Rhythm Testbed Aktif")
            .setContentText("Mengamati aliran trafik eksperimen...")
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setOngoing(true)
            .build()
    }
}
