# 造梦（个人自用 APK 版）

这是 [wkbin/zaomeng](https://github.com/wkbin/zaomeng) 的个人自用重构：**只保留 Android APK 一条交付路径**。
业务功能、共享 UI、内嵌 Ktor + Room 后端与提示词资源都保持原样，删掉的是与手机端使用无关的部分。

## 这个版本改了什么

| 项 | 上游 | 本仓库 |
| --- | --- | --- |
| 交付端 | Android / 桌面 / iOS | 仅 Android APK |
| 桌面入口 `kmp/app/desktopApp` | 有 | 已删除 |
| iOS Xcode 工程 `kmp/iosApp` | 有 | 已删除 |
| 旧 Python 后端与 Web UI（`src/`、`tests/`、`scripts/` 等） | 保留作基线 | 已删除 |
| `zaomeng-skill`、官网 `website/`、示例插件 | 有 | 已删除 |
| 应用内更新 | 检查并下载作者 GitHub Release 的 APK | **已移除**（见下） |
| 桌面 / iOS / Pages / 旧 Python CI | 有 | 已删除，只保留 Android CI |
| 版本号 | `2.1.2` / code 11 | `2.1.4-personal` / code 15 |

打包进 APK 的提示词资源在 `kmp/app/shared/src/commonMain/composeResources/files/prompts` 与
`kmp/server/src/androidMain/assets`，两者仍需保持一致；仓库根的 `prompts/` 只是可编辑源目录。

## 小说人物识别

导入 TXT / EPUB 后，已配置模型且开启“导入后立即蒸馏”时，会自动识别主要人物并回填名单；可以手动增删，失败后可点“识别小说人物”重试。识别只使用开头、中间、末尾等位置的有界正文取样，最多建议 12 位人物，不保证覆盖全书所有配角。仅保存到书架时不会自动调用模型。已导入的书卷可在“继续蒸馏”页点击同一入口，从当前正文识别人物。

## 为什么删掉应用内更新

原版更新器把 `api.github.com/repos/wkbin/zaomeng/releases/latest` 写死，并只允许从
`github.com/wkbin/zaomeng/releases/download/` 下载安装包。个人自用版由自己签名，作者签名的 APK
无法覆盖安装，检查更新只会把安装引向失败。因此连同 `REQUEST_INSTALL_PACKAGES` 权限、
更新用 FileProvider 与相关界面一起移除；升级靠手动安装新包。

## 环境要求

- JDK 21（Gradle daemon 与 Kotlin/Java 目标都是 21）
- Android SDK：`compileSdk 37`、`targetSdk 36`、`minSdk 24`，以及对应 build-tools
- 首次在本机构建前，需要让 Gradle 找到 SDK：

```bash
# 二选一：环境变量，或 kmp/local.properties（该文件已被 git 忽略）
export ANDROID_SDK_ROOT=/path/to/android-sdk
# kmp/local.properties
# sdk.dir=/path/to/android-sdk
```

## 签名（只做一次）

发布包必须用固定 keystore 签名，换签名会导致无法覆盖安装、也无法读取旧数据。
仓库**不会**保存签名文件；请在 `kmp/` 下自建（两项都已 gitignore）：

```bash
cd kmp
keytool -genkeypair -v -keystore personal-release.jks -alias zaomeng \
  -keyalg RSA -keysize 2048 -validity 10000 -storetype PKCS12
cat > keystore.properties <<'EOF'
storeFile=personal-release.jks
storePassword=换成你的密码
keyAlias=zaomeng
keyPassword=换成你的密码
EOF
```

`kmp/app/androidApp/build.gradle.kts` 检测到 `keystore.properties` 时才配置 release 签名；
没有该文件时 `assembleRelease` 产出的是未签名包，无法安装。请把 `personal-release.jks` 和密码
另外备份一份：丢了就无法再用新包覆盖安装。

## 构建 APK

```bash
cd kmp

# 自用发布包（签名 + R8 压缩）
./gradlew :app:androidApp:assembleRelease

# 调试包（applicationId 带 .test 后缀，可与正式包共存）
./gradlew :app:androidApp:assembleDebug
```

产物：

- `kmp/app/androidApp/build/outputs/apk/release/app-release.apk`
- `kmp/app/androidApp/build/outputs/apk/debug/app-debug.apk`

校验签名与内容：

```bash
$ANDROID_SDK_ROOT/build-tools/<版本>/apksigner verify --print-certs \
  kmp/app/androidApp/build/outputs/apk/release/app-release.apk
$ANDROID_SDK_ROOT/build-tools/<版本>/aapt2 dump badging \
  kmp/app/androidApp/build/outputs/apk/release/app-release.apk | head
```

## 测试

```bash
cd kmp
./gradlew :app:androidApp:testDebugUnitTest :app:androidApp:lintDebug
./gradlew jvmTest                      # 服务端/存储/解析等 JVM 回归测试
```

JVM 目标只用于跑这些回归测试，不产出可分发产物。

## 升级与版本号

每次出包前同时递增 `kmp/app/androidApp/build.gradle.kts` 里的 `versionCode`（必须严格递增，
否则无法覆盖安装）和 `versionName`；`kmp/ui/shared/.../AppMetadata.kt` 的
`VERSION_NAME`/`VERSION_CODE` 是「应用与支持」页展示用的副本，需同步修改。

## 已知限制

- 2.0.0 起与 1.5.0 及更早版本的数据不兼容；从旧版迁移要先在旧版导出书卷包（`.zaomeng-run.zip`）。
- 换 applicationId 或签名等于换一个应用，旧数据不会跟随。
- APK 未按 ABI 拆分，包含全部原生库；如需减小体积可自行加 `ndk.abiFilters`，但会放弃其它架构设备。
- 上游工程质量守则仍在 `AGENTS.md`；其中与桌面/iOS/旧 Python 相关的条目已随代码删除同步更新。

## 许可证

上游为 AGPL-3.0，详见 `LICENSE`。自用改造不改变许可证义务。
