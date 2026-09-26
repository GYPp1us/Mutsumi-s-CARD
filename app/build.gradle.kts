plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
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

val splitApks = providers.gradleProperty("splitApks").map(String::toBoolean).orElse(false)

android {
    ndkVersion = "29.0.14206865"
    namespace = "com.mutsumi.card"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mutsumi.card"
        minSdk = 26
        targetSdk = 36
        versionCode = 29
        versionName = "0.8.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        if (!splitApks.get()) ndk {
            abiFilters += providers.gradleProperty("md2svgAbis")
                .orElse("arm64-v8a,armeabi-v7a,x86_64").get().split(",")
        }
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
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    splits {
        abi {
            isEnable = splitApks.get()
            reset()
            include(*providers.gradleProperty("md2svgAbis").orElse("arm64-v8a,armeabi-v7a,x86_64").get().split(",").toTypedArray())
            isUniversalApk = true
        }
    }
    // 下载包压缩原生库；安装时由 Android 解压，字体和排版功能保持完整。
    packaging { jniLibs { useLegacyPackaging = true } }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }

    sourceSets["androidTest"].assets.srcDir("$projectDir/schemas")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
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
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-savedstate:2.10.0")
    implementation("androidx.navigation:navigation-compose:2.9.7")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    ksp("androidx.room:room-compiler:2.8.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.truth:truth:1.4.5")
    testImplementation("androidx.test:core-ktx:1.7.0")
    testImplementation("androidx.room:room-testing:2.8.4")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.robolectric:robolectric:4.16.1")

    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.room:room-testing:2.8.4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

val rustRoot = rootProject.file("native/md2svg-jni")
val rustAbis = providers.gradleProperty("md2svgAbis").orElse("arm64-v8a,armeabi-v7a,x86_64")
val buildRustHost by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("cargo", "build", "--locked", "--manifest-path", "native/md2svg-jni/Cargo.toml")
    inputs.files(fileTree(rustRoot) { exclude("target/**") })
    outputs.dir(rustRoot.resolve("target/debug"))
}
val buildRustAndroid by tasks.registering(Exec::class) {
    workingDir(rustRoot)
    val targets = rustAbis.get().split(",")
    commandLine(listOf("cargo", "ndk") + targets.flatMap { listOf("-t", it) } + listOf(
        "--platform", "26", "-o", layout.buildDirectory.dir("generated/rustJniLibs").get().asFile.absolutePath,
        "build", "--release", "--locked",
    ))
    environment("ANDROID_NDK_HOME", providers.environmentVariable("ANDROID_NDK_HOME").orElse(
        android.sdkDirectory.resolve("ndk/${android.ndkVersion}").absolutePath,
    ).get())
    inputs.files(fileTree(rustRoot) { exclude("target/**") })
    inputs.property("abis", rustAbis)
    outputs.dir(layout.buildDirectory.dir("generated/rustJniLibs"))
}
android.sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("generated/rustJniLibs").get().asFile)
tasks.withType<Test>().configureEach {
    dependsOn(buildRustHost)
    systemProperty("java.library.path", rustRoot.resolve("target/debug").absolutePath)
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    dependsOn(buildRustAndroid)
}
