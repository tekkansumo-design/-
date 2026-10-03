plugins {
    id("com.android.application")
}

android {
    namespace = "com.tekkansumo.sumoleague"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tekkansumo.sumoleague"
        minSdk = 26
        // 35 にすると Android 15 で全画面表示が強制され、WebView がステータスバーに潜る
        targetSdk = 34
        versionCode = 3
        versionName = "1.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
