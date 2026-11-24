plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin { jvmToolchain(17) }

application {
    mainClass.set("io.github.wangsunyang.afguard.mcp.McpMainKt")
    applicationName = "guard-mcp"
}

// 判定逻辑只有一份。
//
// guard-core 是 Android library（AGP），guard-mcp 是纯 JVM，两者无法直接依赖。
// 这里把 guard-core 里不碰 android.* 的源文件编进本模块：PolicyEngine / RateLimiter
// 与 Android 侧跑的是同一份 .kt，不是抄过来的第二份实现。剩下那些 import
// android.os.* 的文件逐个排除，排除列表就是"哪些东西 Android-only"的清单。
val coreKotlinDir = rootProject.layout.projectDirectory.dir("guard-core/src/main/kotlin")

sourceSets {
    main {
        kotlin {
            srcDir(coreKotlinDir)
            exclude(
                // android.os.Bundle / Binder / SystemClock / Context 依赖
                "io/github/wangsunyang/afguard/AfGuard.kt",
                "io/github/wangsunyang/afguard/BundleCodec.kt",
                "io/github/wangsunyang/afguard/DefaultCallerResolver.kt",
                "io/github/wangsunyang/afguard/GuardedCall.kt",
                "io/github/wangsunyang/afguard/GuardConfig.kt",
                "io/github/wangsunyang/afguard/internal/Auditor.kt",
                "io/github/wangsunyang/afguard/internal/FunctionMeta.kt",
                "io/github/wangsunyang/afguard/internal/Redactor.kt",
                "io/github/wangsunyang/afguard/internal/ValidatorRegistry.kt"
            )
        }
    }
}

dependencies {
    // GuardError.toJson() 用 org.json。Android 上它是平台自带的；纯 JVM 上必须显式引入。
    // 这是本项目唯一一个"额外"依赖，只落在 guard-mcp，不进任何 AAR。
    implementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    // 协议层的行式读写测试不需要 JVM 参数，保持默认即可
    testLogging { events("failed") }
}
