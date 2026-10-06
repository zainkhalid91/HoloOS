import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    android {
        namespace = "com.zain.holoos.shared"
        compileSdk = 37
        minSdk = 29
        compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
        androidResources { enable = true }
    }

    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.compose.runtime)
            api(libs.compose.foundation)
            api(libs.compose.ui)
            api(libs.compose.animation)
            implementation(libs.compose.resources)
        }
        getByName("desktopMain") {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
            }
        }
    }
}

compose.resources {
    packageOfResClass = "com.zain.holoos.res"
}

compose.desktop {
    application {
        mainClass = "com.zain.holoos.MainKt"
        jvmArgs += listOf(
            "-Dskiko.renderApi=DIRECT3D",
            "-Dskiko.vsync.enabled=true",
            // Optimus laptops: render on the discrete GPU, not the integrated one.
            "-Dskiko.directx.gpu.priority=discrete",
            "-Dskiko.gpu.priority=discrete",
        )
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "HoloOS"
            packageVersion = "1.0.0"
        }
    }
}
