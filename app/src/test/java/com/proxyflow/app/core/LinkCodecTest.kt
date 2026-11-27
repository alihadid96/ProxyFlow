package com.proxyflow.app.core

import com.proxyflow.app.data.DnsMode
import com.proxyflow.app.data.Protocol
import com.proxyflow.app.data.Security
import com.proxyflow.app.data.Transport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class LinkCodecTest {
    private val uuid = "b831381d-6324-4d53-ad4f-8cda48b30811"
    private val pbk = "Z84J2IelR9ch3k8VtlVhhs5ycBUlXA7wHBWcBrjqnAw"

    @Test fun parsesVlessReality() {
        val p = LinkCodec.parse("vless://$uuid@203.0.113.7:443?encryption=none&flow=xtls-rprx-vision&security=reality&sni=www.microsoft.com&fp=chrome&pbk=$pbk&sid=6ba85179e30d4fc2&spx=%2F&type=tcp#My%20Reality")
        assertEquals(Protocol.VLESS, p.protocol)
        assertEquals("203.0.113.7", p.host)
        assertEquals(443, p.port)
        assertEquals(uuid, p.uuid)
        assertEquals("xtls-rprx-vision", p.flow)
        assertEquals(Security.REALITY, p.security)
        assertEquals("www.microsoft.com", p.sni)
        assertEquals(pbk, p.realityPublicKey)
        assertEquals("6ba85179e30d4fc2", p.realityShortId)
        assertEquals("/", p.spiderX)
        assertEquals("My Reality", p.name)
        assertTrue(ProfileValidator.validate(p).isEmpty())
    }

    @Test fun parsesVlessWsTlsIpv6() {
        val p = LinkCodec.parse("vless://$uuid@[2001:db8::1]:8443?type=ws&security=tls&path=%2Fray%3Fed%3D2048&host=cdn.example.com&sni=cdn.example.com&alpn=h2%2Chttp%2F1.1#ws")
        assertEquals("2001:db8::1", p.host)
        assertEquals(8443, p.port)
        assertEquals(Transport.WS, p.transport)
        assertEquals("/ray?ed=2048", p.path)
        assertEquals("cdn.example.com", p.hostHeader)
        assertEquals("h2,http/1.1", p.alpn)
        assertTrue(ProfileValidator.validate(p).isEmpty())
    }

    @Test fun parsesGrpc() {
        val p = LinkCodec.parse("vless://$uuid@example.com:443?type=grpc&serviceName=gun&mode=multi&security=tls&sni=example.com#g")
        assertEquals(Transport.GRPC, p.transport)
        assertEquals("gun", p.serviceName)
        assertTrue(p.grpcMultiMode)
    }

    @Test fun rejectsRemovedH2Transport() {
        val r = LinkCodec.parseMany("vless://$uuid@example.com:443?type=h2&security=tls#x")
        assertTrue(r.profiles.isEmpty())
        assertTrue(r.errors.single().contains("XHTTP"))
    }

    @Test fun parsesSocksWithAuthAndBase64Userinfo() {
        val a = LinkCodec.parse("socks5://alice:p%40ss@10.0.0.2:1080#Office")
        assertEquals(Protocol.SOCKS5, a.protocol)
        assertEquals("alice", a.username)
        assertEquals("p@ss", a.password)
        assertTrue(a.authEnabled)
        val b64 = Base64.getEncoder().encodeToString("bob:secret".toByteArray())
        val b = LinkCodec.parse("socks://$b64@proxy.example.com:1080#b")
        assertEquals("bob", b.username)
        assertEquals("secret", b.password)
    }

    @Test fun parsesHttpNoAuth() {
        val p = LinkCodec.parse("http://192.168.1.10:3128")
        assertEquals(Protocol.HTTP, p.protocol)
        assertFalse(p.authEnabled)
        assertEquals(3128, p.port)
        assertEquals(DnsMode.REMOTE, p.dnsMode)
    }

    @Test fun exportRoundTrips() {
        val original = LinkCodec.parse("vless://$uuid@example.com:443?type=xhttp&mode=stream-one&path=%2Fx&security=tls&sni=example.com&fp=firefox#Round%20Trip")
        val again = LinkCodec.parse(LinkCodec.export(original))
        assertEquals(original.copy(id = 0), again.copy(id = 0))
        val socks = LinkCodec.parse("socks5://u:p@h.example:1080#S")
        assertFalse(LinkCodec.export(socks, includeSecrets = false).contains("u:p@"))
        assertEquals(socks, LinkCodec.parse(LinkCodec.export(socks, includeSecrets = true)))
    }

    @Test fun importsBase64Subscription() {
        val blob = Base64.getEncoder().encodeToString(
            "vless://$uuid@a.example:443?security=tls&type=tcp#A\nsocks5://b.example:1080#B\ngarbage".toByteArray()
        )
        val r = LinkCodec.parseMany(blob)
        assertEquals(2, r.profiles.size)
        assertEquals(1, r.errors.size)
    }

    @Test fun validationCatchesBadInput() {
        assertTrue(LinkCodec.parseMany("http://host:99999").errors.isNotEmpty())
        val bad = LinkCodec.parse("vless://$uuid@example.com:443?security=reality&type=ws&pbk=short#x")
        val problems = ProfileValidator.validate(bad)
        assertTrue(problems.any { it.contains("public key") })
        assertTrue(problems.any { it.contains("REALITY works") })
    }
}
