package com.tamperdroid.proxy

import java.util.UUID

/** A single captured (and possibly edited) request/response pair. */
data class Exchange(
    val id: String = UUID.randomUUID().toString(),
    var method: String,
    var scheme: String,       // "http" or "https"
    var host: String,
    var port: Int,
    var path: String,         // e.g. "/wp-login.php"
    var headers: MutableList<Pair<String, String>>,
    var body: ByteArray,
    var timestamp: Long = System.currentTimeMillis(),
    var status: Int? = null,
    var responseHeaders: MutableList<Pair<String, String>> = mutableListOf(),
    var responseBody: ByteArray? = null,
    var held: Boolean = false // true while waiting in the intercept queue for user action
) {
    val url: String get() = "$scheme://$host${if ((scheme == "http" && port != 80) || (scheme == "https" && port != 443)) ":$port" else ""}$path"

    fun rawRequestLine(): String = "$method $path HTTP/1.1"
}
