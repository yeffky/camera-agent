# Android UI 组件调研与界面优化路线

> 调研日期：2026-10-30。以当前 Kotlin Views + CameraX 项目为基线。

## 当前界面观察

- CameraX 取景页、相机动作与辅助视图都在 `MainActivity` 中由代码创建；这让相机状态保持直接，但导航、预设、模型设置和业务逻辑耦合在一起。
- 当前底部一级导航只有文字，选中态区分较弱；页面切换没有状态过渡。
- 模型设置把连接参数、请求动作、隐私说明、最近建议和请求预览放在连续表单里，长页面中不容易快速定位。
- 相机页已经有低干扰玻璃卡片与取景层级，优化要保持快门为主操作、AI 请求显式触发、低频控制不遮挡预览。

## 开源 Android UI 组件

| 方案 | 可复用组件 | 当前适配 | 维护情况与建议 |
|---|---|---|---|
| [Material Components for Android](https://github.com/material-components/material-components-android) | `BottomNavigationView`、`MaterialCardView`、`TextInputLayout`、Slider、Bottom Sheet | 最适合当前 Views 代码，渐进替换简单；开源许可为 Apache 2.0 | 官方已声明 Views 版进入维护模式，不再计划功能版本。可做迁移桥接，新增大规模 UI 不建议押长期路线 |
| [Jetpack Compose Material 3](https://developer.android.com/develop/ui/compose/designsystems/material3) | Navigation Bar、Card、Button、Slider、Sheet、主题系统 | 适合新页面和逐步迁移；可与现有 Android View/CameraX 互操作 | Android 官方当前主推方向。建议未来优先用于预设和设置页面；取景层迁移要独立验证相机生命周期与预览表现 |
| AndroidX 原生 Views | `RecyclerView`、`ConstraintLayout`、`SeekBar`、`TextInputLayout` 需外部库 | 与当前代码最一致，无需整体迁移 | 长期适合底层 View 容器和 CameraX 互操作，但需要自己维护较多视觉细节 |

## 推荐路线

1. **本轮：**不新增 UI 依赖，先把一级 Tab 做成带图标、文字和明确选中态的导航条，为页面切换加短淡入；把模型设置按连接、拍摄建议、最近结果和请求预览分组。保持 CameraX `PreviewView` 及拍摄逻辑不变。
2. **下一轮：**将模型设置页拆为独立可复用 UI/状态模块，补充输入校验即时反馈、API Key 显隐、加载/失败/已保存状态和无障碍标签。
3. **后续技术试点：**在不迁移相机预览的前提下，用 `ComposeView` 单独实现一个设置或预设页面，比较交互、无障碍、代码量和性能；试点结果通过后再扩展 Compose。
4. **视觉方向：**取景页继续采用暗色沉浸式画布；表单/预设页使用统一的卡片间距、层级、触控尺寸和状态色。AI 建议用轻量卡片展示“理由 + 改动 + 应用结果”，避免整屏弹窗打断取景。

## 官方资料

- [Material Components for Android 仓库及维护状态](https://github.com/material-components/material-components-android)
- [Material 3 in Jetpack Compose](https://developer.android.com/develop/ui/compose/designsystems/material3)
- [Compose 与现有 Views 互操作](https://developer.android.com/develop/ui/compose/migrate/interoperability-apis)
- [Material Components Bottom Navigation 指南](https://github.com/material-components/material-components-android/blob/master/docs/components/BottomNavigation.md)
