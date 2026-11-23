package com.exp.rhythm

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

class RhythmVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.exp.rhythm.START"
        const val ACTION_STOP = "com.exp.rhythm.STOP"
        const val EXTRA_TARGET_PKG = "target_pkg"

        val engine = RhythmEngine()

        @Volatile
        var isRunning = false
            private set
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var workerThread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val targetPkg = intent.getStringExtra(EXTRA_TARGET_PKG)
                startVpn(targetPkg)
            }
        }
        return START_STICKY
    }

    private fun startVpn(targetPackage: String?) {
        if (isRunning) return

        val builder = Builder()
            .setSession("RhythmTestbed")
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .setMtu(1500)

        // Opsional: isolasi VPN hanya ke 1 aplikasi target (misal: "com.android.chrome")
        if (!targetPackage.isNullOrBlank()) {
            try {
                builder.addAllowedApplication(targetPackage)
            } catch (_: Exception) {
                // Abaikan jika package tidak ditemukan
            }
        }

        vpnInterface = builder.establish() ?: return
        engine.resetMetrics()
        isRunning = true

        workerThread = Thread({
            runPacketLoop()
        }, "RhythmVpnLoop").apply { start() }
    }

    private fun runPacketLoop() {
        val fd = vpnInterface?.fileDescriptor ?: return
        val input = FileInputStream(fd)
        val output = FileOutputStream(fd)
        val packet = ByteBuffer.allocate(32767)

        try {
            while (isRunning && !Thread.interrupted()) {
                val length = input.read(packet.array())
                if (length > 0) {
                    // Hitung pacing dari RhythmEngine
                    val delayMs = engine.onPacket(length)
                    if (delayMs > 0L) {
                        Thread.sleep(delayMs)
                    }

                    // TODO: Teruskan paket IP mentah ke soket keluar yang sudah di-protect()
                    // (misal via tun2socks / userspace TCP-UDP forwarder) lalu tulis respons ke `output`.

                    packet.clear()
                } else {
                    Thread.sleep(10)
                }
            }
        } catch (_: InterruptedException) {
            // Normal saat service dihentikan
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopVpn() {
        isRunning = false
        workerThread?.interrupt()
        workerThread = null
        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }
        vpnInterface = null
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
