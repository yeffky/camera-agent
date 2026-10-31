# Proposal: 拍后轻量预设微调

## Why

当前拍摄结果预览可比较成片和原片，但按快门后无法调整预设强度。产品方向要求拍后提供轻编辑，让用户保存前快速减弱或加强风格，同时避免扩展成复杂修图器。

## What Changes

- 在拍摄结果预览中提供当前预设强度滑杆，并立即预览调整结果。
- 调整从拍摄源（AI 流程时为裁剪后的源图）重新生成，不叠加压缩误差。
- AI 裁剪照片只允许保存裁剪范围内的版本；结果页不提供全幅原片。
- 保存时将最终使用的强度同步为本地该预设的后续默认强度。

## Impact

- Affected specs: `photo-review`
- Affected code: capture result preview, background image processor, preset store.
- Depends on: current local preset pipeline and AI crop output.
- Excludes: HSL 区域、蒙版、图层、RAW 和第三方分享模板编辑器。
