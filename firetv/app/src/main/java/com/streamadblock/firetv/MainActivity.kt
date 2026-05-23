package com.streamadblock.firetv

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.streamadblock.firetv.databinding.ActivityMainBinding
import com.streamadblock.firetv.vpn.AdBlockVpnService
import kotlinx.coroutines.*

/**
 * Main UI — Fire TV remote-friendly.
 *
 * Big focusable buttons:
 *   ⚡ START / STOP (toggles the VPN)
 *   Mode picker: VPN  |  DNS Client
 *
 * Stats refresh every second while on screen.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var refreshJob: Job? = null

    /** Receives VpnService.prepare() consent dialog result */
    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) startVpnService()
            else updateUi()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        BlocklistManager.loadAll(this)
        Stats.load(this)

        binding.toggleButton.setOnClickListener { onToggleClicked() }
        binding.modeVpn.setOnClickListener { setMode(Settings.MODE_VPN) }
        binding.modeDnsClient.setOnClickListener { setMode(Settings.MODE_DNS_CLIENT) }
        binding.autoStartToggle.setOnCheckedChangeListener { _, checked ->
            Settings.setAutoStart(this, checked)
        }
        binding.autoStartToggle.isChecked = Settings.isAutoStart(this)

        // Make the toggle button the initially focused element (Fire TV remote)
        binding.toggleButton.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        updateUi()
        refreshJob?.cancel()
        refreshJob = mainScope.launch {
            while (isActive) {
                updateUi()
                delay(1000)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        refreshJob?.cancel()
        Stats.save(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        mainScope.cancel()
    }

    private fun onToggleClicked() {
        val mode = Settings.getMode(this)
        if (mode == Settings.MODE_VPN) {
            if (AdBlockVpnService.isRunning) {
                stopVpnService()
            } else {
                val prep = VpnService.prepare(this)
                if (prep != null) {
                    vpnPermissionLauncher.launch(prep)
                } else {
                    startVpnService()
                }
            }
        } else {
            // DNS Client mode: show instructions, can't toggle from app directly.
            showDnsClientInstructions()
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, AdBlockVpnService::class.java).apply {
            action = AdBlockVpnService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        updateUi()
    }

    private fun stopVpnService() {
        startService(Intent(this, AdBlockVpnService::class.java).apply {
            action = AdBlockVpnService.ACTION_STOP
        })
        updateUi()
    }

    private fun setMode(mode: String) {
        Settings.setMode(this, mode)
        if (mode != Settings.MODE_VPN && AdBlockVpnService.isRunning) {
            stopVpnService()
        }
        updateUi()
    }

    private fun showDnsClientInstructions() {
        binding.instructionsCard.visibility = View.VISIBLE
        binding.instructionsText.text = getString(
            R.string.dns_client_instructions,
            Settings.getUpstreamDns(this)
        )
    }

    private fun updateUi() {
        val mode = Settings.getMode(this)
        val running = AdBlockVpnService.isRunning

        // Status pill
        binding.statusPill.text = when {
            mode == Settings.MODE_VPN && running -> getString(R.string.status_active)
            mode == Settings.MODE_VPN            -> getString(R.string.status_inactive)
            else                                  -> getString(R.string.status_dns_client_mode)
        }
        binding.statusPill.setBackgroundResource(
            if (running || mode != Settings.MODE_VPN) R.drawable.pill_on else R.drawable.pill_off
        )

        // Toggle button label
        binding.toggleButton.text = when {
            mode != Settings.MODE_VPN          -> getString(R.string.show_setup)
            running                             -> getString(R.string.stop_blocking)
            else                                -> getString(R.string.start_blocking)
        }

        // Stats
        binding.statBlocked.text = Stats.todayBlocked.get().toString()
        binding.statTotal.text = Stats.totalBlocked.get().toString()
        binding.statRate.text = "${Stats.blockRate()}%"
        binding.statDomains.text = BlocklistManager.blockedCount().toString()

        // Mode buttons selection state
        binding.modeVpn.isSelected = mode == Settings.MODE_VPN
        binding.modeDnsClient.isSelected = mode == Settings.MODE_DNS_CLIENT

        // Instructions card
        binding.instructionsCard.visibility =
            if (mode == Settings.MODE_DNS_CLIENT) View.VISIBLE else View.GONE
        if (mode == Settings.MODE_DNS_CLIENT) {
            binding.instructionsText.text = getString(
                R.string.dns_client_instructions,
                Settings.getUpstreamDns(this)
            )
        }
    }
}
