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

    // anthropic-java が引き込む Jackson / httpclient5 などが同名のメタファイルを持つため
    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/FastDoubleParser-*",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "**/module-info.class"
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // 商品特定・調査・出品文（Claude API の公式 SDK）
    implementation("com.anthropic:anthropic-java:2.66.0")
}
