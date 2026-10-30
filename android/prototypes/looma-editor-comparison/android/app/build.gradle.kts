plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "edu.hust.looma.comparison"
    compileSdk { version = release(37) { minorApiLevel = 0 } }
    defaultConfig {
        applicationId = "edu.hust.looma.comparison"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "prototype-2"
    }
    flavorDimensions += "engine"
    productFlavors {
        create("cm") {
            dimension = "engine"
            applicationIdSuffix = ".cm"
            buildConfigField("String", "EDITOR_KIND", "\"CodeMirror View\"")
            resValue("string", "app_name", "Looma · CodeMirror")
        }
        create("native") {
            dimension = "engine"
            applicationIdSuffix = ".native"
            buildConfigField("String", "EDITOR_KIND", "\"Kotlin Compose\"")
            resValue("string", "app_name", "Looma · Compose")
        }
    }
    buildFeatures { compose = true; buildConfig = true; resValues = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.03.01"))
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3:1.5.0-beta02")
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.39.2")
    "cmImplementation"("androidx.webkit:webkit:1.10.0")
}
