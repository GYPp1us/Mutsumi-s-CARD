# 记忆卡片

安卓端记忆卡片工具。卡片的 key 是文字，value 是图片。当前版本为 `v0.6.1`，提供原生 Android MVP、GitHub CI 和已签名 APK 发布流程。

## 第一版能力

- 学习页以可翻面的实体浮动卡片展示随机推荐，支持横向翻面、上滑记住、下滑忘记。
- 支持“记不住 / 模糊 / 记住了”反馈，并配套推荐权重核心逻辑。
- 横屏大屏使用左侧导航，竖屏窄屏使用底部导航。
- 卡片页以条目方式展示卡片。
- 录入页仅横屏，支持正反两面并行绘制、笔刷、橡皮、无限画布移动缩放、底图、Markdown 和 PNG 成品保存。
- 备份模块支持 ZIP 导入导出与 WebDAV 滑动窗口增量备份；云端恢复前校验快照和图片对象。

## 本地运行

```powershell
.\gradlew.bat :app:assembleDebug
```

## 测试

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:connectedDebugAndroidTest
```

## 发布

推送 `v*` 标签会触发 GitHub Actions 构建、验证历史签名证书，并将 `mutsumi-card-release.apk` 与 SHA-256 文件发布到 GitHub Release。该 APK 可被 Obtainium 追踪更新。
