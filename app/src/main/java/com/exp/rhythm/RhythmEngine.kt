package com.exp.rhythm

import kotlin.math.max

/**
 * Mesin logika murni untuk mengatur ritme pembacaan/pelepasan paket
 * dan mencatat metrik throughput serta stall tanpa bergantung ke Android UI.
 */
class RhythmEngine {

    enum class Mode {
        PASSTHROUGH,      // Baseline: mengalir apa adanya
        ADAPTIVE_RHYTHM   // Ritme berbasis kondisi laju & stall
    }

    enum class Phase {
        PASS_OPEN,        // Mengalir penuh (saat burst / butuh cepat)
        HOLD_BRIEF,       // Tahan singkat (memberi ruang bucket/antrean)
        STEADY_PACE       // Aliran terkendali agar tidak bentrok timeout
    }

    data class MetricsSnapshot(
        val currentKbps: Double,
        val stallCount: Int,
        val activePhase: Phase,
        val totalBytes: Long
    )

    @Volatile
    var mode: Mode = Mode.PASSTHROUGH

    // Parameter eksperimen (bisa diubah cepat saat tuning)
    var holdDurationMs: Long = 350L
    var openWindowMs: Long = 1200L
    var stallThresholdMs: Long = 1500L

    private var currentPhase: Phase = Phase.PASS_OPEN
    private var phaseStartedAt: Long = System.currentTimeMillis()
    private var lastPacketAt: Long = System.currentTimeMillis()

    private var windowStartAt: Long = System.currentTimeMillis()
    private var bytesInWindow: Long = 0L
    private var totalBytes: Long = 0L
    private var lastCalculatedKbps: Double = 0.0
    private var stallCount: Int = 0

    fun reset() {
        val now = System.currentTimeMillis()
        currentPhase = Phase.PASS_OPEN
        phaseStartedAt = now
        lastPacketAt = now
        windowStartAt = now
        bytesInWindow = 0L
        totalBytes = 0L
        lastCalculatedKbps = 0.0
        stallCount = 0
    }

    /**
     * Dipanggil setiap kali ada paket keluar/masuk untuk menentukan
     * berapa milidetik loop perlu menahan aliran sebelum memproses berikutnya.
     */
    fun onTrafficCycle(packetBytes: Int): Long {
        val now = System.currentTimeMillis()

        // Deteksi stall: jika jeda antar paket melebihi ambang batas
        if (packetBytes > 0) {
            if (now - lastPacketAt > stallThresholdMs) {
                stallCount++
                // Saat baru pulih dari stall, langsung buka penuh untuk tangkap burst
                currentPhase = Phase.PASS_OPEN
                phaseStartedAt = now
            }
            lastPacketAt = now
            bytesInWindow += packetBytes
            totalBytes += packetBytes
        }

        updateRateWindow(now)

        if (mode == Mode.PASSTHROUGH) {
            currentPhase = Phase.PASS_OPEN
            return 0L
        }

        // Logika transisi ritme adaptif berbasis kondisi
        val elapsedInPhase = now - phaseStartedAt
        return when (currentPhase) {
            Phase.PASS_OPEN -> {
                if (elapsedInPhase >= openWindowMs) {
                    // Jika speed sudah turun ke steady cap rendah (< 25 KB/s), sisipkan jeda singkat
                    if (lastCalculatedKbps in 0.1..25.0) {
                        currentPhase = Phase.HOLD_BRIEF
                        phaseStartedAt = now
                        holdDurationMs
                    } else {
                        phaseStartedAt = now
                        0L
                    }
                } else {
                    0L
                }
            }

            Phase.HOLD_BRIEF -> {
                if (elapsedInPhase >= holdDurationMs) {
                    currentPhase = Phase.PASS_OPEN
                    phaseStartedAt = now
                    0L
                } else {
                    max(10L, holdDurationMs - elapsedInPhase)
                }
            }

            Phase.STEADY_PACE -> {
                currentPhase = Phase.PASS_OPEN
                phaseStartedAt = now
                0L
            }
        }
    }

    private fun updateRateWindow(now: Long) {
        val elapsed = now - windowStartAt
        if (elapsed >= 1000L) {
            lastCalculatedKbps = (bytesInWindow / 1024.0) / (elapsed / 1000.0)
            bytesInWindow = 0L
            windowStartAt = now
        }
    }

    fun snapshot(): MetricsSnapshot {
        updateRateWindow(System.currentTimeMillis())
        return MetricsSnapshot(
            currentKbps = lastCalculatedKbps,
            stallCount = stallCount,
            activePhase = currentPhase,
            totalBytes = totalBytes
        )
    }
}
