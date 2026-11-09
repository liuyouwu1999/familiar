import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    // Kotlin 2.0 起 compose compiler 以独立插件分发（版本 = Kotlin），取代 composeOptions.kotlinCompilerExtensionVersion
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// 正式签名配置：密钥库与密码在本机 keystore.properties（不入 git）
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "dev.liuyouwu.familiar"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.liuyouwu.familiar"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.0.0.0"
        // 资源收缩（审计 P6）：仅保留中/英，第三方库携带的其余 locale 资源不打包
        resourceConfigurations += listOf("zh", "en")
        // androidTest 基建：不声明则测试 APK 落到旧版 android.test.InstrumentationTestRunner，
        // JUnit4 用例报 "No tests found"（仅依赖 androidx.test:runner 不够）
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            // 审计 P3：开启资源收缩（R8 裁剪未引用资源）
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        // 调试构建也用正式签名：允许直接覆盖安装真机上的 release 版本而不丢数据
        debug {
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    // 迁移测试读 schema 快照（MigrationTestHelper 按 assets 里的 17.json 校验迁移结果）
    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

dependencies {
    // Compose BOM：2024.10.01 = compose 1.7.5 + material3 1.3.1 配套（Kotlin 2.0 批次，审计 E3）。
    // 历史：2024.01.00（material3 1.1.2 + 被 lifecycle 链抬上来的 animation-core 1.6.0）
    // 会在 CircularProgressIndicator 上 NoSuchMethodError（at() 签名 1.6 变更），故当时升到 2024.02.02
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // collectAsStateWithLifecycle（审计 P3：后台停止空转收集与 Room 观察）
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose UI
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Room（2.7 起 room-ktx 并入 room-runtime 成空壳，官方建议移除，不再单独声明）
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // OkHttp
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)

    // Gson
    implementation(libs.gson)

    // Coil for image loading
    implementation(libs.coil.compose)

    // PDF rendering
    implementation(libs.pdfbox.android)

    // Markdown rendering
    implementation(libs.compose.markdown)

    // Unit tests
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.okhttp.tls)
    // Dispatchers.setMain（审计 A2：Chat/ChatList ViewModel JVM 构造测试——viewModelScope
    // 走 Main.immediate，无 setMain 则构造即崩）
    testImplementation(libs.kotlinx.coroutines.test)

    // androidTest：MigrationTestHelper（room-testing）+ AndroidJUnitRunner（instrumentation
    // 运行必需；实测仅上 classpath 不够，还须 defaultConfig 声明 testInstrumentationRunner）
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.runner)
}

// Room schema 快照：迁移测试与 schema 演进追踪依赖它
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
