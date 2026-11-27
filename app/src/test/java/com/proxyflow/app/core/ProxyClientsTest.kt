package com.proxyflow.app.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Exercises the real SOCKS5 and HTTP CONNECT client implementations against small in-process proxy
 * servers that forward to a local HTTP server answering 204 (the same probe the app uses).
 */
class ProxyClientsTest {
    private val sockets = mutableListOf<ServerSocket>()
    private var targetPort = 0

    @Before fun setUp() {
        val target = ServerSocket(0, 50, InetAddress.getLoopbackAddress()); sockets += target
        targetPort = target.localPort
        thread(isDaemon = true) {
            while (!target.isClosed) {
                val c = runCatching { target.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    c.use {
                        val inp = it.getInputStream()
                        var last = 0
                        while (true) { val b = inp.read(); if (b < 0) break; last = (last shl 8) or b; if (last == 0x0D0A0D0A) break }
                        it.getOutputStream().write("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n".toByteArray())
                    }
                }
            }
        }
    }

    @After fun tearDown() { sockets.forEach { runCatching { it.close() } } }

    private fun pipe(a: Socket, b: Socket) {
        fun copy(i: InputStream, o: OutputStream) = thread(isDaemon = true) {
            runCatching { val buf = ByteArray(8192); while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); o.flush() } }
            runCatching { a.close() }; runCatching { b.close() }
        }
        copy(a.getInputStream(), b.getOutputStream()); copy(b.getInputStream(), a.getOutputStream())
    }

    /** Minimal RFC1928/1929 server. Resolves "target.test" to loopback to prove remote (proxy-side) DNS. */
    private fun startSocks(user: String?, pass: String?): Int {
        val ss = ServerSocket(0, 50, InetAddress.getLoopbackAddress()); sockets += ss
        thread(isDaemon = true) {
            while (!ss.isClosed) {
                val c = runCatching { ss.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    runCatching {
                        val i = DataInputStream(c.getInputStream()); val o = c.getOutputStream()
                        i.readByte(); val n = i.readUnsignedByte(); val methods = ByteArray(n).also { i.readFully(it) }.map { it.toInt() }
                        if (user != null) {
                            if (2 !in methods) { o.write(byteArrayOf(5, 0xFF.toByte())); c.close(); return@thread }
                            o.write(byteArrayOf(5, 2))
                            i.readByte()
                            val u = String(ByteArray(i.readUnsignedByte()).also { i.readFully(it) })
                            val p = String(ByteArray(i.readUnsignedByte()).also { i.readFully(it) })
                            if (u != user || p != pass) { o.write(byteArrayOf(1, 1)); c.close(); return@thread }
                            o.write(byteArrayOf(1, 0))
                        } else o.write(byteArrayOf(5, 0))
                        i.readByte(); i.readByte(); i.readByte()
                        val host = when (i.readUnsignedByte()) {
                            1 -> InetAddress.getByAddress(ByteArray(4).also { i.readFully(it) }).hostAddress
                            3 -> String(ByteArray(i.readUnsignedByte()).also { i.readFully(it) })
                            else -> InetAddress.getByAddress(ByteArray(16).also { i.readFully(it) }).hostAddress
                        }
                        val port = i.readUnsignedShort()
                        val dest = Socket(if (host == "target.test") "127.0.0.1" else host, port)
                        o.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
                        pipe(c, dest)
                    }.onFailure { runCatching { c.close() } }
                }
            }
        }
        return ss.localPort
    }

    private fun startHttpProxy(user: String?, pass: String?): Int {
        val ss = ServerSocket(0, 50, InetAddress.getLoopbackAddress()); sockets += ss
        thread(isDaemon = true) {
            while (!ss.isClosed) {
                val c = runCatching { ss.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    runCatching {
                        val inp = c.getInputStream(); val sb = StringBuilder(); var last = 0
                        while (true) { val b = inp.read(); if (b < 0) break; sb.append(b.toChar()); last = (last shl 8) or b; if (last == 0x0D0A0D0A) break }
                        val head = sb.toString()
                        val target = head.lineSequence().first().split(' ')[1]
                        if (user != null) {
                            val expected = "Basic " + java.util.Base64.getEncoder().encodeToString("$user:$pass".toByteArray())
                            if (!head.lines().any { it.equals("Proxy-Authorization: $expected", true) }) {
                                c.getOutputStream().write("HTTP/1.1 407 Proxy Authentication Required\r\n\r\n".toByteArray()); c.close(); return@thread
                            }
                        }
                        val host = target.substringBeforeLast(':'); val port = target.substringAfterLast(':').toInt()
                        val dest = Socket(if (host == "target.test") "127.0.0.1" else host, port)
                        c.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
                        pipe(c, dest)
                    }.onFailure { runCatching { c.close() } }
                }
            }
        }
        return ss.localPort
    }

    private fun probe(s: Socket) = HttpProbe.get(s, "target.test", "/generate_204", tls = false, port = targetPort).code

    @Test fun socks5NoAuth() {
        val port = startSocks(null, null)
        assertEquals(204, probe(Socks5Client.connect("127.0.0.1", port, null, null, "target.test", targetPort, 3000)))
    }

    @Test fun socks5WithAuth() {
        val port = startSocks("alice", "s3cret")
        assertEquals(204, probe(Socks5Client.connect("127.0.0.1", port, "alice", "s3cret", "target.test", targetPort, 3000)))
    }

    @Test fun socks5WrongPassword() {
        val port = startSocks("alice", "s3cret")
        try {
            Socks5Client.connect("127.0.0.1", port, "alice", "nope", "target.test", targetPort, 3000); fail()
        } catch (e: ProxyException) { assertEquals(ProxyException.Kind.AUTH, e.kind) }
    }

    @Test fun socks5MissingCredentials() {
        val port = startSocks("alice", "s3cret")
        try {
            Socks5Client.connect("127.0.0.1", port, null, null, "target.test", targetPort, 3000); fail()
        } catch (e: ProxyException) { assertEquals(ProxyException.Kind.AUTH, e.kind) }
    }

    @Test fun httpNoAuth() {
        val port = startHttpProxy(null, null)
        assertEquals(204, probe(HttpConnectClient.connect("127.0.0.1", port, null, null, "target.test", targetPort, 3000)))
    }

    @Test fun httpWithAuth() {
        val port = startHttpProxy("bob", "pa:ss")
        assertEquals(204, probe(HttpConnectClient.connect("127.0.0.1", port, "bob", "pa:ss", "target.test", targetPort, 3000)))
    }

    @Test fun httpWrongPassword() {
        val port = startHttpProxy("bob", "pa:ss")
        try {
            HttpConnectClient.connect("127.0.0.1", port, "bob", "wrong", "target.test", targetPort, 3000); fail()
        } catch (e: ProxyException) {
            assertEquals(ProxyException.Kind.AUTH, e.kind); assertTrue(e.message!!.contains("407"))
        }
    }

    @Test fun unreachableProxy() {
        val dead = ServerSocket(0).let { val p = it.localPort; it.close(); p }
        try {
            Socks5Client.connect("127.0.0.1", dead, null, null, "target.test", targetPort, 2000); fail()
        } catch (e: ProxyException) { assertEquals(ProxyException.Kind.UNREACHABLE, e.kind) }
    }

    @Test fun silentServerTimesOut() {
        val ss = ServerSocket(0, 50, InetAddress.getLoopbackAddress()); sockets += ss
        thread(isDaemon = true) { runCatching { val keep = ss.accept(); Thread.sleep(5000); keep.close() } }
        val start = System.currentTimeMillis()
        try {
            HttpConnectClient.connect("127.0.0.1", ss.localPort, null, null, "target.test", 80, 800); fail()
        } catch (e: ProxyException) {
            assertEquals(ProxyException.Kind.TIMEOUT, e.kind)
            assertTrue(System.currentTimeMillis() - start < 3000)
        }
    }

    @Test fun urlTestMeasuresLatency() {
        val port = startSocks(null, null)
        val tunnel = Tunnel { h, p, t -> Socks5Client.connect("127.0.0.1", port, null, null, if (h == ProxyTester.PROBE_HOST) "target.test" else h, targetPort, t) }
        // urlTest targets port 80 on PROBE_HOST; this tunnel rewrites it to the local target server.
        val ms = ProxyTester.urlTest(tunnel, 3000)
        assertTrue(ms in 0..3000)
    }
}
