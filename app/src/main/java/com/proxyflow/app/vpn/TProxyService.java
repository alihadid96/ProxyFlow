package com.proxyflow.app.vpn;

/**
 * JNI bridge to hev-socks5-tunnel (libhev-socks5-tunnel.so). The native library registers these exact
 * static methods for class com/proxyflow/app/vpn/TProxyService (built with -DPKGNAME=com/proxyflow/app/vpn),
 * so this class is plain Java to guarantee the JVM signatures: TProxyStartService(Ljava/lang/String;I)Z etc.
 */
public final class TProxyService {
    private TProxyService() {}

    /** Non-null if the native library could not be loaded. */
    public static volatile Throwable loadError;

    static {
        try {
            System.loadLibrary("hev-socks5-tunnel");
        } catch (Throwable t) {
            loadError = t;
        }
    }


    public static native boolean TProxyStartService(String configPath, int fd);
    public static native boolean TProxyStopService();
    public static native boolean TProxyIsRunning();
    /** [tx_packets, tx_bytes, rx_packets, rx_bytes]; tx = read from TUN (upload), rx = written to TUN (download). */
    public static native long[] TProxyGetStats();
}
