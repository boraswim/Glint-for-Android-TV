import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

// The release signing key lives in local.properties (git-ignored); a -P flag wins over it.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.reader(Charsets.UTF_8)?.use { load(it) }
}
fun setting(name: String, default: String) =
    ((findProperty(name) as String?) ?: localProps.getProperty(name, default)).trim()

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.glint.player"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.glint.player"
        minSdk = 24
        targetSdk = 36
        versionCode = 7
        versionName = "1.4.1"
        // Mi Box S is 32-bit ARM; arm64 covers newer boxes. Skipping x86 keeps libVLC's size down.
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }

    // Release key from local.properties (release.storeFile and friends); without one, the debug key signs,
    // so anyone can build and install from source.
    val releaseKey = setting("release.storeFile", "").takeIf { it.isNotEmpty() && file(it).exists() }
    signingConfigs {
        if (releaseKey != null) create("release") {
            storeFile = file(releaseKey)
            storePassword = setting("release.storePassword", "")
            keyAlias = setting("release.keyAlias", "")
            keyPassword = setting("release.keyPassword", "")
        }
    }

    buildTypes {
        release {
            // Compose debug builds are slow on TV boxes, so even test installs use the R8 release build.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (releaseKey != null) "release" else "debug")
            // The native libraries' symbol tables go into the bundle, so Play can name the functions in native
            // crash and ANR reports (it warns when they're missing).
            ndk { debugSymbolLevel = "SYMBOL_TABLE" }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += listOf("META-INF/DEPENDENCIES", "META-INF/*.md", "META-INF/versions/**")
        jniLibs.useLegacyPackaging = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("com.hierynomus:smbj:0.14.0")
    // Lists the shares on a NAS (SRVSVC NetShareEnum), so nobody has to type share names with a remote.
    implementation("com.rapid7.client:dcerpc:0.12.13")
    // Other network sources: WebDAV (OkHttp), FTP/FTPS, SFTP, NFS v3.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("commons-net:commons-net:3.11.1")
    implementation("com.github.mwiede:jsch:0.2.20")
    implementation("com.emc.ecs:nfs-client:1.1.0")
    implementation("org.videolan.android:libvlc-all:3.7.7")
    // libvlc-all brings fragment 1.0.0 through legacy-support-v4, which Play Console flags as outdated.
    implementation("androidx.fragment:fragment:1.8.6")
    // Dolby Vision: libVLC 3 can't hand DV to the TV's Dolby Vision decoder; Media3 can. Used only for DV
    // files on TVs that have one. Jellyfin's FFmpeg build decodes TrueHD, which Android has no decoder for.
    implementation("androidx.media3:media3-exoplayer:1.9.0")
    implementation("androidx.media3:media3-ui:1.9.0")
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1")
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test>().configureEach {
    System.getProperty("paths")?.let { systemProperty("paths", it) }
}
