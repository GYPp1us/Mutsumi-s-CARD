# Mutsumi's CARD

Mutsumi's CARD 是一个原生 Android 记忆卡片工具。卡片保留 `key` 文字机制，学习内容以图片为主；录入时可以使用触控笔绘制，也可以使用双面 Markdown 生成图片卡片。

当前版本：`v0.7.0`；正式发布记录见 GitHub Releases。

## 产品能力

- 学习页使用无阴影实体浮动卡片，随机推荐卡片。
- 左右拖动连续翻转正反面；上下拖动提交“记住了”或“忘记了”。
- 横屏使用左侧导航和宽屏工作区，竖屏使用底部导航。
- 卡片页按条目展示卡片，并提供卡片选择和卡组上下文。
- 录入页支持触控笔、手指绘制、预设与自定义 RGB 画笔颜色、笔刷大小、撤销、清空、底图以及无限画布视口。
- 绘图保存结果为银行卡比例 PNG，底图等比适配，不拉伸、不裁切。
- 双面录入支持正面和背面并行编辑；用卡面中的灰色背景标识保存状态，空正面会明确提示将降级为文字 key；key 可一键锁定。
- 本地备份支持 ZIP 导入导出；云端支持 WebDAV 滑动窗口增量同步、版本预览、恢复和三方冲突解决（保留本地或采用云端）。
- AI 批量录入是三层工作流：素材选择、拆分/拼接节点和结果预览；支持递归导入笔记库、按标题/段落/定长切分、自由组合并生成候选卡片。
- 设置页可自动检查 GitHub Release，支持可选自动下载；安装始终由 Android 系统确认。
- Release APK 使用固定签名证书，可由 Obtainium 自动追踪 GitHub Release 更新。

## 响应式布局

- 竖屏：底部导航；进入录入页时应用会自动切换横屏。
- 横屏：左侧导航、中间主要工作区、右侧上下文区域；绘图工具栏与画布并行放置。
- 录入页强制横屏，使用图标侧边工具栏和双面并行画布。
- 卡片和图片统一使用竖向银行卡比例 `1024×1624`，Compose 展示比例为 `53.98f / 85.60f`。
- 旧版 `1024×2048` 图片只做兼容读取，并使用 FitCenter 显示。

## 本地构建

要求：JDK 21、Android SDK 36、Build Tools 36.0.0。Windows 上通常可通过 `ANDROID_HOME` 指向 `%LOCALAPPDATA%\Android\Sdk`。

优先使用仓库内 Gradle Wrapper：

```powershell
.\gradlew.bat :app:assembleDebug
```

运行本地发布前门禁：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest :app:assembleDebug
```

## ADB 调试

查看在线设备：

```powershell
adb devices
```

构建并安装当前 Debug APK：

```powershell
.\gradlew.bat :app:assembleDebug
adb -s <设备序列号> install -r app\build\outputs\apk\debug\app-debug.apk
adb -s <设备序列号> shell am force-stop com.mutsumi.card
adb -s <设备序列号> shell monkey -p com.mutsumi.card 1
```

## CI 与发布

向 `main` 推送或创建针对 `main` 的 Pull Request 会运行 Android CI：单元测试、Lint、AndroidTest APK 编译、Debug 构建，并上传 Debug APK 工件。

推送 `v*` 标签，或手动触发 Release workflow，会执行：

1. 使用 Java 17、Android SDK 36 和仓库 Gradle Wrapper。
2. 使用 GitHub Secrets 写入 Release keystore。
3. 运行单元测试、Lint、AndroidTest APK 编译，并构建 `app-release.apk`。
4. 使用 `apksigner` 精确验证 APK signer SHA-256 与历史证书一致。
5. 发布 `mutsumi-card-release.apk` 和对应的 `.sha256` 文件。

Release 必须保持 applicationId `com.mutsumi.card`，并使用历史签名证书，否则 Android 增量更新会失败。签名只允许通过 GitHub Secrets 注入，不得提交 keystore、密码或 API Key。

最新 Release：<https://github.com/GYPp1us/Mutsumi-s-CARD/releases/latest>

Obtainium 配置 GitHub 仓库：

```text
https://github.com/GYPp1us/Mutsumi-s-CARD
```

选择 Release APK 资产 `mutsumi-card-release.apk`，即可自动检查新版本。

## 数据与安全

- Room 保存卡组、卡片和复习状态；DataStore 保存应用设置和 AI 配置；应用私有目录保存 PNG。
- AI API Key 只保存在 DataStore，不进入备份、云端快照、日志或异常文本。
- 云同步使用稳定 UUID 和图片 SHA-256；复习状态会同步，但不计入差异统计和卡片组数量。
- 当同一稳定 ID 相对同步基线（首次同步视为空快照）被本地和云端同时改动时，会在写入前列出冲突；选择策略后以同一侧解决冲突，避免破坏卡组、卡片和复习状态关联。
- 自动更新只查询公开 GitHub Release；更新下载完成后仍需 Android 的安装确认。
- 导入、导出、同步、恢复、保存和删除失败必须显示具体错误，不得静默处理。
- 构建、测试和临时验证产物不得提交。

## 目录概览

```text
app/src/main/java/com/mutsumi/card/
  backup/       本地备份和 WebDAV 同步
  cards/        卡片组列表和卡片条目
  domain/       复习、持久化和工作流模型
  draw/         绘图、底图、Markdown 和 PNG 导出
  study/        随机推荐和实体卡片手势
  ui/           自适应布局、导航、主题和图片显示
docs/           产品设计、响应式布局和实施计划
```

更具体的代理协作约束和设计事实来源见 [AGENTS.md](AGENTS.md)。
