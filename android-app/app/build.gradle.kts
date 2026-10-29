import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.tiaomabang.labelreprint"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tiaomabang.labelreprint"
        minSdk = 34                  // TC73 = Android 14
        targetSdk = 36
        versionCode = 20
        versionName = "2.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        create("releaseTest") {
            val f = rootProject.file("keystore.properties")
            if (f.exists()) {
                val p = Properties().apply { f.inputStream().use { load(it) } }
                storeFile = file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            } else {
                storeFile = File(System.getProperty("user.home"), ".android/debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug { isMinifyEnabled = false }
        release {
            isMinifyEnabled = false      // 先不混淆：后面要接 Zebra SDK(反射/回调)，封版前再开
            signingConfig = signingConfigs.getByName("releaseTest")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    androidResources {
        // ONNX 模型与字典必须原样进 APK：压缩后再解压会让 ORT 多一次 MB 级拷贝，
        // 首次建 session 明显变慢（另一个工程实测）。
        noCompress += listOf("onnx", "txt")
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs {
            useLegacyPackaging = false
            // AI-Data-Capture-SDK 与 OpenCV 各自带了一份 libc++_shared.so，合并时冲突。
            // 两边都是同一个 C++ 运行时（C++ 运行时是 ABI 稳定的），留一份即可；不允许两份
            // 共存会导致 mergeDebugNativeLibs 直接失败。
            pickFirsts += "**/libc++_shared.so"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    val camerax = "1.5.3"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // 条码/二维码：zxing-cpp 官方 Android 封装（Apache-2.0，自带 4 个 ABI 的 natives，无 GMS 依赖）
    implementation("io.github.zxing-cpp:android:3.1.1")

    // 文字 OCR：PP-OCRv4（det+rec+cls）走 ONNX Runtime；几何后处理（DB 阈值/轮廓/minAreaRect/
    // unclip/透视裁剪）走 OpenCV —— 与 PC 端 tools/ppocr_ref.py 用的是同一套算法和同一批模型，
    // 端侧才能和参考实现对齐。OpenCV 也用于 label 基准的标签四边形检测。
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.0")
    implementation("org.opencv:opencv:4.11.0")

    // SDK 的 AAR 自带 3 个 demo 布局（layout_controls 等）用了 ConstraintLayout 属性。
    // 走本地 AAR 时拿不到它的 POM，传递依赖不会自动带进来 -> 必须显式声明，
    // 否则 AAPT 报 "attribute layout_constraintStart_toEndOf not found"。
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")

    // Zebra AI Data Capture SDK + DL 条码模型。走本地 AAR：
    // Zebra 的 Artifactory(zebratech.jfrog.io) 要凭据，本地 AAR 免登录、版本可控。
    // 体积提示：模型资产 147MB(barcode-decoder) + natives 143MB(SDK) —— APK 会到 ~300MB，
    // 只装 TC73 这类 Zebra 机（非 Zebra 机上 AIVisionSDK.getInstance 会抛 UnsupportedOperationException）。
    // AI Data Capture SDK：**4.0.6，不是 4.1.0**。
    // 4.1.0 里的 AIVisionSDK 在本机（TC201L / QCS6690 / Android 15）构造时直接抛
    // `UnsupportedOperationException: AI Vision SDK is not supported`，Zebra 自家 App 也崩；
    // 换回 4.0.6 就能初始化成功（条码模型仍是 barcode-decoder 5.0.4）。
    implementation(files("libs/AI-Data-Capture-SDK-4.0.6.aar"))
    implementation(files("libs/barcode-decoder-5.0.4.aar"))

    // 打印：Zebra Link-OS Multiplatform SDK 2.14（不在 Maven Central，jar 手工放 libs/）。
    // 与 voice-print-android 同一份 jar，那边已在 TC73 上跑通实机打印。
    implementation(files("libs/ZSDK_ANDROID_API.jar"))

    // ZSDK 自己不带运行时依赖，缺失时会在**连接成功后读状态/型号**的瞬间抛
    // "Failed resolution of: Lcom/fasterxml/jackson/databind/ObjectMapper;"，
    // 极易误判成打印机故障（状态/型号/SGD 全走 JSON）。必须显式加这三个。
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("com.fasterxml.jackson.core:jackson-core:2.17.2")
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.17.2")
}
