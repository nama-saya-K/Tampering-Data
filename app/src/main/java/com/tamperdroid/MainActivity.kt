package com.tamperdroid

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tamperdroid.proxy.CertAuthority
import com.tamperdroid.proxy.Exchange
import com.tamperdroid.proxy.ProxyBus
import com.tamperdroid.proxy.ProxyService

class MainActivity : AppCompatActivity(), ProxyBus.Listener {

    private lateinit var adapter: RequestListAdapter
    private lateinit var txtStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val portField = findViewById<EditText>(R.id.editPort)
        val btnStart = findViewById<Button>(R.id.btnStart)
        val btnStop = findViewById<Button>(R.id.btnStop)
        val btnExportCa = findViewById<Button>(R.id.btnExportCa)
        val switchIntercept = findViewById<Switch>(R.id.switchIntercept)
        val recycler = findViewById<RecyclerView>(R.id.recyclerRequests)
        txtStatus = findViewById(R.id.txtStatus)

        adapter = RequestListAdapter(mutableListOf()) { exchange ->
            val i = Intent(this, DetailActivity::class.java)
            i.putExtra("exchangeId", exchange.id)
            startActivity(i)
        }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        CertAuthority.init(applicationContext)
        updateStatus()

        btnStart.setOnClickListener {
            val port = portField.text.toString().toIntOrNull() ?: 8888
            val i = Intent(this, ProxyService::class.java)
            i.putExtra(ProxyService.EXTRA_PORT, port)
            startForegroundService(i)
            Toast.makeText(this, "Started on port $port. Set this device's Wi-Fi proxy to this IP:$port", Toast.LENGTH_LONG).show()
            updateStatus()
        }

        btnStop.setOnClickListener {
            stopService(Intent(this, ProxyService::class.java))
            updateStatus()
        }

        switchIntercept.setOnCheckedChangeListener { _, checked ->
            ProxyBus.interceptEnabled = checked
        }

        btnExportCa.setOnClickListener {
            val file = CertAuthority.exportCaCertFile(this)
            if (!file.exists()) {
                Toast.makeText(this, "Start the proxy once first to generate the CA.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "application/x-x509-ca-cert"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "Install / share CA cert"))
        }
    }

    override fun onStart() {
        super.onStart()
        ProxyBus.addListener(this)
        adapter.submit(ProxyBus.history)
    }

    override fun onStop() {
        super.onStop()
        ProxyBus.removeListener(this)
    }

    private fun updateStatus() {
        txtStatus.text = if (ProxyService.isRunning) "Proxy: RUNNING" else "Proxy: stopped"
    }

    // ---- ProxyBus.Listener (called from proxy worker threads!) ----

    override fun onNewExchange(exchange: Exchange) {
        runOnUiThread { adapter.submit(ProxyBus.history) }
    }

    override fun onExchangeUpdated(exchange: Exchange) {
        runOnUiThread { adapter.updateOne(exchange) }
    }

    override fun onHeldForIntercept(exchange: Exchange) {
        runOnUiThread {
            Toast.makeText(this, "Held: ${exchange.method} ${exchange.host}${exchange.path}", Toast.LENGTH_SHORT).show()
            adapter.submit(ProxyBus.history)
        }
    }
}
