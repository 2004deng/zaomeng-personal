# AGENTS.md

本文件适用于仓库根目录及所有子目录，供自动化编码代理和协作者执行开发、测试与交付任务时使用。
若子目录存在更具体的 `AGENTS.md`，以距离目标文件最近的文档为准。

## 项目定位

造梦将中文小说人物蒸馏为人物包、关系和记忆，并提供多角色对话体验。

**本仓库是个人自用重构，只交付 Android APK。** 桌面端（`app/desktopApp`）、iOS 工程（`iosApp`）、
旧 Python/Web 实现、`zaomeng-skill` 包与官网均已删除，不要重新引入。

## KMP 模块

- `kmp/app/shared`：共享 UI、导航、ViewModel、展示层 DI 与平台入口。
- `kmp/app/androidApp`：Android 应用入口与打包配置（唯一交付物）。
- `kmp/core/contracts`：跨层 DTO、稳定契约和纯值类型。
- `kmp/core/domain`：领域 Gateway、UseCase 与不依赖 UI 的业务编排。
- `kmp/core/runtime`：嵌入式后端、端点、安全存储与流式 HTTP 等运行时抽象。
- `kmp/data/remote`：Ktor API client、SSE、在线书库下载、流式文件读写与 DataStore 偏好存储。
- `kmp/data/repository`：面向应用的 Repository 实现与 domain Gateway 适配。
- `kmp/ui/shared`：主题、跨平台 UI 辅助与共享展示模型。
- `kmp/feature/books`：书卷域（书架、在线书库、导入、继续蒸馏、原文知识、书卷详情）。
- `kmp/feature/dialogue`：对话域（聊天流式交互、会话列表与分页）。
- `kmp/feature/persona`：人物域（人物档案、人物关系、可复用卡片）。
- `kmp/feature/settings`：设置域（模型与应用设置、插件工坊）。
- `kmp/feature/studio`：创作域（章节、剧情回顾、时间线、跨作品共演）。
- `kmp/server`：内嵌 Ktor 聚合模块，保留路由、业务服务、插件宿主与平台入口。
- `kmp/server/storage`：Room、StorageService、PathSafety 与领域模型。
- `kmp/server/llm`：LlmClient、ModelApiKeyService、提示词构建与响应解析。
- `kmp/server/http`：HTTP 插件、SSE 编码器与基础路由。
- `kmp/plugins-api`：插件公共契约；保持向后兼容。
- `kmp/builtin-plugins`：内置插件实现。

依赖统一通过 `kmp/gradle/libs.versions.toml` 管理。新增依赖前先确认是否已有版本目录项，
不要在模块脚本中随意硬编码版本。JVM 目标保留用于 `jvmTest` 回归测试，不产出桌面分发包。

## 开发原则

1. 只维护 Android APK 与 KMP 共享实现；不要恢复桌面、iOS 或旧 Python 代码路径。
2. 业务逻辑尽量放在 `commonMain`。`commonMain` 不得直接引用 JVM、Android 专属 API；
   平台差异通过 `expect/actual` 或已有平台接口隔离。
3. 修改共享接口时检查现有 `actual` 实现（Android / JVM）。仓库内的 iOS `actual` 源码仍在，
   但不再纳入构建验证；改动共享 API 时顺手保持其语义一致即可。
4. 保持协程非阻塞。文件、数据库和网络阻塞操作应进入平台 IO dispatcher；
   不要在 UI 线程执行大文件解析。
5. 不要复制大型原文或生成型静态资源。优先使用内容寻址、缓存、索引或已有资源引用。
6. 对现有脏工作区保持克制：不要覆盖、还原或删除与当前任务无关的用户改动。

## 后端与存储

- 生产持久化统一经过 `StorageService`、`DocumentStore` 和 `DomainStore`，
  不要绕过它们直接操作应用数据库。
- 所有 run/session 标识必须经过 `PathSafety` 校验；禁止拼接未经验证的外部路径。
- 写入业务文档使用原子写接口。新增文档路径时确认 Room 文档同步、缓存失效和级联删除语义。
- 会话生成路径只读取有界的近期 transcript。完整历史通过归档/分页路径读取。
- 数据结构或 Room schema 变化必须提供迁移或明确的破坏性升级策略，并补充测试。不要静默丢弃用户数据。
- API 密钥只通过 `ModelApiKeyService` 和平台安全存储访问，禁止写入 manifest、日志、
  测试快照或仓库文件。

