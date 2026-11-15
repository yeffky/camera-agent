# 拍摄按钮方形改动：真机（模拟器）截图证据

同一台模拟器（cam35，Android 15 / API 35，1080×2400，420dpi）、同一主题与状态：
相机页取景界面，已授予 CAMERA 权限，未拍摄照片，界面处于首页底部拍摄区。

| 文件 | 版本 | 说明 |
|---|---|---|
| `shutter-before-device.png` | 修改前：`main` 49ad844 | 原圆形 72dp 快门，外框为 oval 形状的 4dp 白描边 |
| `shutter-after-device.png` | 修改后：本 PR `ec0cc05` | 方形 72dp 快门，24dp 圆角，外框仍为 4dp 白描边 |
| `shutter-side-by-side.png` | 两张原始截图底部拍摄区裁切并 2 倍放大后并排 | 左=修改前，右=修改后 |

测量（像素坐标，密度 2.625，取自上述原始截图）：

- 快门外框包围盒两者都是 189×189 px（= 72dp），中心都在 (568, 2090)，说明尺寸和位置未变。
- 形状差异：修改前外框在包围盒角上要向内缩进约 74px（半径 36dp 的圆）；
  修改后角上只缩进约 49px，对应 24dp 圆角 + 4dp 描边。
- 截图来自 Android 模拟器（软件渲染），不是实体手机；当时系统时钟区只显示信号与电量。

复现步骤：

```bash
git checkout 49ad844   # 或本 PR 的 commit
export ANDROID_HOME=$HOME/android-sdk JAVA_HOME=$HOME/jdks/jdk-21.0.13+9
sh ./gradlew assembleDebug
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.example.cameraagent android.permission.CAMERA
adb shell am start -n com.example.cameraagent/.MainActivity
adb exec-out screencap -p > shutter.png
```
