// Tiny helper app used only by the automated emulator test (scripts/e2e-android.sh):
// an ordinary app (its own UID) that makes one HTTP request, so the test can verify that
// normal apps' traffic is routed through ProxyFlow's VPN. Not shipped.
plugins {
    id("com.android.application")
}

android {
    namespace = "com.proxyflow.probe"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.proxyflow.probe"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
