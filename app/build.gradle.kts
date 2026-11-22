import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// ============================================================
// 打包参数（通过 -P 传入，缺省值见下）
//   -PsignMode=demo|custom|none    默认 none
//   -PversionCode=210              默认 1
//   -PversionName=2.1.0            默认 1.0
// ============================================================
val signMode: String = providers.gradleProperty("signMode").orNull ?: "none"
val versionCodeArg: Int? = providers.gradleProperty("versionCode").orNull?.toIntOrNull()
val versionNameArg: String? = providers.gradleProperty("versionName").orNull

// 自定义签名属性来源：命令行 -P > keystore.properties
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signProp(key: String): String? =
    providers.gradleProperty(key).orNull ?: keystoreProps.getProperty(key)

fun resolveKeystoreFile(path: String?): File? {
    if (path.isNullOrBlank()) return null
    val f = File(path)
    return if (f.isAbsolute) f else rootProject.file(path)
}

android {
    namespace = "top.witzzz.moonbazaar"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "top.witzzz.moonbazaar"
        minSdk = 29
        targetSdk = 37
        versionCode = versionCodeArg ?: 1
        versionName = versionNameArg ?: "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // ============================================================
    // 多 ABI 分包
    // ============================================================
    flavorDimensions += "abi"

    productFlavors {
        create("arm_all") {
            dimension = "abi"
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        }
        create("arm64") {
            dimension = "abi"
            ndk { abiFilters += listOf("arm64-v8a") }
        }
        create("arm32") {
            dimension = "abi"
            ndk { abiFilters += listOf("armeabi-v7a") }
        }
        create("x8664") {
            dimension = "abi"
            ndk { abiFilters += listOf("x86_64") }
        }
    }

    // ============================================================
    // 签名配置
    //   demo   : signature/example.jks（演示，硬编码）
    //   custom : keystore.properties 或 -P 参数
    //   none   : 不签名（产出 *-unsigned.apk）
    // ============================================================
    signingConfigs {
        create("demo") {
            storeFile = rootProject.file("signature/example.jks")
            storePassword = "example@123."
            keyAlias = "example"
            keyPassword = "examp1e"
        }

        if (signMode == "custom") {
            create("custom") {
                val sf = resolveKeystoreFile(signProp("storeFile"))
                    ?: throw GradleException(
                        "signMode=custom 但未找到签名文件！\n" +
                                "请配置 keystore.properties 或使用 -PstoreFile= 参数"
                    )
                storeFile = sf
                storePassword = signProp("storePassword") ?: ""
                keyAlias = signProp("keyAlias") ?: ""
                keyPassword = signProp("keyPassword") ?: ""
            }
        }
    }

    buildTypes {
        release {
            signingConfig = when (signMode) {
                "demo"   -> signingConfigs.getByName("demo")
                "custom" -> signingConfigs.getByName("custom")
                else     -> null
            }
            optimization {
                enable = false
            }
        }

        debug {
            // 仅 debug 变体额外带上模拟器可用的 ABI（x86_64 / x86）。
            // 原因：flavor 按 ABI 分包后，若 IDE 选中 arm32Debug，
            // 在 x86_64 模拟器上会因 ABI 不兼容而无法安装，导致无法直接调试。
            // release 不受影响，仍严格按 flavor 的 ABI 分包。
            ndk { abiFilters += listOf("x86_64", "x86") }
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

// ---------------------------------------------------------------------------
// Android Studio Gradle Sync 兼容用占位任务
//
// AGP 9 使用「内置 Kotlin」编译 Kotlin：不能再应用 org.jetbrains.kotlin.android
// （会与内置注册的 kotlin 扩展冲突：Cannot add extension with name 'kotlin'），
// 而内置 Kotlin 只在根项目注册 prepareKotlinBuildScriptModel，
// 于是 Android Studio 在 :app 上请求该任务时报：
//   Task 'prepareKotlinBuildScriptModel' not found in project ':app'
//
// 这里补一个空实现，让 IDE 的 Sync 能正常完成。
// ---------------------------------------------------------------------------
tasks.register("prepareKotlinBuildScriptModel") {
    group = "build setup"
    description = "Android Studio Gradle Sync 兼容占位任务（AGP 9 内置 Kotlin 不注册该任务）"
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.okhttp)
    implementation(libs.androidx.browser)
    testImplementation(libs.junit)
    // 单元测试跑在 JVM 上，android.jar 里的 org.json 只是抛 "Stub!" 的壳；
    // 引入真实实现才能测 /home/mb_token 的 JSON 解析。
    testImplementation("org.json:json:20240303")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
// ============================================================
// 打包产物收集
//   把所有 APK / AAB 统一复制到 <项目根>/build/dist
//   用法：gradlew collectDist
//   或： 任意 assembleXxx / bundleXxx 完成后自动触发
// ============================================================
val distDir = rootProject.layout.buildDirectory.dir("dist")

tasks.register("collectDist") {
    group = "build"
    description = "收集所有 APK / AAB 到 <项目根>/build/dist"

    doLast {
        val dst = distDir.get().asFile
        dst.mkdirs()

        val outputsRoot = layout.buildDirectory.dir("outputs").get().asFile
        if (!outputsRoot.exists()) {
            logger.warn("[collectDist] 未找到 outputs 目录: ${outputsRoot.absolutePath}")
            logger.warn("[collectDist] 请先执行一次 assembleXxx / bundleXxx")
            return@doLast
        }

        val collected = mutableListOf<String>()
        outputsRoot.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in listOf("apk", "aab") }
            .forEach { src ->
                val dest = File(dst, src.name)
                src.copyTo(dest, overwrite = true)
                val sizeKb = src.length() / 1024
                collected += "${src.name}  (${sizeKb} KB)"
            }

        if (collected.isEmpty()) {
            logger.warn("[collectDist] outputs 目录下没有 APK / AAB")
        } else {
            logger.lifecycle("[collectDist] 已收集 ${collected.size} 个产物 → ${dst.absolutePath}")
            collected.forEach { logger.lifecycle("  • $it") }
        }
    }
}

// assemble*/bundle* 完成后自动触发收集
afterEvaluate {
    tasks.matching {
        (it.name.startsWith("assemble") && it.name != "assemble") ||
                (it.name.startsWith("bundle")   && it.name != "bundle")
    }.configureEach {
        finalizedBy("collectDist")
    }
}