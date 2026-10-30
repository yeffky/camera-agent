# Proposal: 取景页实时预览风格预设

## Why

当前预设主要在拍摄后处理，用户按快门前无法判断风格效果。主流相机和 VSCO 等产品把风格选择放在取景阶段；Camera Agent 也已确定拍前预览为目标体验。

## What Changes

- 在相机取景页呈现当前预设及低干扰的预设切换入口。
- 预设切换时实时更新取景效果，拍摄输出继续应用相同配方与强度。
- 保留拍后强度微调和已有预设页。
- 添加清晰的过渡反馈，避免遮挡构图。

## Impact

- Affected specs: `preset-preview`, `camera-experience`
- Affected code: camera preview rendering, preset state, capture/post-processing path.
- Depends on: existing local `PhotoPresetStore` and `PhotoColorProcessor`.
