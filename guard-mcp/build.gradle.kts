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
// 这里把 guard-core 里既不碰 android.*、也不碰 org.json 的源文件编进本模块：
// PolicyEngine / RateLimiter / GuardErrorCode 与 Android 侧跑的是同一份 .kt，
// 不是抄过来的第二份实现。排除列表就是"哪些东西只能活在 Android 上"的清单，
// 也正是本模块零第三方依赖的边界。
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
                // org.json：Android 平台自带，纯 JVM 上没有。GuardErrorCode 已拆到独立文件，
                // 本模块只需要那个枚举，不需要这里的 GuardError.toJson()
                "io/github/wangsunyang/afguard/GuardResult.kt",
                "io/github/wangsunyang/afguard/internal/Auditor.kt",
                "io/github/wangsunyang/afguard/internal/FunctionMeta.kt",
                "io/github/wangsunyang/afguard/internal/Redactor.kt",
                "io/github/wangsunyang/afguard/internal/ValidatorRegistry.kt"
            )
        }
    }
}

dependencies {
    // 协议层只用 JDK 自带类型：JSONObject / JSONArray 见本模块的 mcp/Json.kt。
    // 保持零第三方运行时依赖，这是 guard-mcp 能作为单文件分发二进制的前提。
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    // 协议层的行式读写测试不需要 JVM 参数，保持默认即可
    testLogging { events("failed") }
}
