import org.gradle.api.publish.maven.MavenPublication

plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

kotlin {
    jvmToolchain(17)
}

java {
    withSourcesJar()
}

val javadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
    from(rootProject.file("docs/JAVADOC.md"))
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "guard-ksp"
            artifact(javadocJar)
                pom {
                    name.set("AppFunction Guard :: ksp")
                    description.set("KSP processor generating argument validators and metadata for @Guard annotated AppFunctions")
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

dependencies {
    implementation(libs.ksp.api)
}
