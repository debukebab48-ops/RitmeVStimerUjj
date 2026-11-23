package com.exp.rhythm

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvMetrics: TextView
    private lateinit var rgMode: RadioGroup
    private lateinit var etTargetPackage: EditText
    private lateinit var btnToggleVpn: Button

    private val uiHandler = Handler(Looper.getMainLooper())
    private val metricsUpdater = object : Runnable {
        override fun run() {
            updateUiState()
            uiHandler.postDelayed(this, 500L)
        }
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvMetrics = findViewById(R.id.tvMetrics)
        rgMode = findViewById(R.id.rgMode)
        etTargetPackage = findViewById(R.id.etTargetPackage)
        btnToggleVpn = findViewById(R.id.btnToggleVpn)

        rgMode.setOnCheckedChangeListener { _, checkedId ->
            RhythmVpnService.engine.mode = if (checkedId == R.id.rbAdaptiveRhythm) {
                RhythmEngine.Mode.ADAPTIVE_RHYTHM
            } else {
                RhythmEngine.Mode.PASSTHROUGH
            }
        }

        btnToggleVpn.setOnClickListener {
            if (RhythmVpnService.isRunning.get()) {
                stopVpnService()
            } else {
                prepareAndStartVpn()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        uiHandler.post(metricsUpdater)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(metricsUpdater)
    }

    private fun prepareAndStartVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        val mode = if (rgMode.checkedRadioButtonId == R.id.rbAdaptiveRhythm) {
            RhythmEngine.Mode.ADAPTIVE_RHYTHM.name
        } else {
            RhythmEngine.Mode.PASSTHROUGH.name
        }

        val intent = Intent(this, RhythmVpnService::class.java).apply {
            action = RhythmVpnService.ACTION_START
            putExtra(RhythmVpnService.EXTRA_TARGET_PACKAGE, etTargetPackage.text.toString())
            putExtra(RhythmVpnService.EXTRA_MODE, mode)
        }
        startService(intent)
    }

    private fun stopVpnService() {
        val intent = Intent(this, RhythmVpnService::class.java).apply {
            action = RhythmVpnService.ACTION_STOP
        }
        startService(intent)
    }

    private fun updateUiState() {
        val running = RhythmVpnService.isRunning.get()
        val snap = RhythmVpnService.engine.snapshot()

        tvStatus.text = if (running) "Status: RUNNING" else "Status: IDLE"
        btnToggleVpn.text = if (running) "STOP EXPERIMENT" else "START EXPERIMENT"

        val totalKb = snap.totalBytes / 1024.0
        tvMetrics.text = String.format(
            Locale.US,
            "Rate: %.1f KB/s\nTotal: %.1f KB\nStall Count: %d\nPhase: %s",
            snap.currentKbps,
            totalKb,
            snap.stallCount,
            snap.activePhase.name
        )
    }
}
