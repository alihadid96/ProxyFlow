package com.proxyflow.app.core

import android.content.Context
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlin.concurrent.thread

/** Runs the bundled Xray-core binary (packaged as lib/<abi>/libxray.so) as a child process. */
class XrayProcess(
    private val context: Context,
    private val secrets: List<String>,
    private val onLog: (String) -> Unit,
) {
    @Volatile private var process: Process? = null
    private val tail = ArrayDeque<String>()

    val isAlive: Boolean get() = process?.isAlive == true

    fun start(configJson: String) {
        val bin = binary(context)
        if (!bin.exists()) throw ProxyException("Xray-core binary is missing from this build (${bin.name})")
        val pb = ProcessBuilder(bin.absolutePath, "run", "-format", "json", "-c", "stdin:")
            .directory(context.filesDir)
            .redirectErrorStream(true)
        pb.environment()["XRAY_LOCATION_ASSET"] = context.filesDir.absolutePath
        pb.environment()["XRAY_LOCATION_CONFIG"] = context.filesDir.absolutePath
        val proc = pb.start()
        process = proc
        // The configuration (which contains credentials) is passed over stdin and never written to disk.
        proc.outputStream.use { it.write(configJson.toByteArray(Charsets.UTF_8)) }
        thread(name = "xray-log", isDaemon = true) {
            runCatching {
                proc.inputStream.bufferedReader().forEachLine { raw ->
                    val line = sanitize(raw)
                    synchronized(tail) {
                        tail.addLast(line)
                        while (tail.size > 40) tail.removeFirst()
                    }
                    onLog(line)
                }
            }
        }
    }

    fun recentOutput(): String = synchronized(tail) { tail.joinToString("\n") }

    /** Waits until the local SOCKS inbound accepts connections, or throws if Xray exits / times out. */
    fun awaitReady(port: Int, timeoutMs: Long = 8000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val proc = process ?: throw ProxyException("Xray-core is not running")
            if (!proc.isAlive) {
                Thread.sleep(150) // let the log reader drain
                throw ProxyException("Xray-core exited (code ${proc.exitValue()}): " + lastError())
            }
            try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) }
                return
            } catch (_: Exception) {
                Thread.sleep(120)
            }
        }
        throw ProxyException("Xray-core did not start within ${timeoutMs / 1000}s: " + lastError())
    }

    private fun lastError(): String {
        val lines = synchronized(tail) { tail.toList() }
        return lines.lastOrNull { it.contains("Failed", true) || it.contains("error", true) }
            ?: lines.lastOrNull() ?: "no output"
    }

    fun waitFor(): Int = process?.waitFor() ?: -1

    fun stop() {
        val p = process ?: return
        process = null
        runCatching {
            p.destroy()
            if (!p.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS)) p.destroyForcibly()
        }
    }

    private fun sanitize(line: String): String {
        var out = line
        for (s in secrets) out = out.replace(s, "***")
        return out
    }

    companion object {
        fun binary(context: Context) = File(context.applicationInfo.nativeLibraryDir, "libxray.so")

        fun freePort(): Int = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }

        fun randomToken(len: Int = 16): String {
            val chars = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
            val rnd = SecureRandom()
            return (1..len).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
        }

        fun newInbound() = LocalInbound(freePort(), randomToken(), randomToken(24))

        /** Runs `xray version` – used by the About screen to show the real bundled version. */
        fun version(context: Context): String = runCatching {
            val p = ProcessBuilder(binary(context).absolutePath, "version").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out.lineSequence().firstOrNull()?.trim().orEmpty()
        }.getOrDefault("unavailable")
    }
}
