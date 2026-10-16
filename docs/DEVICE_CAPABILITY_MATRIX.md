# 设备能力验证矩阵

用应用中的“设备能力报告”在每台真机复制报告内容，填入本表。矩阵记录的是公开 Camera2/CameraX 能力，不代表厂商原生相机的全部能力。

| 厂商 / 型号 | Android | Camera ID / 等级 | 变焦范围 | 曝光补偿范围 | 手动曝光/ISO | 手动白平衡 | AF 最小对焦距离 | 闪光灯 | JPEG 最大尺寸 | 预览/拍照验证 |
|---|---|---|---|---|---|---|---|---|
| Google sdk_gphone64_x86_64（Medium_Phone_API_37.0） | Android 17 / API 37 | ID 10 / LIMITED | 1.00×–1.00×，不支持 | -3.00…+3.00 EV，0.50 EV 步长 | 不支持/未报告 | 不支持/未报告 | 0.10 diopter | 不支持 | 1280×960 | 预览与拍照通过；自然、暖调照片已保存 |
| 待采集：品牌真机 1 |  |  |  |  |  |  |  |  |  |  |
| 待采集：不同厂商真机 2 |  |  |  |  |  |  |  |  |  |  |

## 采集说明

1. 安装 MVP 后打开相机并等待预览就绪。
2. 点击“设备能力报告”，检查 Camera ID 与型号是否对应后置相机。
3. 复制报告并填写矩阵。
4. 实际拖动变焦滑杆、点击曝光按钮并拍摄，记录报告与实际行为是否一致。
5. 若应用报告能力但控制没有生效，记录设备型号、系统版本、Camera ID 和复现步骤。

可先用 PowerShell 运行 `scripts/verify-mvp.ps1 -AndroidSdkRoot $env:ANDROID_SDK_ROOT -BuildOnly` 验证构建与最终 APK 权限。连接设备后传入 `-DeviceSerial`，脚本会安装、授权相机、启动应用、检查进程和崩溃日志并保存截图；场景、参数和拍摄效果仍需按下方清单手动记录。

| 厂商 / 型号 | 系统版本 | Camera ID | 现象 / 复现步骤 | 结果 |
|---|---|---|---|---|
| 待验证 |  |  |  |  |

### 已执行的模拟器记录

- Camera2：后置 Camera ID `10`，硬件等级 `LIMITED`；闪光灯不支持；最小对焦距离报告为 `0.10` 屈光度。
- 模式：AF `OFF, AUTO`；AE `OFF (manual), ON`；AWB `OFF (manual), AUTO`。
- ZoomState：`1.00×–1.00×`，应用禁用变焦并显示不支持。
- 曝光：索引 `-6…6`、步长 `0.50 EV`、范围 `-3.00…+3.00 EV`；按“曝光 +”后 UI 从 `0 EV` 更新到 `+0.5 EV`。
- 权限：撤销权限后重新启动显示系统权限框；选择拒绝后应用仍运行并显示“需要相机权限才能启动取景”；恢复授权后预览恢复。
- 拍照：至少一张自然和一张暖调 JPEG 保存到 `Pictures/CameraAgent`；暖调样张已拉取并目视确认有暖色变化。
- 网络权限：此处记录的是手动云端请求接入前的 APK，清单当时不含 `INTERNET` / `ACCESS_NETWORK_STATE`；飞行模式下预览/分析状态和拍照保存通过。现行 APK 需要 `INTERNET` 用于用户手动触发的云端请求，`ACCESS_NETWORK_STATE` 仍已移除。
- 场景：模拟器虚拟客厅被报告为“通用场景 · 未分类”；这只验证未分类状态显示，不能代表分类准确度。
- 运行：最终 APK 安装成功，应用进程存活；本轮过滤的 logcat 未发现 Camera Agent 的 `FATAL EXCEPTION` 或 ANR。模拟器冷启动期间出现过 Pixel Launcher 系统 ANR 对话框，停止桌面进程后相机应用继续运行。

## 可调参数能力采集表

CameraCharacteristics 报告值表示 API 声明的能力，不表示当前应用已接入控制。对每台真机分别记录“API 报告、应用是否已接入、实拍是否生效”。Camera2Interop 能覆盖 CameraX request 配置，但官方文档提示互操作选项可能覆盖 CameraX 内部配置并导致意外行为，因此每个参数都要按机型验证。

