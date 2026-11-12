plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tekkansumo.ebaylister"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tekkansumo.ebaylister"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
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
    kotlinOptions {
        jvmTarget = "17"
    }

    // Jackson / Kotlin などが同名のメタファイルを持つため
    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
            pickFirsts += setOf("META-INF/*.version")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Claude API（商品の調査と Item Specifics の下書き作成）
    implementation("com.anthropic:anthropic-java:3.3.0")
    // Google Play 開発者サービスのバーコードスキャナ（カメラ権限不要）
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
}
