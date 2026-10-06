plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// アーティスト名から CD / DVD の在庫店舗を探す単独アプリ
android {
    namespace = "com.tekkansumo.bookoffsearch"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tekkansumo.bookoffsearch"
        minSdk = 26
        targetSdk = 35
        // build 27 で同じ ID・同じ鍵のものを配ったので、上書きできるよう大きくする
        versionCode = 3
        versionName = "1.2"
    }

    // 毎回同じ鍵で署名し、新しいビルドを上書きインストールできるようにする
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("ci-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.3")
}
