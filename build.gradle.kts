import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinSerialization)
    `maven-publish`
    signing
}

group = "io.github.phylaris"
// -Pversion 命令行属性优先（CI 发布时由 workflow 传入），本地构建缺省用快照版本
version = providers.gradleProperty("version").getOrElse("0.1.0-SNAPSHOT")

kotlin {
    withSourcesJar()

    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // JVM target: mainly serves as a test host and for desktop consumers.
    jvm("jvm") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "WebDavClient"
            isStatic = true
        }
    }

    // Browser targets: compiled to JavaScript / WebAssembly, using the fetch API.
    js {
        browser()
        binaries.library()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.library()
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.ktor.client.core)
            api(libs.ktor.client.auth)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.xmlutil.core)
            implementation(libs.xmlutil.serialization)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
        }
        jsMain.dependencies {
            implementation(libs.ktor.client.js)
        }
        // ktor-client-js carries both the Kotlin/JS and Kotlin/Wasm variants;
        // Gradle picks the matching one per target.
        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
        }
    }
}

android {
    namespace = "com.phylaris.webdav.client"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("WebDAV Client KMP")
            description.set("Multiplatform WebDAV client for Kotlin")
            url.set("https://github.com/phylaris/KMP-WebDAV")
            licenses {
                license {
                    name.set("Apache-2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0")
                }
            }
            developers {
                developer {
                    id.set("phylaris")
                    name.set("phylaris")
                }
            }
            scm {
                connection.set("scm:git:git@github.com:phylaris/KMP-WebDAV.git")
                developerConnection.set("scm:git:git@github.com:phylaris/KMP-WebDAV.git")
                url.set("https://github.com/phylaris/KMP-WebDAV")
            }
        }
    }
    repositories {
        maven {
            name = "MavenCentral"
            url = uri("https://central.sonatype.com/api/v1/publisher")
            credentials {
                username = providers.gradleProperty("sonatype.username").orNull ?: ""
                password = providers.gradleProperty("sonatype.password").orNull ?: ""
            }
        }
    }
}

signing {
    setRequired({
        providers.gradleProperty("signing.privateKey").isPresent
            && providers.gradleProperty("signing.password").isPresent
    })
    val privateKey = providers.gradleProperty("signing.privateKey").orNull ?: ""
    val passphrase = providers.gradleProperty("signing.password").orNull ?: ""
    if (privateKey.isNotBlank() && passphrase.isNotBlank()) {
        useInMemoryPgpKeys(privateKey, passphrase)
        sign(publishing.publications)
    }
}
