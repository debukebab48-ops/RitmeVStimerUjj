package com.exp.rhythm

import kotlin.math.max

class RhythmEngine {

    enum class Mode {
        PASSTHROUGH,      // Baseline: tanpa jeda
        FIXED_PACE,       // Timer tetap: tahan tiap siklus
        ADAPTIVE_RHYTHM   // Ritme adaptif berdasarkan laju & indikasi stall
    }

    @Volatile
    var currentMode: Mode = Mode.PASSTHROUGH

    // Parameter eksperimen (bisa diubah live dari UI)
    @Volatile var holdDurationMs: Long = 150L
    @Volatile var openWindowMs: Long = 850L
    @Volatile var stallThresholdMs: Long = 1200L

    private var windowStartTime = System.currentTimeMillis()
    private var lastPacketTime = System.currentTimeMillis()
    private var bytesInCurrentSec = 0L
    private var lastStatTime = System.currentTimeMillis()

    @Volatile var currentKbps: Double = 0.0
        private set

    @Volatile var stallCount: Int = 0
        private set

    /**
     * Dipanggil setiap ada paket masuk/keluar di loop VPN.
     * Mengembalikan durasi delay (ms) sebelum paket diteruskan.
     */
    fun onPacket(byteLength: Int): Long {
        val now = System.currentTimeMillis()

        // Deteksi stall: jeda antar paket melebihi ambang batas
        if (now - lastPacketTime > stallThresholdMs && byteLength > 0) {
            stallCount++
        }
        lastPacketTime = now

        // Update kalkulasi KB/s setiap 1 detik
        bytesInCurrentSec += byteLength
        val elapsedStat = now - lastStatTime
        if (elapsedStat >= 1000L) {
            currentKbps = (bytesInCurrentSec / 1024.0) / (elapsedStat / 1000.0)
            bytesInCurrentSec = 0L
            lastStatTime = now
        }

        return when (currentMode) {
            Mode.PASSTHROUGH -> 0L

            Mode.FIXED_PACE -> {
                val cycle = openWindowMs + holdDurationMs
                val pos = (now - windowStartTime) % max(1L, cycle)
                if (pos > openWindowMs) (cycle - pos) else 0L
            }

            Mode.ADAPTIVE_RHYTHM -> {
                // Jika speed sedang drop di bawah ~25 KB/s, kurangi intervensi jeda
                // agar koneksi tidak makin tercekik timeout.
                if (currentKbps in 0.1..25.0) {
                    0L
                } else {
                    val cycle = openWindowMs + holdDurationMs
                    val pos = (now - windowStartTime) % max(1L, cycle)
                    if (pos > openWindowMs) (cycle - pos) else 0L
                }
            }
        }
    }

    fun resetMetrics() {
        stallCount = 0
        bytesInCurrentSec = 0L
        currentKbps = 0.0
        windowStartTime = System.currentTimeMillis()
        lastPacketTime = System.currentTimeMillis()
        lastStatTime = System.currentTimeMillis()
    }
}
