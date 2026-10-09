import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.canglan.cyancodex"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        // 共存版: 仅 applicationId 加 ".com" 后缀, 与官方版并存安装。
        // namespace 保持 "com.canglan.cyancodex" 不变 —— Java 包名与安装包名解耦,
        // 这样插件 API (com.canglan.cyancodex.plugin.*) 仍在原位置,
        // plugin-sdk 编译出的官方插件 JAR 可直接被本版加载。
        applicationId = "com.canglan.cyancodex.com"
        minSdk = 26
        targetSdk = 36
        // 版本规则: 每次更新 versionName 加 0.0.1 (1.0.11 -> 1.0.12 -> 1.0.13 ...)
        // versionCode = major*10000 + minor*100 + patch (必须随更新递增)
        versionCode = 20000
        versionName = "2.0.0"
    }

    signingConfigs {
        create("release") {
            val props = Properties()
            val f = rootProject.file("keystore.properties")
            if (f.exists()) {
                f.inputStream().use { props.load(it) }
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // 代码加固: R8 混淆 (规则见 proguard-rules.pro), 防解包逆向
            optimization {
                enable = true
            }
            // 体积: 移除未被引用的资源。前置条件是 R8 已开启(见上面的 optimization)。
            // 本工程**没有** getIdentifier / getResourceEntryName 之类的动态资源查找
            // (已全仓 grep 确认), 所以 shrink 不会误删运行时才引用的资源。
            // 实测可删掉 okhttp 的 publicsuffixes.gz(41 KB, 其宿主类已被 R8 裁掉) 等。
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (rootProject.file("keystore.properties").exists())
                signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    // 体积: 只保留中英文资源。
    // 依赖库(androidx / compose / material3)自带 80+ 个语言的字符串与排版资源,
    // 全部合并进 resources.arsc 是纯粹的体积浪费 —— 本 App 面向中文用户。
    // 实测: arsc 从 112,988 B 降到 13,660 B (省 99 KB)。
    // ★ 若将来要做多语言, 记得把对应 locale 加回来, 否则该语言会回落到默认(英文)。
    // ⚠️ 用 localeFilters, 不要用 defaultConfig.resourceConfigurations ——
    //    AGP 9 起后者会打 deprecation 警告, 官方已声明将移除。
    androidResources {
        localeFilters += listOf("zh", "en")
    }

    packaging {
        resources {
            // 体积: 剔除运行时用不到的元数据。
            // ⚠️ 这里**故意不动** META-INF 下的 LICENSE.txt —— 那是 Apache-2.0 要求的
            //    许可证副本, 省下的十几 KB 不值得冒合规风险。
            excludes += setOf(
                // kotlinx-coroutines 的调试探针, 只在 IDE attach 调试器时被读
                "DebugProbesKt.bin",
                // okhttp 的公共后缀表 (41 KB)。它是 Java 资源 —— shrinkResources 管不到
                // (那只处理 res/ 下的 Android 资源), 只能用 packaging 过滤。
                // 安全性依据: 其唯一宿主类 okhttp3.internal.publicsuffix.PublicSuffixDatabase
                // 已被 R8 裁掉(见 usage.txt), 且本工程未配置 CookieJar, 不存在读取路径。
                "okhttp3/internal/publicsuffix/publicsuffixes.gz",
                // androidx 各库的版本标记, 运行时无人读
                "META-INF/*.version",
                // AGP 生成的 VCS 信息
                "META-INF/version-control-info.textproto",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // 离线环境无 lint-gradle 32.3.1 缓存, 关闭 release lint 检查
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation("androidx.compose.material:material-icons-extended:1.6.8")

    // 液态玻璃 (Liquid Glass) —— 开源库 Kyant0/AndroidLiquidGlass, Apache-2.0, Maven Central
    //
    // ⛔ 版本必须锁 2.0.0 / 1.2.0, 不要升到 2.0.1 / 1.2.1 !!
    //    2.0.1 与 2.0.0 的**公开 API 完全相同**(已用 javap 逐个核对: PlatformKt 的
    //    isRuntimeShaderSupported / RuntimeShaderKt 的 RuntimeShader+asComposeShader /
    //    shapes 的 Capsule 构造签名 —— 一字不差)。2.0.1 唯一的变化是**改用 Kotlin 2.4.0 重新编译**,
    //    于是 META-INF/*.kotlin_module 的元数据版本从 2.3.0 变成 2.4.0。
    //    而本工程用的是 Kotlin 2.2.10, 最多只能读 2.3.0 的元数据 →
    //    升上去会得到满屏 "Module was compiled with an incompatible version of Kotlin"
    //    以及一堆由 kotlin-stdlib 2.4.10 被拖进来引发的假报错(Unresolved reference 'runCatching' 等)。
    //    查元数据版本字节: kotlin_module 头部第 4 个 u32, 3=2.3.0 / 4=2.4.0。
    implementation("io.github.kyant0:backdrop:2.0.0")
    // 官方高层组件用的形状库: com.kyant.shapes.Capsule() —— 连续曲率(Continuous)胶囊。
    // backdrop 自己也依赖它, 但那是 implementation 不对外暴露, 我们要直接用 Capsule 所以显式声明。
    // 同上: 锁 1.2.0。
    implementation("io.github.kyant0:shapes:1.2.0")

    debugImplementation(libs.androidx.compose.ui.tooling)
}