| 参数组 | 重点探测项 | API/依赖能力 | 当前应用状态 | 真机记录 |
|---|---|---|---|---|
| 变焦/镜头 | min/max zoom、逻辑/物理镜头切换 | CameraX ZoomState、CameraCharacteristics | 变焦已接入；物理镜头选择未接入 |  |
| 曝光 | EV 补偿范围/步长；曝光时间范围、ISO 范围、帧时长 | CameraX ExposureState；手动传感器需 MANUAL_SENSOR | EV 补偿已接入；时间/ISO 仅报告或未完整探测 |  |
| 对焦/测光 | AF 模式、最小对焦距离、AF/AE/AWB regions、focus distance | CameraCharacteristics 与控制区域数；手动镜头控制受能力约束 | 人脸对焦/测光建议已有；任意手动焦距未接入 |  |
| 白平衡/颜色 | AWB 模式、手动色彩校正矩阵/增益 | MANUAL_POST_PROCESSING、AWB 可用模式/色彩校正能力 | 能力报告部分已有；控制未接入 |  |
| 闪光灯 | 是否有闪光灯、AE flash 模式 | CameraInfo.hasFlashUnit、AE modes | 基本报告已有；建议动作/选项需验证 |  |
| ISP/画质 | 色调映射、降噪、锐化、镜头畸变校正、色差校正、边缘增强模式 | 各自 available modes；设备与 Android 版本相关 | 未接入 |  |
| 稳定/输出 | OIS、视频稳定、JPEG quality、输出尺寸/格式、可用动态范围 | Lens/Scaler/Request characteristics | JPEG 尺寸部分报告；其余未接入 |  |

不要只记录“支持/不支持”。分别记录请求参数、CaptureResult 实际返回值、预览/成片主观差异和失败行为；如果设备报告支持但请求不生效，记为该机型不兼容并保留安全默认值。

模拟器证据文件在项目 `build/` 目录：`cameraagent-screen-final.png`、`cameraagent-capability-report.png`、`cameraagent-permission-denied.png`、`cameraagent-offline-test.png` 和 `cameraagent-warm-test.jpg`。这些证据仅代表 Android 37 模拟器。

新增脚本的设备路径已在 Android 37 模拟器执行：`verify-mvp.ps1 -DeviceSerial emulator-5554` 完成 APK 构建、权限检查、安装、相机授权、启动与日志检查；进程存活且未发现 Camera Agent 的致命异常/ANR，截图保存在 `build/verification/cameraagent-emulator-5554.png`。这验证脚本流程，不计作真实手机或多厂商兼容性结果。

## 阶段 E 真机验收清单

- [ ] 至少两台不同厂商的 Android 真机，记录型号、Android 版本和应用版本。
- [ ] 首次启动分别验证相机权限允许和拒绝；拒绝后无崩溃，授权后预览可用。
- [ ] 复制设备能力报告，核对 Camera ID、变焦范围、曝光范围及 JPEG 输出尺寸。
- [ ] 操作变焦与曝光，确认实际预览/成片响应与报告一致；不支持时控件应禁用并提示。
- [ ] 在人像、风景、美食、低照度场景检查识别、建议和置信度展示；记录误分类及建议闪动。
- [ ] 开启本地自动调整，观察曝光和人脸测光对焦是否工作；关闭后检查曝光恢复。
- [ ] 分别拍摄自然和其他影调，确认照片出现在 `Pictures/CameraAgent` 且可正常打开。
- [ ] 记录分析用例不可用、拍摄失败或处理失败时的降级提示。

## 云端多模态 Agent 验收清单

- [ ] 在 Agent 页确认服务地址/模型支持 OpenAI-compatible `image_url` 输入；默认 DeepSeek `deepseek-flash` 支持该格式。
- [ ] 仅在手机应用内填写 API Key；确认页面说明点击后会上传一张压缩取景帧及相机摘要到该服务。
- [ ] 手动请求成功后，检查模型描述能对应画面里的主体、构图位置、光线或颜色，而不只是重复端侧场景标签。
- [ ] 当前版本验证曝光、变焦、色彩建议确认弹窗和设备范围处理；后续直应用版本需验证无二次确认、逐项状态准确、撤销恢复正确。
- [ ] 前一请求结束后立即再次手动请求，确认无客户端固定间隔；请求进行中不应并发发送。
- [ ] 断网/服务错误时确认相机预览、端侧分析和拍照仍正常；记下 HTTP 错误或模型提示，不记录 API Key。
- [ ] 在至少两家不同厂商真机重复上述关键步骤，记录机型、系统版本、模型/服务商、图像请求结果与能力差异。

请勿把包含 API Key 或私人取景画面的截图/日志直接共享；验收报告记录结论与错误码即可。
