plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.looma"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.looma"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
            optimization {
                enable = true
                packageScope = setOf("androidx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.markdown.renderer.m3)
    implementation(libs.ratex.compose)

    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

/**
 * 字号、行高、字重只允许出现在角色定义（ui/theme）和从角色推导的比例（ui/markdown）里。
 * 页面自己写这些值，就是在和 token 层分叉，而分叉不会有人发现。
 */
val verifyTypography = tasks.register("verifyTypography") {
    group = "verification"
    description = "Fails when a screen hardcodes a font size, weight or family instead of using LoomaTokens."
    val sourceDir = file("src/main/java/com/example/looma")
    val exempt = listOf("ui/theme/", "ui/markdown/")
    inputs.dir(sourceDir)
    doLast {
        val forbidden = listOf(
            "fontSize =",
            "lineHeight =",
            "fontWeight =",
            "fontFamily =",
            "FontWeight.",
            "TextStyle(",
        )
        val offenders = mutableListOf<String>()
        sourceDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val relative = file.relativeTo(sourceDir).invariantSeparatorsPath
                if (exempt.none { relative.startsWith(it) }) {
                    file.readLines().forEachIndexed { index, line ->
                        val code = line.substringBefore("//")
                        if (forbidden.any { code.contains(it) }) {
                            offenders += "$relative:${index + 1}  ${line.trim()}"
                        }
                    }
                }
            }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "字体值只能写在 ui/theme/LoomaTokens.kt 里，下面这些地方自己写了：\n" +
                    offenders.joinToString("\n"),
            )
        }
    }
}

tasks.matching { it.name == "check" }.configureEach { dependsOn(verifyTypography) }
