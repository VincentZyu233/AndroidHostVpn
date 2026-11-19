package com.vincentzyu.androidhostvpn

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.vincentzyu.androidhostvpn.ui.HostsVpnScreen
import com.vincentzyu.androidhostvpn.ui.theme.AndroidHostVpnTheme
import com.vincentzyu.androidhostvpn.vpn.HostsVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {

    private companion object {
        const val PREFS_NAME = "hosts"
    }

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    private var isVpnRunning by mutableStateOf(false)
    private var upstreamDns by mutableStateOf("223.5.5.5")
    private var remoteUrl by mutableStateOf("")
    private var hostsText by mutableStateOf("")
    private var isFetchingRemote by mutableStateOf(false)

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            doStartVpn()
        } else {
            isVpnRunning = false
            Toast.makeText(this, "需要授予 VPN 权限才能拦截 DNS", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        loadPrefs()

        setContent {
            AndroidHostVpnTheme {
                HostsVpnScreen(
                    isVpnRunning = isVpnRunning,
                    onToggleVpn = { targetState ->
                        if (targetState) {
                            startVpn()
                        } else {
                            stopVpn()
                        }
                    },
                    upstreamDns = upstreamDns,
                    onUpstreamDnsChange = { upstreamDns = it },
                    remoteUrl = remoteUrl,
                    onRemoteUrlChange = { remoteUrl = it },
                    hostsText = hostsText,
                    onHostsTextChange = { hostsText = it },
                    isFetchingRemote = isFetchingRemote,
                    onFetchRemote = { fetchRemoteHosts() },
                    onSaveAndApply = { saveAndApply() }
                )
            }
        }
    }

    private fun loadPrefs() {
        hostsText = prefs.getString("hosts_text", "") ?: ""
        upstreamDns = prefs.getString("upstream_dns", "223.5.5.5") ?: "223.5.5.5"
        remoteUrl = prefs.getString("remote_url", "") ?: ""
        isVpnRunning = HostsVpnService.isRunning
    }

    private fun savePrefs() {
        val cleanUpstream = upstreamDns.trim().ifEmpty { "223.5.5.5" }
        prefs.edit()
            .putString("hosts_text", hostsText)
            .putString("upstream_dns", cleanUpstream)
            .putString("remote_url", remoteUrl.trim())
            .apply()
    }

    private fun saveAndApply() {
        savePrefs()
        if (HostsVpnService.isRunning) {
            sendServiceIntent(HostsVpnService.ACTION_RELOAD)
            Toast.makeText(this, "配置已保存并重载生效", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startVpn() {
        savePrefs()
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            doStartVpn()
        }
    }

    private fun doStartVpn() {
        sendServiceIntent(HostsVpnService.ACTION_START)
        isVpnRunning = true
    }

    private fun stopVpn() {
        sendServiceIntent(HostsVpnService.ACTION_STOP)
        isVpnRunning = false
    }

    private fun sendServiceIntent(action: String) {
        val intent = Intent(this, HostsVpnService::class.java).setAction(action)
        startService(intent)
    }

    private fun fetchRemoteHosts() {
        val targetUrl = remoteUrl.trim()
        if (targetUrl.isEmpty()) {
            Toast.makeText(this, "请先输入远程 hosts 订阅地址", Toast.LENGTH_SHORT).show()
            return
        }

        isFetchingRemote = true
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val conn = URL(targetUrl).openConnection() as HttpURLConnection
                    conn.connectTimeout = 15000
                    conn.readTimeout = 15000
                    conn.requestMethod = "GET"
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    if (conn.responseCode == 200) body else null
                } catch (e: Exception) {
                    null
                }
            }
            isFetchingRemote = false

            if (result != null) {
                hostsText = result
                Toast.makeText(this@MainActivity, "已成功拉取远程 hosts 内容", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, "拉取失败，请检查网络或 URL 是否正确", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isVpnRunning = HostsVpnService.isRunning
    }
}
