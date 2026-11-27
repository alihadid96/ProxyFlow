package com.proxyflow.app.core

import com.proxyflow.app.data.ProxyProfile
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

/**
 * Reachability test for OpenVPN servers without bringing up a VPN:
 * sends a real P_CONTROL_HARD_RESET_CLIENT_V2 packet and waits for the server's reset reply.
 * Servers protected with tls-auth / tls-crypt silently drop unauthenticated packets, so for those
 * only TCP reachability (TCP servers) or DNS resolution (UDP servers) can be checked.
 */
object OpenVpnProbe {
    private const val P_CONTROL_HARD_RESET_CLIENT_V2 = 7

    fun hardReset(): ByteArray {
        val sid = ByteArray(8).also { SecureRandom().nextBytes(it) }
        return byteArrayOf((P_CONTROL_HARD_RESET_CLIENT_V2 shl 3).toByte()) + sid + byteArrayOf(0) + byteArrayOf(0, 0, 0, 0)
    }

    fun test(p: ProxyProfile, timeoutMs: Int): TestResult {
        val parsed = OvpnConfig.parse(p.ovpnConfig)
        if (parsed.errors.isNotEmpty()) return TestResult(-1, parsed.errors.first())
        val remote = parsed.firstRemote ?: return TestResult(-1, "No remote server in configuration")
        val addr = try {
            InetAddress.getByName(remote.host)
        } catch (e: Exception) {
            return TestResult(-1, "Cannot resolve ${remote.host}")
        }
        val tcp = remote.proto.startsWith("tcp")
        return if (tcp) testTcp(addr, remote.port, parsed.hasTlsAuthOrCrypt, timeoutMs)
        else testUdp(addr, remote.port, parsed.hasTlsAuthOrCrypt, timeoutMs)
    }

    private fun testTcp(addr: InetAddress, port: Int, tlsAuth: Boolean, timeoutMs: Int): TestResult = try {
        Socket().use { s ->
            val start = System.nanoTime()
            s.connect(InetSocketAddress(addr, port), timeoutMs)
            val connectMs = ((System.nanoTime() - start) / 1_000_000).toInt()
            if (tlsAuth) return TestResult(connectMs, null)
            s.soTimeout = timeoutMs
            val pkt = hardReset()
            s.getOutputStream().write(byteArrayOf((pkt.size shr 8).toByte(), pkt.size.toByte()) + pkt)
            val inp = DataInputStream(s.getInputStream())
            val len = inp.readUnsignedShort()
            val first = inp.readUnsignedByte()
            val ms = ((System.nanoTime() - start) / 1_000_000).toInt()
            if (len > 0 && (first shr 3) in 1..10) TestResult(ms, null)
            else TestResult(-1, "Server answered, but not with an OpenVPN handshake")
        }
    } catch (e: java.net.SocketTimeoutException) {
        TestResult(-1, "No answer from OpenVPN server (timeout)")
    } catch (e: Exception) {
        TestResult(-1, "Cannot reach ${addr.hostAddress}:$port (${e.message})")
    }

    private fun testUdp(addr: InetAddress, port: Int, tlsAuth: Boolean, timeoutMs: Int): TestResult {
        // tls-auth / tls-crypt: the server ignores our unauthenticated probe — a reply cannot be expected.
        if (tlsAuth) return TestResult(-1, null)
        return try {
            DatagramSocket().use { s ->
                s.soTimeout = timeoutMs
                val pkt = hardReset()
                val start = System.nanoTime()
                s.send(DatagramPacket(pkt, pkt.size, addr, port))
                val buf = ByteArray(2048)
                val reply = DatagramPacket(buf, buf.size)
                s.receive(reply)
                val ms = ((System.nanoTime() - start) / 1_000_000).toInt()
                if (reply.length > 0 && (buf[0].toInt() and 0xFF shr 3) in 1..10) TestResult(ms, null)
                else TestResult(-1, "Unexpected UDP reply")
            }
        } catch (e: java.net.SocketTimeoutException) {
            TestResult(-1, "No answer from OpenVPN server over UDP (timeout)")
        } catch (e: Exception) {
            TestResult(-1, "UDP probe failed: ${e.message}")
        }
    }
}
