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
        versionCode = 5
        versionName = "1.4"
    }

    // CI はビルドのたびに使い捨ての debug 鍵で署名するため、そのままだと
    // 署名が毎回変わって上書きインストールできない。固定の鍵で署名する。
    signingConfigs {
        create("fixed") {
            storeFile = file("sumo-league.keystore")
            storePassword = "android"
            keyAlias = "sumoleague"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
