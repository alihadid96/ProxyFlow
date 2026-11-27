package com.proxyflow.probe;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import java.net.HttpURLConnection;
import java.net.URL;

/** Makes one HTTP request and logs "PROBE <code>" (tag ProxyFlowProbe). */
public class ProbeActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String extra = getIntent().getStringExtra("url");
        final String url = extra != null ? extra : "http://www.gstatic.com/generate_204";
        new Thread(() -> {
            String result;
            String host = Uri.parse(url).getHost();
            String dns;
            try {
                dns = "dns=" + java.net.InetAddress.getByName(host).getHostAddress();
            } catch (Exception e) {
                dns = "dns_error=" + e.getMessage();
            }
            Log.i("ProxyFlowProbe", "DNS " + dns);
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(15000);
                c.setUseCaches(false);
                result = "PROBE " + c.getResponseCode();
                c.disconnect();
            } catch (Exception e) {
                result = "PROBE_ERROR " + e;
            }
            Log.i("ProxyFlowProbe", result);
            runOnUiThread(this::finish);
        }).start();
    }
}
