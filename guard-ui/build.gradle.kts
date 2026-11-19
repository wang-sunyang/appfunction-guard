import org.gradle.api.publish.maven.MavenPublication

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

android {
    namespace = "io.github.yangyang2857.afguard.ui"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            // Robolectric 起 Activity 要能读主题与资源
            isIncludeAndroidResources = true
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

val javadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
    from(rootProject.file("docs/JAVADOC.md"))
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "guard-ui"
                artifact(javadocJar)
                pom {
                    name.set("AppFunction Guard :: ui")
                    description.set("Reference confirmation dialog for AppFunction Guard: lifecycle aware, timeout safe, no deadlock on main thread")
                    url.set("https://github.com/wang-sunyang/appfunction-guard")
                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }
                    developers {
                        developer {
                            id.set("wang-sunyang")
                            name.set("Wang Sunyang")
                            url.set("https://github.com/wang-sunyang")
                        }
                    }
                    scm {
                        connection.set("scm:git:git://github.com/wang-sunyang/appfunction-guard.git")
                        developerConnection.set("scm:git:ssh://github.com/wang-sunyang/appfunction-guard.git")
                        url.set("https://github.com/wang-sunyang/appfunction-guard")
                    }
                }
            }
        }
    }
}

dependencies {
    api(project(":guard-core"))

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
