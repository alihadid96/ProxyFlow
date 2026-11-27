package com.proxyflow.app.vpn

/**
 * JNI bridge to hev-socks5-tunnel (libhev-socks5-tunnel.so), which reads IP packets from the TUN file
 * descriptor, runs a user-space TCP/IP stack (lwIP) and forwards every TCP/UDP flow to a SOCKS5 server.
 * The native library registers these methods for class com/proxyflow/app/vpn/TProxyService
 * (compiled with -DPKGNAME=com/proxyflow/app/vpn), so this class name must not change.
 */
object TProxyService {
    @Volatile
    var loadError: Throwable? = null
        private set

    init {
        try {
            System.loadLibrary("hev-socks5-tunnel")
        } catch (t: Throwable) {
            loadError = t
        }
    }

    @JvmStatic external fun TProxyStartService(configPath: String, fd: Int): Boolean
    @JvmStatic external fun TProxyStopService(): Boolean
    @JvmStatic external fun TProxyIsRunning(): Boolean
    /** [tx_packets, tx_bytes, rx_packets, rx_bytes]; tx = read from TUN (upload), rx = written to TUN (download). */
    @JvmStatic external fun TProxyGetStats(): LongArray?
}
