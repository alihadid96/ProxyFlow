package com.proxyflow.app.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/** Reads a small text document (e.g. an .ovpn file) chosen by the user. Returns (display name, text). */
fun readTextDocument(context: Context, uri: Uri, maxBytes: Int = 1024 * 1024): Pair<String, String> {
    var name = "OpenVPN"
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) name = c.getString(0) ?: name
        }
    }
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            buf.write(chunk, 0, n)
            if (buf.size() > maxBytes) throw IllegalArgumentException("File is too large for a VPN configuration")
        }
        buf.toByteArray()
    } ?: throw IllegalArgumentException("Cannot open the file")
    return name.substringBeforeLast('.').ifBlank { "OpenVPN" } to String(bytes, Charsets.UTF_8)
}
