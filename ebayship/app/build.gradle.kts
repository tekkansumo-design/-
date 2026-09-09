plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * ビルドするときの環境変数から初期値を取り込む。
 *
 * 値そのものはリポジトリに置かない。GitHub Secrets から環境変数として渡し、
 * ここで BuildConfig に焼く。
 *
 * EBAYSHIP_EMBED が "true" のときだけ焼く。ワークフローはリポジトリが
 * 非公開のときにしかこれを立てないので、公開の APK に鍵が入ることはない。
 */
fun seed(name: String): String {
    if (System.getenv("EBAYSHIP_EMBED") != "true") return ""
    return System.getenv(name) ?: ""
}

/** BuildConfig は Java として生成されるので、Java の文字列リテラルとして安全な形にする。 */
fun quoted(value: String): String {
    val escaped = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "")
    return "\"" + escaped + "\""
}

android {
    namespace = "com.tekkansumo.ebayship"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tekkansumo.ebayship"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // 端末で長い文字列を打たなくて済むよう、ビルド時に初期値を持たせる
        buildConfigField("String", "SEED_EBAY_CLIENT_ID", quoted(seed("EBAY_APP_ID")))
        buildConfigField("String", "SEED_EBAY_CLIENT_SECRET", quoted(seed("EBAY_CERT_ID")))
        buildConfigField("String", "SEED_EBAY_RUNAME", quoted(seed("EBAY_RUNAME")))
        buildConfigField("String", "SEED_JP_LOGIN_ID", quoted(seed("JP_LOGIN_ID")))
        buildConfigField("String", "SEED_JP_PASSWORD", quoted(seed("JP_PASSWORD")))
        buildConfigField("String", "SEED_MAIL_USER", quoted(seed("MAIL_USER")))
        buildConfigField("String", "SEED_MAIL_PASSWORD", quoted(seed("MAIL_PASSWORD")))
        buildConfigField("String", "SEED_FROM_NAME", quoted(seed("FROM_NAME")))
        buildConfigField("String", "SEED_FROM_POSTAL", quoted(seed("FROM_POSTAL")))
        buildConfigField("String", "SEED_FROM_ADDRESS", quoted(seed("FROM_ADDRESS")))
        buildConfigField("String", "SEED_FROM_PHONE", quoted(seed("FROM_PHONE")))
        buildConfigField("String", "SEED_DEF_HS_CODE", quoted(seed("DEF_HS_CODE")))
        buildConfigField("String", "SEED_DEF_ORIGIN", quoted(seed("DEF_ORIGIN")))
        buildConfigField("String", "SEED_DEF_WEIGHT", quoted(seed("DEF_WEIGHT")))
    }

    buildFeatures {
        buildConfig = true
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

    // android-mail と android-activation が同名のメタファイルを持つため
    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST"
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")
}
