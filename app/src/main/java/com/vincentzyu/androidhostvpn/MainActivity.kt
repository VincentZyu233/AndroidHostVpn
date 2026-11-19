package com.vincentzyu.androidhostvpn

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import com.vincentzyu.androidhostvpn.vpn.HostsVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private companion object {
        const val VPN_REQUEST_CODE = 1
        const val PREFS_NAME = "hosts"
    }

    private lateinit var switch: SwitchMaterial
    private lateinit var hostsEdit: android.widget.EditText
    private lateinit var upstreamEdit: android.widget.EditText
    private lateinit var remoteUrlEdit: android.widget.EditText

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        switch = findViewById(R.id.vpn_switch)
        hostsEdit = findViewById(R.id.hosts_edit)
        upstreamEdit = findViewById(R.id.upstream_edit)
        remoteUrlEdit = findViewById(R.id.remote_url_edit)

        loadPrefs()

        switch.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                startVpn()
            } else {
                stopVpn()
            }
        }

        findViewById<android.widget.Button>(R.id.save_btn).setOnClickListener {
            savePrefs()
            if (HostsVpnService.isRunning) {
                sendServiceIntent(HostsVpnService.ACTION_RELOAD)
            }
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        }

        findViewById<android.widget.Button>(R.id.fetch_remote_btn).setOnClickListener {
            fetchRemote()
        }
    }

    private fun loadPrefs() {
        hostsEdit.setText(prefs.getString("hosts_text", ""))
        upstreamEdit.setText(prefs.getString("upstream_dns", "8.8.8.8"))
        remoteUrlEdit.setText(prefs.getString("remote_url", ""))
        switch.isChecked = HostsVpnService.isRunning
    }

    private fun savePrefs() {
        prefs.edit()
            .putString("hosts_text", hostsEdit.text.toString())
            .putString("upstream_dns", upstreamEdit.text.toString().trim().ifEmpty { "8.8.8.8" })
            .putString("remote_url", remoteUrlEdit.text.toString().trim())
            .apply()
    }

    private fun startVpn() {
        savePrefs()
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, VPN_REQUEST_CODE)
        } else {
            doStartVpn()
        }
    }

    private fun doStartVpn() {
        sendServiceIntent(HostsVpnService.ACTION_START)
    }

    private fun stopVpn() {
        sendServiceIntent(HostsVpnService.ACTION_STOP)
    }

    private fun sendServiceIntent(action: String) {
        val intent = Intent(this, HostsVpnService::class.java).setAction(action)
        startService(intent)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                doStartVpn()
            } else {
                switch.isChecked = false
                Toast.makeText(this, "需要授权 VPN 才能生效", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun fetchRemote() {
        val url = remoteUrlEdit.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(this, "请先填写远程 hosts 地址", Toast.LENGTH_SHORT).show()
            return
        }
        CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 15000
                    conn.readTimeout = 15000
                    conn.requestMethod = "GET"
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    if (conn.responseCode == 200) body else null
                } catch (e: Exception) {
                    null
                }
            }
            if (result != null) {
                hostsEdit.setText(result)
                Toast.makeText(this@MainActivity, "已拉取远程 hosts", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, "拉取失败，请检查地址或网络", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        switch.isChecked = HostsVpnService.isRunning
    }
}