## 模型输出与流式对话

- 多角色对话的主协议是 NDJSON：每行一个完整对象，至少包含非空 `speaker` 与 `message`，
  可选 `inner_thought`。
- 对话解析器需兼容旧 JSON 数组和 `responses` 包装对象，但兼容逻辑是兜底，不应成为提示词主协议。
- NDJSON 整体不是单个 JSON 对象，因此对话请求不要同时声明 `response_format=json_object`。
- 流式链路必须从模型响应体到应用 UI 全程增量读取。禁止使用会把响应缓存到 EOF 的调用方式。
- 第一个可见 `message` 增量应立即进入 UI；后续可做很短的帧级合批以减少 Compose 重组。
- 不要记录完整模型输出、完整提示词、密钥或超长原文。诊断日志应使用长度限制，
  并记录阶段耗时、TTFT、重试次数等指标。

## 提示词同步

同机内提示词存在于两处，改一侧必须同步另一侧：

- `kmp/app/shared/src/commonMain/composeResources/files/prompts/**`
- `kmp/server/src/androidMain/assets/**`

仓库根 `prompts/` 是可编辑源目录，配合环境变量 `ZAOMENG_PROMPTS_DIR` 可在开发时直接读取。
结构化输出提示必须明确：输出形状、必填字段、是否允许 Markdown 围栏、是否允许前后缀文字。

## UI 与 API

- 网络 DTO 位于共享 API 模型中；服务端与客户端字段需要同步变更并保持合理默认值。
- `docs/server-api.md` 是当前 Ktor HTTP 接口文档；新增、删除或修改路由、请求/响应字段、
  认证、分页、流式事件或状态码时必须同步更新。
- 聊天完成事件优先返回轻量 session 和本轮增量，不要每次发送后重新传输完整 transcript。
- ViewModel 状态更新应保持幂等，尤其注意断线重试、operation ID、流式 reset/complete
  和历史分页合并。
- Compose 列表元素应使用稳定 key；避免在高频 delta 上进行昂贵的全列表重建。

## 版本与发布

- 应用内更新**已移除**：不要再加回指向上游 `wkbin/zaomeng` Release 的检查/下载逻辑，
  也不要把 `REQUEST_INSTALL_PACKAGES` 加回 manifest。升级靠手动安装新 APK。
- 出包前同步递增 `kmp/app/androidApp/build.gradle.kts` 的 `versionCode` 与 `versionName`，
  并同步 `kmp/ui/shared/src/commonMain/kotlin/top/wkbin/zaomeng/app/shared/AppMetadata.kt`。
- 签名文件 `kmp/keystore.properties` 与 `*.jks` 已被 gitignore，绝不能提交；
  换签名意味着无法覆盖安装，也不能读取旧数据。

## 验证命令

命令默认从 `kmp/` 目录运行。需要 JDK 21 与 Android SDK（compileSdk 37 / targetSdk 36 / minSdk 24）。

Android 检查与出包：

```bash
./gradlew :app:androidApp:testDebugUnitTest :app:androidApp:lintDebug :app:androidApp:assembleDebug \
  --no-daemon --stacktrace --console=plain
```

服务端/共享回归（JVM）：

```bash
./gradlew :server:storage:jvmTest :server:llm:jvmTest :server:http:jvmTest :server:jvmTest \
  :app:shared:jvmTest --no-daemon --stacktrace --console=plain
```

测试范围应与风险相称：解析器、存储、幂等、并发、提示词协议和 token 预算变更必须补回归测试。
提交前至少运行 `git diff --check`。

## 安全与仓库卫生

- 永远不要提交 `*.jks`、`*.keystore`、`keystore.properties`、`local.properties`、`.env*`、
  数据库、应用私有目录或 Gradle/IDE 缓存。
- 不要提交模型 API 密钥、签名密码、个人访问令牌或用户数据库导出。
- 不要用破坏性 Git 命令清理用户工作区。只在用户明确要求时创建提交、推送或改写历史。

## 完成标准

1. 实现落在 Android/KMP 主模块，未重新引入已删除的平台代码。
2. 跨模块契约同步，共享 API 的现有 `actual` 实现保持一致。
3. 关键失败路径有结构校验、边界处理和回归测试。
4. 相关测试与 `assembleDebug`/`assembleRelease` 通过；无法在本机运行的验证已明确说明。
5. 没有密钥、构建产物或大型重复资源进入版本控制。
