plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.mangashelf.reader"
    compileSdk = 34

    // ---------------------------------------------------------------------
    // 签名
    // 密钥随工程走：keystore/mangashelf.jks（见 版本记录.md）。
    // debug 和 release **用同一把钥匙**，所以：
    //   1. 换机器、换 Android Studio 重新构建，产出的包仍能覆盖升级；
    //   2. debug 包和 release 包可以互相覆盖，不会出现「应用未安装」。
    // 代价：这是自签名证书（CN=Android Debug），上架应用商店不被接受——自用侧载没问题。
    // ---------------------------------------------------------------------
    signingConfigs {
        create("app") {
            storeFile = rootProject.file("keystore/mangashelf.jks")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.mangashelf.reader"
        minSdk = 24
        targetSdk = 34
        versionCode = 10
        versionName = "1.5.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets {
        // 把导出的 schema JSON 挂进测试资源，MigrationTestHelper 要读它
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("app")
        }
        release {
            signingConfig = signingConfigs.getByName("app")
            // v1.3 开了 R8 之后在真机上闪退，先关掉压缩回滚（v1.3.1）。
            // 等崩溃堆栈拿到、根因定位后，再考虑重新开启。
            isMinifyEnabled = false
            isShrinkResources = false
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        // 与 Kotlin 1.9.24 严格配对的 Compose 编译器版本
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Room 的 schema 导出：迁移测试和以后写 Migration 的唯一依据
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    // 书架 / 阅读进度持久化
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // 漫画页面的磁盘图片加载与内存缓存
    implementation("io.coil-kt:coil-compose:2.6.0")

    // 读取图片的 EXIF 旋转信息
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // 文件夹选择（OpenDocumentTree）后遍历子目录
    implementation("androidx.documentfile:documentfile:1.0.1")

    testImplementation("junit:junit:4.13.2")

    // 仪器测试：跑真实迁移（v1→v2）与启动冒烟。仅测试期依赖，不进 APK。
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
