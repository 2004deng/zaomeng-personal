import org.jetbrains.kotlin.gradle.dsl.JvmTarget


plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// 对话域：聊天流式交互与会话列表/分页。
kotlin {
    jvm()

    android {
        namespace = "top.wkbin.zaomeng.feature.dialogue"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
        withHostTest {}
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:contracts"))
            implementation(project(":core:runtime"))
            implementation(project(":core:domain"))
            implementation(project(":data:remote"))
            implementation(project(":data:repository"))
            implementation(project(":ui:shared"))
            implementation(libs.bundles.compose.ui)
            implementation(libs.compose.lifecycle.runtime)
            api(libs.compose.lifecycle.viewmodel)
            implementation(libs.bundles.koin.compose)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.okio)
            implementation(libs.paging.common)
            implementation(libs.paging.compose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
