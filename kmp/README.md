# 造梦 Android 客户端（APK 自用版）

Compose Multiplatform 写成的 Android 应用，内嵌 Ktor + Room 后端，全部数据保存在本机，
无需部署服务器。桌面与 iOS 入口已从本仓库移除，只保留 APK 构建。

## 模块

```
kmp/
├── app/androidApp/     # Android 入口，产出 APK
├── app/shared/         # 导航、App、ViewModel/DI 装配与共享 Compose UI
├── core/{contracts,domain,runtime}
├── data/{remote,repository}
├── feature/*           # 各业务 Screen/ViewModel
├── ui/shared/          # 主题与跨平台 UI 辅助
├── server/             # 内嵌 Ktor：路由 / 服务 / 插件（含 storage、llm、http 子模块）
├── plugins-api/、builtin-plugins/
```

JVM 目标保留下来供 `jvmTest` 回归测试使用，不产出桌面分发包。

## 构建

需要 JDK 21 与 Android SDK（compileSdk 37 / targetSdk 36 / minSdk 24）。

```bash
# 签名配置见仓库根 README 的「签名」一节
./gradlew :app:androidApp:assembleRelease
./gradlew :app:androidApp:assembleDebug

# 检查
./gradlew :app:androidApp:testDebugUnitTest :app:androidApp:lintDebug
./gradlew jvmTest
```

APK 输出在 `app/androidApp/build/outputs/apk/{release,debug}/`。

## 提示词同步

内置提示词有两份，必须同步修改：

- `app/shared/src/commonMain/composeResources/files/prompts/**`
- `server/src/androidMain/assets/**`

开发时可用环境变量 `ZAOMENG_PROMPTS_DIR` 指向仓库根 `prompts/` 直接读文件，免去重新打包。
