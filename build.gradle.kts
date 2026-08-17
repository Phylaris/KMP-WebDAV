import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinSerialization)
    // Vanniktech 插件内部会应用 maven-publish 插件，并接管 Central Portal 的 bundle 上传
    alias(libs.plugins.mavenPublish)
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

// Central Portal 发布：非 SNAPSHOT 版本先发布到本地 staging 目录，build 结束时插件
// 打包成 bundle zip 上传 portal（POST /api/v1/publisher/upload），随后可在
// central.sonatype.com 的 Deployments 页面手动 Publish（默认不自动发布）。
// 凭据来自 Gradle 属性 mavenCentralUsername / mavenCentralPassword（Portal User Token 的两段）。
mavenPublishing {
    publishToMavenCentral()
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
    // 不再手动配置远程 repository：Central Portal 已移除 per-file PUT 端点（OSSRH 已于
    // 2025-06-30 关闭），发布目标由 Vanniktech 插件统一管理。
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
