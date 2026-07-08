plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val releaseStoreFilePath = providers.environmentVariable("MUTSUMI_RELEASE_STORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("MUTSUMI_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("MUTSUMI_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("MUTSUMI_RELEASE_KEY_PASSWORD").orNull
val releaseSigningReady = listOf(
    releaseStoreFilePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "com.mutsumi.card"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mutsumi.card"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "0.3.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (releaseSigningReady) {
                storeFile = file(requireNotNull(releaseStoreFilePath))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

tasks.register("checkReleaseSigning") {
    doLast {
        check(releaseSigningReady) {
            "Release 签名缺少环境变量：MUTSUMI_RELEASE_STORE_FILE、MUTSUMI_RELEASE_STORE_PASSWORD、MUTSUMI_RELEASE_KEY_ALIAS、MUTSUMI_RELEASE_KEY_PASSWORD"
        }
        check(file(requireNotNull(releaseStoreFilePath)).exists()) {
            "Release keystore 文件不存在：$releaseStoreFilePath"
        }
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn("checkReleaseSigning")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.truth:truth:1.4.5")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
