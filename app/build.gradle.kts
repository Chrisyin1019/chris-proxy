import java.util.Properties

plugins {
    id("com.android.application")
}

// Release key lives outside the project, like Chris Secure and the ERP app. Override the path
// with CHRIS_PROXY_RELEASE_PROPERTIES. Keys: storeFile, storePassword,
// keyAlias, keyPassword. Without it, assembleRelease produces an unsigned APK.
val releasePropsFile = file(
    System.getenv("CHRIS_PROXY_RELEASE_PROPERTIES")
        ?: "${System.getProperty("user.home")}/Android/chris-proxy-release-keystore.properties"
)
val releaseProps = Properties().apply {
    // UTF-8 reader: the default ISO-8859-1 mangles non-ASCII paths (e.g. ~/文件/...).
    if (releasePropsFile.exists()) releasePropsFile.reader(Charsets.UTF_8).use { load(it) }
}

val localPropsFile = rootProject.file("local.properties")
val updatePropsFile = file(
    System.getenv("CHRIS_PROXY_UPDATE_PROPERTIES")
        ?: "${System.getProperty("user.home")}/Android/chris-proxy-update.properties"
)
val updateProps = Properties().apply {
    if (updatePropsFile.exists()) updatePropsFile.reader(Charsets.UTF_8).use { load(it) }
    if (localPropsFile.exists()) localPropsFile.reader(Charsets.UTF_8).use { load(it) }
}
val defaultUpdateUrl = System.getenv("CHRIS_PROXY_UPDATE_URL")
    ?: updateProps.getProperty("updateUrl", "")

android {
    namespace = "hk.chris.proxy"
    compileSdk = 36

    defaultConfig {
        // Same family as hk.chris.shuangtie; never reuse another app's ID or key.
        applicationId = "hk.chris.proxy"
        minSdk = 26
        targetSdk = 36
        versionCode = 16
        versionName = "1.4.1"
        buildConfigField(
            "String",
            "DEFAULT_UPDATE_URL",
            "\"" + defaultUpdateUrl.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        )
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // Target device is GrapheneOS on Pixel/ARM64. Avoid shipping
            // three unused native ABIs and inflating the APK to 300+ MB.
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        create("projectDebug") {
            storeFile = file("keys/neutralproxy-debug.keystore")
            storePassword = "neutralproxy-debug-only"
            keyAlias = "neutralproxy-debug"
            keyPassword = "neutralproxy-debug-only"
        }
        if (releasePropsFile.exists()) {
            create("release") {
                storeFile = file(releaseProps.getProperty("storeFile"))
                storePassword = releaseProps.getProperty("storePassword")
                keyAlias = releaseProps.getProperty("keyAlias")
                keyPassword = releaseProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // A project-local debug key is generated in keys/ and is never read
            // from the user's shared ~/.android/debug.keystore.
            signingConfig = signingConfigs.getByName("projectDebug")
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // libbox is gomobile JNI; shrinking would need exhaustive keep
            // rules and buys little next to the native library size.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

// Some AndroidX transitive deps still pull the pre-1.8 kotlin-stdlib-jdk7/jdk8
// jars, which duplicate classes now inside kotlin-stdlib (same fix as the ERP app).
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity:1.9.3")
    implementation("com.google.android.material:material:1.12.0")
    // Export gate: per-use Keystore signature behind BiometricPrompt (same as Chris Secure).
    implementation("androidx.biometric:biometric:1.1.0")
    // In-app QR scanning and QR generation (same scanner as the ERP app).
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    // sing-box libbox supplies the actual TUN/protocol data plane.
    implementation("com.github.singbox-android:libbox:1.14.0")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM tests; android.jar only ships stubs.
    testImplementation("org.json:json:20240303")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    // Espresso still requests Hamcrest 1.3; pin the cached, API-compatible 2.2
    // artifact so offline/reproducible builds do not fetch the legacy jar.
    androidTestImplementation("org.hamcrest:hamcrest-library:2.2")
}
