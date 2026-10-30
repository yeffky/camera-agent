# Proposal: 云端 AI 取景裁剪与拍摄

## Why

当前 Agent 可返回构图提示、变焦、曝光和风格建议，但不能指定画面裁剪区域，也不能完成用户确认的“AI 调整后拍摄”流程。产品已确认让云端 AI 分析用户主动提交的当前画面，裁掉干扰物、自动应用支持的基础参数，并由用户选择手动或自动快门。

## What Changes

- 云端响应增加归一化裁剪区域和可读说明。
- 客户端验证裁剪范围、响应时效和当前设备能力后展示/应用建议。
- 用户可选择确认构图后按快门，或显式选择 AI 调整后自动拍照。
- 输出照片只保存裁剪版本。
- 断网和服务错误退回普通相机并提供明确提示。

## Impact

- Affected specs: `ai-framing`, `camera-experience`, `photo-output`
- Affected code: Agent response parser/request prompt, camera preview overlay, crop mapping, capture/post-process/save flow.
- Depends on: user-triggered single-frame cloud request, CameraX basic exposure/zoom, local preset pipeline.
- Excludes: continuous image upload, arbitrary model commands, advanced Camera2 controls, retaining the uncropped original.
