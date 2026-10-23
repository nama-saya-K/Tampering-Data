package com.tamperdroid

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.tamperdroid.proxy.Exchange
import com.tamperdroid.proxy.ProxyBus

class DetailActivity : AppCompatActivity() {

    private var exchange: Exchange? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)

        val id = intent.getStringExtra("exchangeId")
        exchange = ProxyBus.history.firstOrNull { it.id == id }
        val ex = exchange ?: run { finish(); return }

        val editMethod = findViewById<EditText>(R.id.editMethod)
        val editPath = findViewById<EditText>(R.id.editPath)
        val editHeaders = findViewById<EditText>(R.id.editHeaders)
        val editBody = findViewById<EditText>(R.id.editBody)
        val txtResponse = findViewById<TextView>(R.id.txtResponse)
        val btnForward = findViewById<Button>(R.id.btnForward)
        val btnDrop = findViewById<Button>(R.id.btnDrop)

        editMethod.setText(ex.method)
        editPath.setText(ex.path)
        editHeaders.setText(ex.headers.joinToString("\n") { "${it.first}: ${it.second}" })
        editBody.setText(String(ex.body, Charsets.UTF_8))

        val canEdit = ex.held
        editMethod.isEnabled = canEdit
        editPath.isEnabled = canEdit
        editHeaders.isEnabled = canEdit
        editBody.isEnabled = canEdit
        btnForward.isEnabled = canEdit
        btnDrop.isEnabled = canEdit

        if (ex.status != null) {
            val respHeaders = ex.responseHeaders.joinToString("\n") { "${it.first}: ${it.second}" }
            val respBody = ex.responseBody?.let { String(it, Charsets.UTF_8) } ?: ""
            txtResponse.text = "HTTP ${ex.status}\n$respHeaders\n\n$respBody"
        } else {
            txtResponse.text = if (ex.held) "(held — edit above, then Forward)" else "(pending...)"
        }

        btnForward.setOnClickListener {
            // Apply edits back onto the exchange object before releasing the waiting proxy thread.
            ex.method = editMethod.text.toString()
            ex.path = editPath.text.toString()
            ex.headers = editHeaders.text.toString().lines()
                .mapNotNull { line ->
                    val idx = line.indexOf(":")
                    if (idx > 0) line.substring(0, idx).trim() to line.substring(idx + 1).trim() else null
                }.toMutableList()
            ex.body = editBody.text.toString().toByteArray(Charsets.UTF_8)
            ProxyBus.release(ex)
            finish()
        }

        btnDrop.setOnClickListener {
            ProxyBus.drop(ex)
            finish()
        }
    }
}
