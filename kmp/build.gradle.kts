// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.androidx.room3) apply false
}
// 统一从仓库根目录 prompts/ 同步提示词到 Android assets 与 Compose Resources，避免多份副本漂移。
val rootPromptsDir = layout.projectDirectory.dir("../prompts")
val promptSubdirs = listOf("chapters", "dialogue", "distill", "review")

val syncPromptsToAssets by tasks.registering(Sync::class) {
    promptSubdirs.forEach { sub ->
        from(rootPromptsDir.dir(sub)) {
            into(sub)
            include("**/*.yaml", "**/*.md")
        }
    }
    into(layout.projectDirectory.dir("server/src/androidMain/assets"))
}

val syncPromptsToComposeResources by tasks.registering(Sync::class) {
    promptSubdirs.forEach { sub ->
        from(rootPromptsDir.dir(sub)) {
            into(sub)
            include("**/*.yaml", "**/*.md")
        }
    }
    into(layout.projectDirectory.dir("app/shared/src/commonMain/composeResources/files/prompts"))
}

val syncPrompts by tasks.registering {
    dependsOn(syncPromptsToAssets, syncPromptsToComposeResources)
}

subprojects {
    if (path == ":server") {
        tasks.matching { it.name.contains("Assets") || it.name == "preBuild" }.configureEach {
            dependsOn(syncPromptsToAssets)
        }
    }
    if (path == ":app:shared") {
        tasks.matching {
            it.name.contains("ComposeResources") || it.name.contains("Resource") || it.name == "preBuild"
        }.configureEach {
            dependsOn(syncPromptsToComposeResources)
        }
    }
}
