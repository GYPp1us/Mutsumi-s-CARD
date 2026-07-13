# Mutsumi's CARD 开发代理约束

## 语言

- 所有提交信息、项目文档、PR/Release 说明和应用内可见文案使用中文。
- Kotlin 标识符遵循英文生态惯例。

## 当前目标

- 当前开发目标是完成并发布 `v0.4.0`。
- 当前重构分支：`codex/v0.4.0-refactor`。
- 当前隔离工作区：`.worktrees/v0.4.0-refactor`。
- 不要在主工作区的 `main` 上直接实现重构。
- 必须持续推进至 GitHub Release 包含已签名 APK；中间阶段完成不等于目标完成。

## 上下文恢复顺序

每次进入仓库或发生上下文压缩后，先完整读取：

1. `AGENTS.md`。
2. `docs/superpowers/specs/2026-07-11-前端响应式布局设计.md`，这是前端布局唯一事实来源。
3. `docs/superpowers/plans/2026-07-11-v0.4.0全面重构实施计划.md`，这是实施任务和验证门禁。
4. 当前分支的 `git status --short` 和 `git log --oneline -10`。

如果上述文件互相矛盾，按用户最新明确要求、前端布局契约、实施计划、旧代码的顺序裁决。若仍无法确定，暂停并向用户说明具体歧义。

## 不可回退的产品约束

- Android 原生：Kotlin + Jetpack Compose + Material 3。
- key 仅文字，value 仅图片。
- 所有新 value 图片和实体卡片采用竖向 ISO ID-1 银行卡比例 `1024×1624`，Compose `aspectRatio(53.98f / 85.60f)`；旧 `1024×2048` 图片仅兼容读取并 FitCenter 显示。
- 绘图视口背后是无边界世界坐标；相机不得限制位移或缩放，保存内容必须与当前银行卡比例视口一致。
- 图片 FitCenter，禁止拉伸和裁切。
- 竖屏使用底部导航；横屏宽度 `>=680dp` 使用左导航、中工作区、右上下文三栏。
- 目标尺寸为 `360×800`、`800×360`、`800×1280`、`1280×800`。
- 学习卡片无阴影、无透明虚影，正反面是一个刚体；物理渲染只接收中心和朝向。
- 学习手势圈半径为屏宽 `1/5`；纵向越圈后固定微倾，释放由位置和末速度共同判定，离场期间保持当前绝对朝向。背面纹理不得因起手轴变化产生平面内 `180°` 旋转。
- 新数据库无需迁移旧 `cards.json`；新安装仅创建空默认卡组，禁止示例数据。
- 开发阶段未知异常必须抛出，禁止空 `catch` 和静默失败。
- 保存、导入、导出、清空、归档、删除必须有明确反馈。
- Release 必须保持 applicationId `com.mutsumi.card`，并使用与历史 Release 相同的签名证书以支持覆盖安装。
- 历史 `v0.3.6` Release APK 的 signer SHA-256 为 `970be097332d65f5c7cab0cd867790f3d135ba0c03306e91d000cee7f1482481`；机器可读值位于 `release-signing-cert.sha256`。

## 架构边界

- Room 保存卡组、卡片、复习状态；DataStore 保存偏好；应用私有目录保存 PNG。
- Repository + Flow + ViewModel 驱动页面。不得把持久业务状态放在普通 `remember` 中。
- 图片写入使用临时文件、fsync、原子重命名；数据库失败时清理新图片。
- 绘图预览和 PNG 导出共享一个场景渲染器。
- 学习姿态使用 `CardPose(center, Quaternion)`，回弹使用中心非线性插值和四元数最短弧 SLERP。
- 备份使用 SAF ZIP；先完整验证临时文件，再事务导入为新副本。

## 构建环境

- AGP `9.2.1`，Kotlin `2.2.21`，KSP `2.2.21-2.0.5`，Gradle Wrapper `9.6.0`。
- 本机已验证 Java 21 和 Android SDK 36；CI 使用 Java 17。
- 优先使用仓库 Wrapper：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

- Wrapper 官方下载在部分本机网络可能受 GitHub CDN 影响；本机已有 Gradle 9.6.0 缓存。不要因此降低项目 Gradle 或 AGP 版本。
- `android.builtInKotlin=false` 和 `android.newDsl=false` 是 AGP 9.2 + 外部 Kotlin + KSP 的过渡兼容设置，当前版本保留。

## 工作方式

- 采用测试先行：先写失败测试并确认失败原因，再写最小生产实现。
- 每个阶段使用中文提交，避免把数据层、UI、物理引擎和发布门禁混在一个提交。
- 修改前先读取目标文件及其测试；不要依赖广泛搜索替代理解。
- 不得回滚用户或其他代理的已有修改。
- 每阶段至少运行最窄相关测试；提交前运行 `git diff --check`。
- 完成声明前必须按实施计划的“完成审计”逐项核验，不得用单一绿色构建替代全范围证据。

## 发布流程

- 合并前运行单元测试、lint、AndroidTest 编译、debug APK 构建；有设备/模拟器时运行 connected tests。
- Release workflow 必须验证 signer SHA-256 与历史签名一致，而不仅是“APK 有签名”。
- 推送 `v0.4.0` 标签后等待 GitHub Actions 成功，检查 Release APK 和 SHA-256 附件。
- 使用 `apksigner` 验证版本、证书；使用 `adb install -r` 或等价方式验证覆盖安装。
- Obtainium 应可从 GitHub Release 自动检测 `mutsumi-card-release.apk`。
