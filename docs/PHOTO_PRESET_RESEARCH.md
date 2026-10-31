# 影像预设与可调参数调研

> 更新时间：2026-10-16
> 用途：为 Camera Agent 的风格预设、后处理参数和后续相机控制扩展提供产品与 Android API 依据。本文件是规划，不代表这些能力已经实现。

## 1. 调研结论

成熟影像产品把预设设计为一组可调的编辑配方，而不是一个颜色滤镜开关。常见交互包括按风格/用途分类、缩略图即时预览、强度滑杆、收藏与自定义配方、套用后仍可逐项修改。

- VSCO 将预设作为可预览的风格包，并提供强度（Strength）等可调整项；Recipes 可保存一组预设与工具编辑，之后再次套用，也允许调整或删除其中单项。[VSCO 预设管理](https://support.vsco.co/en/articles/12698476-using-and-managing-presets)、[VSCO Recipes](https://support.vsco.co/en/articles/12698502-how-to-create-and-apply-a-recipe)
- Lightroom 将预设定义为曝光、颜色、对比和影调等调整的组合；用户可按分类浏览并用 Amount 滑杆控制整体强度，也支持基于照片的推荐预设和用户自定义预设。[Lightroom 移动端预设](https://helpx.adobe.com/lightroom/mobile/work-with-presets/apply-presets.html)、[创建自定义预设](https://helpx.adobe.com/uk/lightroom/mobile/work-with-presets/create-update-delete-presets.html)
- 美图秀秀官方资料按“复古胶片、美食摄影、东方美学”等偏好与场景介绍滤镜分类，并提到滤镜强度可调；其相机介绍也以清透人像、胶片风景、复古相机等风格说明拍前套用的产品路径。[美图秀秀滤镜介绍](https://www.meituxiuxiu.com/zh-Hans/academy/meitu-filters)、[美图秀秀相机介绍](https://www.meituxiuxiu.com/zh-Hans/academy/camera)

对 Camera Agent 的启示：按“用户想要的效果/场景”组织类别比只提供暖、冷、柔和等色彩属性更容易选择；同时要保留强度微调与可复用配方。预设可包含图像后处理配方，并可选携带设备支持时的采集参数偏好；Agent 推荐“复古胶片”时，必须落到明确、受限、可显示的预设 ID 与参数集合，不能让模型生成任意 LUT 或执行代码。

## 2. 建议的预设浏览体验

1. **先按目的进入**：推荐、胶片复古、电影感、霓虹赛博、人像、美食、旅行风景、黑白。类目描述结果观感，避免只用抽象色温词。
2. **卡片即预览**：显示当前画面的预览缩略图、名称和简短效果描述；可按住比较原图。应用前先预览，不要求通过云端请求才能用。
3. **整体强度**：选择预设后显示 0–100 的 Amount；内部对配方参数做有界插值。强度 0 回到原图，100 使用完整配方；若参数非线性，应针对各参数定义插值曲线。
4. **套用后可继续调**：预设是可编辑的起点，用户可调整曝光、色温、对比等；界面说明当前有未保存编辑。
5. **收藏与自定义**：首版提供收藏、最近使用；后续支持“复制为自定义预设”、命名、重排、删除。原厂预设只读。
6. **重置和撤销**：分别提供撤销最近一次 Agent 建议、恢复当前照片/会话默认、恢复原片；这三者语义要区分。

## 3. 风格库建议

首版建议控制在 8–10 个辨识度明确的风格。下面是产品方向和配方设计目标，不应误解为已校准的视觉效果；最终参数应通过多款手机、肤色和明暗场景样张调优。

| 类别 | 预设名 | 视觉方向 | 配方维度重点 | 风险/约束 |
|---|---|---|---|---|
| 基础 | 自然标准 | 保持场景真实、轻微优化 | 轻微对比、色彩校正 | 默认与强度 0 都需明确 |
| 胶片 | 暖色胶片 | 暖高光、柔和黑位、轻微颗粒 | 曲线、色温、饱和度、颗粒 | 颗粒需避免破坏细节 |
| 胶片 | 褪色复古 | 低对比、抬黑、淡色、轻微暗角 | 曲线、对比、色调、暗角 | 避免整体灰雾、肤色发灰 |
| 电影 | 青橙电影 | 暗部偏青、肤色/高光偏暖、对比增强 | 分离色调/HSL、曲线、高光阴影 | 肤色保护优先，强度默认中低 |
| 夜景 | 赛博霓虹 | 洋红/青蓝强调、深色背景、局部高饱和 | HSL、曲线、饱和度、色温 | 高饱和易色带和噪声放大；不改变 LED 光源色彩承诺 |
| 日系 | 清透日系 | 高亮、低反差、偏冷或微暖、低饱和 | 曝光、高光、曲线、色温、饱和度 | 保留高光层次，避免过曝 |
| 人像 | 柔肤暖光 | 柔和反差、暖亮肤色、肤色饱和度保护 | 局部/肤色 HSL、纹理/清晰度、色温 | 第一版不做未经明确告知的人脸磨皮或身份相关推断 |
| 美食 | 鲜润食物 | 适度增饱和、暖色、主体对比 | HSL、对比、白平衡 | 白盘/中性背景应保持中性 |
| 风景 | 青绿旅行 | 蓝天和绿色更清晰、层次增强 | HSL、去朦胧/局部对比、曲线 | 过度处理会产生色偏/光晕 |
| 黑白 | 银盐黑白 | 中高对比、黑白混色控制、颗粒可选 | 灰度混色、曲线、颗粒 | 颗粒提供独立开关/强度 |

命名策略建议使用“风格效果 + 场景/情绪”组合，例如“褪色胶片”“夜行霓虹”“清晨日系”，不用仿冒具体厂商或创作者的专有滤镜名称。首版可以用自研颜色矩阵/曲线实现基础款；复杂 HSL、LUT、颗粒和局部蒙版需要后续图像处理管线，不应假装当前单个 ColorMatrix 已能实现。

## 4. 配方数据模型草案

```json
{
  "schema_version": 1,
  "preset_id": "film_faded",
  "name": "褪色胶片",
  "category": "film",
  "description": "低反差、柔和黑位与轻微暖调",
  "amount": 0.72,
  "image_adjustments": {
    "exposure_ev": 0.0,
    "contrast": -0.12,
    "highlights": -0.18,
    "shadows": 0.10,
    "temperature": 0.08,
    "tint": 0.01,
    "saturation": -0.08,
    "grain": 0.06,
    "vignette": 0.04
  },
  "capture_preferences": {
    "exposure_compensation_ev": 0.0,
    "flash_mode": "off"
  }
}
```

模型输出只允许建议 `preset_id`、有限范围的 Amount 和可选语义参数。客户端从本地注册表读取真实配方并验证各字段；忽略额外字段。采集参数与后处理参数分开显示和应用，避免用户以为拍摄参数等于后期效果。

## 5. Android 相机参数能力盘点

### 当前已有

- CameraX `CameraControl`：变焦、曝光补偿；应用同时有人脸测光/对焦建议路径。
- App 后处理：自然、暖调、柔和、鲜明、冷调五种基于颜色矩阵的影调，作用于拍摄后的 JPEG。
- 能力报告：变焦范围、EV 范围与步长、手动传感器能力标志、AWB 模式、AF 模式、最小对焦距离、闪光灯和 JPEG 输出尺寸等部分信息。
- 云端执行动作：曝光 EV、变焦、以上五种影调、构图提示；未实现额外 Camera2 控制。

### 可以进一步探测与评估

| 能力 | Android API 线索 | 规划注意事项 |
|---|---|---|
| 曝光时间、ISO、帧时长 | `SENSOR_INFO_EXPOSURE_TIME_RANGE`、`SENSOR_INFO_SENSITIVITY_RANGE`、MANUAL_SENSOR | 可能需组合固定 AE 与手动采集；互相依赖且低照度运动模糊风险高 |
| 对焦模式/距离/区域 | AF available modes、`LENS_INFO_MINIMUM_FOCUS_DISTANCE`、最大 AF regions | 固定焦距设备不支持；CameraX 对焦点与 Camera2 手动焦距要区分 |
| 白平衡/色彩 | AWB available modes、MANUAL_POST_PROCESSING、`COLOR_CORRECTION_GAINS/TRANSFORM` | 优先语义化 preset（daylight/cloudy）再考虑 Kelvin；受 ISP/设备能力限制 |
| 闪光灯 | flash unit、AE modes、`FLASH_MODE` | 预览和 still capture 行为可能不同；避免 Agent 在未告知时触发闪光 |
| 色调映射/颜色效果 | `TONEMAP_MODE/CURVE`、色彩校正模式 | 有可用模式和版本限制；与设备 ISP 默认处理可能冲突 |
| 降噪/锐化/边缘增强 | `NOISE_REDUCTION_MODE`、`SHADING_MODE`、`EDGE_MODE` available modes | 质量与延迟取舍；先验证设备可用模式再请求，部分模式可能只允许 capture 时改变 |
| 镜头校正 | `LENS_DISTORTION_CORRECTION_MODE`、色差校正模式 | 会影响裁切与边缘画质；由设备默认 ISP 接管往往更稳 |
| 防抖 | OIS 能力与视频稳定模式 | 与照片/视频、分辨率和设备模式相关；不是通用 CameraX CameraControl 开关 |
| 输出质量 | JPEG quality、输出尺寸/格式、RAW/YUV 能力 | 提高尺寸会增加内存/耗时；RAW 处理是独立工作量 |
| 物理镜头选择 | logical multi-camera characteristics / physical camera IDs | 切镜头会重建/影响会话；不能仅将 zoom ratio 等同物理镜头控制 |

CameraX 的 Camera2Interop 可给 UseCase 或活动的 CameraControl 加 Camera2 选项，并查询 Camera2 元数据；官方警告互操作参数可覆盖 CameraX 内部值并造成非预期行为。因此建议先扩展能力报告与验证矩阵，再选少数高价值参数接入，不要把 CaptureRequest 全量开放给模型。[Camera2Interop 官方说明](https://developer.android.com/reference/androidx/camera/camera2/interop/Camera2Interop)、[CameraControl 官方 API](https://developer.android.com/reference/androidx/camera/core/CameraControl)、[CaptureRequest 参数目录](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest)、[CameraCharacteristics 能力元数据](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics)

## 6. 建议分期

1. **相机与模型设置导航：已实现。**底部 Tab 提供主界面、风格预设和模型设置。
2. **风格预设 MVP：已实现。**本地注册 8 个固定预设，支持预设强度和曝光、对比、高光、阴影、色温、色调、饱和度的配方调节；支持收藏、最近使用、自定义配方保存/重命名/删除和恢复默认。拍照后提供成片/原片按住对比，并分别保存成片或原片；后处理保留主要拍摄 EXIF。
3. **Agent 接入：已实现。**模型可返回本地内置白名单 `preset_id` 与 `preset_strength`；客户端严格验证、直接应用并纳入已有逐项结果与撤销快照。模型不能返回任意配方或处理代码；用户自定义预设仅在设备本地可用。
4. **设备高级控制：后续阶段。**手动曝光时间/ISO、白平衡、焦距及其他 Camera2 控制不属于本阶段。
5. **质量验收：待执行。**每个预设仍需用日光、室内、低照度及人像/食物/风景样张调优；需在多品牌真机确认色彩效果、元数据和处理耗时。

当前处理使用有界颜色矩阵与逐像素色调/颜色调整，不包含 LUT、HSL 分区、局部蒙版、颗粒或暗角。因此“电影感”“人像”等名称是轻量本地配方，不代表专业 RAW 或厂商 ISP 效果；复杂处理管线和真机调色属于后续质量迭代。

## 7. 参考资料

- [VSCO：Using and Managing Presets](https://support.vsco.co/en/articles/12698476-using-and-managing-presets)
- [VSCO：How to Create and Apply a Recipe](https://support.vsco.co/en/articles/12698502-how-to-create-and-apply-a-recipe)
- [美图秀秀：滤镜风格介绍](https://www.meituxiuxiu.com/zh-Hans/academy/meitu-filters)
- [美图秀秀：相机与拍前风格介绍](https://www.meituxiuxiu.com/zh-Hans/academy/camera)
- [Adobe Lightroom：Apply Presets on Mobile](https://helpx.adobe.com/lightroom/mobile/work-with-presets/apply-presets.html)
- [Adobe Lightroom：Create, Update, and Delete Presets](https://helpx.adobe.com/uk/lightroom/mobile/work-with-presets/create-update-delete-presets.html)
- [Android Camera2Interop](https://developer.android.com/reference/androidx/camera/camera2/interop/Camera2Interop)
- [Android CameraControl](https://developer.android.com/reference/androidx/camera/core/CameraControl)
- [Android CaptureRequest](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest)
- [Android CameraCharacteristics](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics)
