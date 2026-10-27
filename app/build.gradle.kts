import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Cœurs natifs (AAR précompilés) : Xray via AndroidLibXrayLite + tun2socks via hev-socks5-tunnel.
val coreAar = layout.projectDirectory.file("libs/libv2ray.aar")
val hevAar = layout.projectDirectory.file("libs/hev-socks5-tunnel.aar")
val coreVersion = "v26.10.21"
val hevVersion = "2.19.3"

val fetchDeps = tasks.register("fetchDeps") {
    group = "build"
    description = "Télécharge les cœurs Xray (libv2ray) et hev-socks5-tunnel si nécessaire"
    val targets = mapOf(
        coreAar.asFile to "https://github.com/2dust/AndroidLibXrayLite/releases/download/$coreVersion/libv2ray.aar",
        hevAar.asFile to "https://github.com/heiher/hev-socks5-tunnel/releases/download/$hevVersion/hev-socks5-tunnel.aar"
    )
    outputs.files(targets.keys)
    doLast {
        targets.forEach { (target, url) ->
            if (target.length() > 1024) return@forEach
            target.parentFile?.mkdirs()
            println("Téléchargement de ${target.name}…")
            val temp = File(target.parentFile, target.name + ".part")
            URI(url).toURL().openStream().use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        }
    }
}

android {
    namespace = "com.dems.vpn"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dems.vpn"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(files(coreAar.asFile))
    implementation(files(hevAar.asFile))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

tasks.named("preBuild").configure {
    dependsOn(fetchDeps)
}
