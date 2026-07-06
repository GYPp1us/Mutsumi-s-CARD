# 记忆卡片

安卓端记忆卡片工具。卡片的 key 是文字，value 是图片。当前版本提供原生 Android MVP、GitHub CI 和 APK 发布流程。

## 第一版能力

- 学习页以浮动卡片展示随机推荐。
- 支持“记不住 / 模糊 / 记住了”反馈，并配套推荐权重核心逻辑。
- 横屏大屏使用左侧导航，竖屏窄屏使用底部导航。
- 卡片页以条目方式展示卡片。
- 绘制页支持触控笔或手指绘制。
- 备份模块提供 ZIP 导出核心逻辑和页面入口。

## 本地运行

```powershell
gradle :app:assembleDebug
```

## 测试

```powershell
gradle :app:testDebugUnitTest
```

## 发布

推送 `v*` 标签会触发 GitHub Actions 构建 APK，并将 `mutsumi-card-debug.apk` 上传到 GitHub Release。
