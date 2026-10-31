package com.example.cameraagent

import android.Manifest
import android.content.ContentValues
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PointF
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.view.View
import android.widget.ScrollView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.HorizontalScrollView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.ZoomState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private data class AgentApplySnapshot(
    val exposureIndex: Int,
    val zoomRatio: Float?,
    val colorStyle: ColorStyle,
    val manualColorOverride: Boolean,
    val presetId: String,
    val presetAmount: Int,
    val cropRect: NormalizedCropRect?,
    val cropAspectRatio: Float,
)

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var compositionOverlay: CompositionOverlayView
    private lateinit var statusText: TextView
    private lateinit var captureIntentButton: TextView
    private lateinit var zoomSeekBar: SeekBar
    private lateinit var zoomLabel: TextView
    private lateinit var exposureLabel: TextView
    private lateinit var exposureDownButton: Button
    private lateinit var exposureUpButton: Button
    private lateinit var autoModeButton: Button
    private lateinit var colorStyleButton: Button
    private lateinit var quickColorButton: Button
    private lateinit var cameraContent: android.widget.FrameLayout
    private lateinit var pageHost: android.widget.FrameLayout
    private lateinit var controlsPanel: View
    private lateinit var agentCircleButton: View
    private lateinit var shutterButton: View
    private lateinit var presetPreviewStrip: LinearLayout
    private lateinit var cameraAdvicePanel: LinearLayout
    private lateinit var cameraAdviceText: TextView
    private lateinit var cameraAutoCaptureButton: Button
    private lateinit var cameraUndoButton: Button
    private lateinit var agentConfigStore: AgentConfigStore
    private lateinit var photoPresetStore: PhotoPresetStore
    private lateinit var baseUrlEditText: EditText
    private lateinit var endpointPathEditText: EditText
    private lateinit var modelEditText: EditText
    private lateinit var temperatureEditText: EditText
    private lateinit var apiKeyEditText: EditText
    private lateinit var credentialStatusText: TextView
    private lateinit var requestPreviewText: TextView
    private lateinit var cloudAdviceText: TextView
    private lateinit var sendCloudRequestButton: Button
    private lateinit var undoCloudAdviceButton: Button
    private val navTabViews = mutableMapOf<String, LinearLayout>()
    private val navTabIconViews = mutableMapOf<String, android.widget.ImageView>()
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var analysisExecutor: ExecutorService
    private lateinit var photoProcessingExecutor: ExecutorService
    private lateinit var cloudApiExecutor: ExecutorService

    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var sceneAnalyzer: AgentSceneAnalyzer? = null
    private var latestAssessment: SceneAssessment? = null
    private var selectedColorStyle = ColorStyle.NATURAL
    private var selectedPresetId = "natural"
    private var selectedPresetAmount = 0
    private var appliedPreviewSignature: String? = null
    private var activeAiCropRect: NormalizedCropRect? = null
    private var activeAiCropAspectRatio = 1f
    private var latestCloudAdviceAspectRatio = 1f
    private var photoCaptureInProgress = false
    private var captureAutomaticallyAfterAi = false
    private var activeTab = "home"
    private var settingsPage: View? = null
    private var recommendedColorStyle = ColorStyle.NATURAL
    private var autoModeEnabled = false
    private var manualColorOverride = false
    private var baselineExposureIndex = 0
    private var lastAutoExposureIndex: Int? = null
    private var lastMeteringPoint: PointF? = null
    private var guideEnabled = true
    private var zoomSeekBarUpdating = false
    private var cloudRequestInFlight = false
    private var cloudAdviceApplying = false
    private var latestCloudAdvice: CameraAdvice? = null
    private var latestCloudAdviceAtMs = 0L
    private var latestCloudAdviceSceneName: String? = null
    private var latestCloudAdviceVisualSignature: Long? = null
    private var latestCloudAdviceFaceBounds: android.graphics.RectF? = null
    private var latestCloudAdviceRequestId: String? = null
    private var latestAdviceIntentId: String? = null
    private var latestAgentApplySnapshot: AgentApplySnapshot? = null
    private var agentCaptureContext = AgentCaptureContext()
    @Volatile
    private var activeAgentRequest: ActiveAgentRequest? = null

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startCamera() else showStatus("需要相机权限才能启动取景")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        analysisExecutor = Executors.newSingleThreadExecutor()
        photoProcessingExecutor = Executors.newSingleThreadExecutor()
        cloudApiExecutor = Executors.newSingleThreadExecutor()
        agentConfigStore = AgentConfigStore(this)
        photoPresetStore = PhotoPresetStore(this)
        selectedPresetId = photoPresetStore.selectedId()
        selectedColorStyle = photoPresetStore.find(selectedPresetId)?.style ?: ColorStyle.NATURAL
        selectedPresetAmount = photoPresetStore.amount(selectedPresetId)
        manualColorOverride = selectedPresetId != "natural"
        captureAutomaticallyAfterAi = getSharedPreferences(CAMERA_PREFERENCES, MODE_PRIVATE)
            .getBoolean(KEY_AUTO_CAPTURE_AFTER_AI, false)
        setContentView(createCameraScreen())

        if (hasCameraPermission()) startCamera() else requestCameraPermission.launch(Manifest.permission.CAMERA)
    }

    override fun onDestroy() {
        cancelActiveAgentRequest("页面已关闭", updateUi = false)
        super.onDestroy()
        sceneAnalyzer?.close()
        cameraExecutor.shutdown()
        analysisExecutor.shutdown()
        photoProcessingExecutor.shutdown()
        cloudApiExecutor.shutdownNow()
    }

    override fun onStop() {
        if (activeAgentRequest != null) cancelActiveAgentRequest("应用已进入后台")
        agentCaptureContext = AgentCaptureContext()
        super.onStop()
    }

    private fun createCameraScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val contentHost = android.widget.FrameLayout(this)
        root.addView(contentHost, LinearLayout.LayoutParams(-1, 0, 1f))
        cameraContent = android.widget.FrameLayout(this)
        pageHost = android.widget.FrameLayout(this).apply { visibility = View.GONE }
        contentHost.addView(cameraContent, android.widget.FrameLayout.LayoutParams(-1, -1))
        contentHost.addView(pageHost, android.widget.FrameLayout.LayoutParams(-1, -1))

        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        compositionOverlay = CompositionOverlayView(this)
        cameraContent.addView(previewView, android.widget.FrameLayout.LayoutParams(-1, -1))
        cameraContent.addView(compositionOverlay, android.widget.FrameLayout.LayoutParams(-1, -1))

        // 顶栏容器（Top Bar Container）
        val topBar = android.widget.FrameLayout(this).apply {
            setPadding(dp(16), dp(44), dp(16), 0)
        }

        // 1. 左上角：调控功能按钮（精美小巧图标按钮）
        val btnControls = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_sliders_control)
            setBackgroundResource(R.drawable.bg_glass_button)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            contentDescription = "打开相机调控"
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val expanded = controlsPanel.visibility != View.VISIBLE
                controlsPanel.visibility = if (expanded) View.VISIBLE else View.GONE
            }
        }
        topBar.addView(
            btnControls,
            android.widget.FrameLayout.LayoutParams(dp(48), dp(48), Gravity.START or Gravity.TOP),
        )

        // 2. 右上角：Agent 配置栏功能按钮（精美小巧齿轮图标按钮）
        val btnAgentConfig = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_settings_gear)
            setBackgroundResource(R.drawable.bg_glass_button)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            contentDescription = "打开模型设置"
            isClickable = true
            isFocusable = true
            setOnClickListener {
                switchTab("settings")
            }
        }
        topBar.addView(
            btnAgentConfig,
            android.widget.FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END or Gravity.TOP),
        )

        cameraContent.addView(topBar, android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        val sceneStatusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(7), dp(12), dp(7))
            setBackgroundResource(R.drawable.bg_glass_card)
            contentDescription = "场景与构图状态"
        }
        sceneStatusCard.addView(TextView(this).apply {
            text = "CAMERA AGENT"
            textSize = 9f
            letterSpacing = 0.12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(183, 204, 239))
        })
        statusText = TextView(this).apply {
            text = "正在识别场景"
            textSize = 11f
            setTextColor(Color.WHITE)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        }
        sceneStatusCard.addView(statusText, matchWidthWrapHeight())
        captureIntentButton = TextView(this).apply {
            text = "拍摄意图：自动判断  ⌄"
            textSize = 11f
            setTextColor(Color.rgb(231, 223, 255))
            setPadding(0, dp(5), 0, 0)
            isClickable = true
            isFocusable = true
            contentDescription = "选择拍摄意图"
            setOnClickListener { showCaptureIntentPicker() }
        }
        sceneStatusCard.addView(captureIntentButton, matchWidthWrapHeight())
        topBar.addView(
            sceneStatusCard,
            android.widget.FrameLayout.LayoutParams(dp(188), -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL),
        )

        cameraAdvicePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundResource(R.drawable.bg_glass_card)
            visibility = View.GONE
        }
        val adviceTitleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val adviceIcon = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_agent_sparkle)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            setColorFilter(Color.rgb(204, 190, 255))
        }
        adviceTitleRow.addView(adviceIcon, LinearLayout.LayoutParams(dp(26), dp(26)))
        adviceTitleRow.addView(TextView(this).apply {
            text = "AI 构图建议"
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(8), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        adviceTitleRow.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = cameraPillBackground(selected = false, accentColor = Color.WHITE)
            contentDescription = "关闭 AI 建议"
            isClickable = true
            isFocusable = true
            setOnClickListener { showCameraAdvice("", show = false) }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        cameraAdvicePanel.addView(adviceTitleRow)
        cameraAdviceText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            maxLines = 3
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, dp(4), 0, dp(2))
        }
        cameraAdvicePanel.addView(cameraAdviceText, matchWidthWrapHeight())
        val adviceActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        cameraAutoCaptureButton = Button(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.bg_glass_button)
            setOnClickListener {
                if (cloudRequestInFlight) {
                    cancelActiveAgentRequest("已取消分析")
                    return@setOnClickListener
                }
                captureAutomaticallyAfterAi = !captureAutomaticallyAfterAi
                getSharedPreferences(CAMERA_PREFERENCES, MODE_PRIVATE).edit()
                    .putBoolean(KEY_AUTO_CAPTURE_AFTER_AI, captureAutomaticallyAfterAi).apply()
                updateCameraAutoCaptureButton()
                showStatus(if (captureAutomaticallyAfterAi) "AI 建议成功应用后将自动拍摄" else "AI 调整后由你按快门")
            }
        }
        updateCameraAutoCaptureButton()
        cameraUndoButton = Button(this).apply {
            text = "撤销 AI 调整"
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.bg_glass_button)
            visibility = View.GONE
            setOnClickListener { undoLastCloudAdvice() }
        }
        adviceActions.addView(cameraAutoCaptureButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            rightMargin = dp(6)
        })
        adviceActions.addView(cameraUndoButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        cameraAdvicePanel.addView(adviceActions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        cameraContent.addView(cameraAdvicePanel, android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
            setMargins(dp(16), dp(96), dp(16), 0)
        })

        // 4. 左上角浮动“调控面板”（Controls Panel）
        controlsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundResource(R.drawable.bg_glass_card)
            visibility = View.GONE
        }

        val panelTitleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val panelTitle = TextView(this).apply {
            text = "相机参数调控"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val panelClose = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setOnClickListener { controlsPanel.visibility = View.GONE }
        }
        panelTitleRow.addView(panelTitle, LinearLayout.LayoutParams(0, -2, 1f))
        panelClose.contentDescription = "关闭相机调控"
        panelTitleRow.addView(panelClose, LinearLayout.LayoutParams(dp(48), dp(48)))
        (controlsPanel as LinearLayout).addView(panelTitleRow, matchWidthWrapHeight())

        // 变焦 Control
        val zoomRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(4))
        }
        zoomLabel = TextView(this).apply {
            text = "变焦 1.0×"
            setTextColor(Color.WHITE)
            textSize = 13f
            minWidth = dp(70)
        }
        zoomSeekBar = SeekBar(this).apply {
            max = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && !zoomSeekBarUpdating) applyZoomProgress(progress)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        zoomRow.addView(zoomLabel)
        zoomRow.addView(zoomSeekBar, LinearLayout.LayoutParams(0, -2, 1f))
        (controlsPanel as LinearLayout).addView(zoomRow, matchWidthWrapHeight())

        // 曝光 Control
        val exposureRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(8))
        }
        exposureLabel = TextView(this).apply {
            text = "曝光 0.0 EV"
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
        }
        exposureDownButton = Button(this).apply {
            text = "曝光 −"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.bg_glass_button)
            setOnClickListener { adjustExposure(-1) }
        }
        exposureUpButton = Button(this).apply {
            text = "曝光 +"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.bg_glass_button)
            setOnClickListener { adjustExposure(1) }
        }
        exposureRow.addView(exposureDownButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        exposureRow.addView(exposureLabel, LinearLayout.LayoutParams(0, -2, 1.2f))
        exposureRow.addView(exposureUpButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        (controlsPanel as LinearLayout).addView(exposureRow, matchWidthWrapHeight())

        // 影调 & 自动模式
        colorStyleButton = Button(this).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.bg_glass_button)
            setOnClickListener { cycleColorStyle() }
        }
        (controlsPanel as LinearLayout).addView(colorStyleButton, LinearLayout.LayoutParams(-1, dp(48)).apply {
            topMargin = dp(4)
        })
        updateColorStyleButton()

        autoModeButton = Button(this).apply {
            text = "自动调控：关闭"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.bg_glass_button)
            setOnClickListener { toggleAutoMode() }
        }
        (controlsPanel as LinearLayout).addView(autoModeButton, LinearLayout.LayoutParams(-1, dp(48)).apply {
            topMargin = dp(6)
        })

        val guideButton = Button(this).apply {
            text = "切换构图辅助线"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.bg_glass_button)
            setOnClickListener {
                guideEnabled = !guideEnabled
                compositionOverlay.visibility = if (guideEnabled) View.VISIBLE else View.GONE
                showStatus(if (guideEnabled) "构图辅助线已开启" else "构图辅助线已关闭")
            }
        }
        (controlsPanel as LinearLayout).addView(guideButton, LinearLayout.LayoutParams(-1, dp(48)).apply {
            topMargin = dp(6)
        })

        val capabilityButton = Button(this).apply {
            text = "设备能力报告"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.bg_glass_button)
            setOnClickListener { showCapabilityReport() }
        }
        (controlsPanel as LinearLayout).addView(capabilityButton, LinearLayout.LayoutParams(-1, dp(48)).apply {
            topMargin = dp(6)
        })

        cameraContent.addView(
            controlsPanel,
            android.widget.FrameLayout.LayoutParams(dp(280), -2, Gravity.TOP or Gravity.START).apply {
                setMargins(dp(16), dp(96), 0, 0)
            },
        )

        // 5. 底部操作栏（Bottom Control Area）
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(10), dp(24), dp(18))
        }

        // 快速切影调小按钮
        quickColorButton = Button(this).apply {
            text = "自然"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackground(cameraPillBackground(selected = true, accentColor = Color.rgb(183, 204, 239)))
            maxLines = 2
            gravity = Gravity.CENTER
            contentDescription = "当前风格，点按切换预设"
            setOnClickListener { cycleColorStyle() }
        }
        bottomBar.addView(quickColorButton, LinearLayout.LayoutParams(dp(82), dp(52)).apply {
            rightMargin = dp(18)
        })
        updateColorStyleButton()

        // 主拍摄按钮（大圆形）
        shutterButton = View(this).apply {
            setBackgroundResource(R.drawable.bg_shutter_outer)
            setOnClickListener { takePhoto() }
        }
        bottomBar.addView(shutterButton, LinearLayout.LayoutParams(dp(72), dp(72)))

        // Agent 功能圆形按钮（与拍摄按钮并排）
        agentCircleButton = android.widget.FrameLayout(this).apply {
            setBackgroundResource(R.drawable.bg_agent_circle)
            contentDescription = "AI 拍摄建议"
            val icon = android.widget.ImageView(context).apply {
                setImageResource(R.drawable.ic_agent_sparkle)
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }
            addView(icon, android.widget.FrameLayout.LayoutParams(-1, -1))
            setOnClickListener {
                sendCloudRequest()
            }
        }
        bottomBar.addView(agentCircleButton, LinearLayout.LayoutParams(dp(60), dp(60)).apply {
            leftMargin = dp(18)
        })

        val bottomControls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                orientation = android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP
                colors = intArrayOf(0xdd000000.toInt(), 0x00000000)
            }
        }
        bottomControls.addView(createPresetPreviewStrip(), LinearLayout.LayoutParams(-1, dp(52)))
        bottomControls.addView(bottomBar, LinearLayout.LayoutParams(-1, -2))
        cameraContent.addView(bottomControls, android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(5), dp(10), dp(5))
            setBackgroundColor(Color.rgb(13, 16, 22))
        }
        listOf(
            Triple("home", "主界面", R.drawable.ic_nav_camera),
            Triple("presets", "风格预设", R.drawable.ic_nav_presets),
            Triple("settings", "模型设置", R.drawable.ic_settings_gear),
        ).forEach { (tab, label, iconResource) ->
            val icon = android.widget.ImageView(this).apply {
                setImageResource(iconResource)
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
            }
            val title = TextView(this).apply {
                text = label
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(0, dp(3), 0, 0)
            }
            val tabButton = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                contentDescription = label
                addView(icon)
                addView(title)
                setOnClickListener {
                    switchTab(tab)
                    animate().scaleX(0.96f).scaleY(0.96f).setDuration(70L).withEndAction {
                        animate().scaleX(1f).scaleY(1f).setDuration(100L).start()
                    }.start()
                }
            }
            navTabViews[tab] = tabButton
            navTabIconViews[tab] = icon
            tabButton.tag = title
            tabBar.addView(tabButton, LinearLayout.LayoutParams(0, dp(54), 1f).apply {
                leftMargin = dp(4)
                rightMargin = dp(4)
            })
        }
        root.addView(tabBar, LinearLayout.LayoutParams(-1, dp(64)))
        updateNavTabAppearance()

        return root
    }

    private fun switchTab(tab: String) {
        if (tab !in setOf("home", "presets", "settings")) return
        if (tab != "home") {
            if (activeAgentRequest != null) cancelActiveAgentRequest("已离开取景页")
            agentCaptureContext = AgentCaptureContext()
            updateCaptureIntentButton()
        }
        activeTab = tab
        updateNavTabAppearance()
        val isHome = tab == "home"
        cameraContent.visibility = if (isHome) View.VISIBLE else View.GONE
        pageHost.visibility = if (isHome) View.GONE else View.VISIBLE
        if (!isHome) {
            pageHost.removeAllViews()
            val page = if (tab == "presets") createPresetsPage() else settingsPage ?: createAgentSettingsPage().also { settingsPage = it }
            pageHost.addView(page, android.widget.FrameLayout.LayoutParams(-1, -1))
            pageHost.alpha = 0f
            pageHost.animate().alpha(1f).setDuration(160L).start()
        } else {
            cameraContent.alpha = 0f
            cameraContent.animate().alpha(1f).setDuration(160L).start()
        }
    }

    private fun selectedCaptureIntentId(): String? = CaptureIntentCatalog.find(
        latestAssessment?.sceneName,
        agentCaptureContext.selectedIntentId,
    )?.id

    private fun updateCaptureIntentButton() {
        if (!::captureIntentButton.isInitialized) return
        val selected = CaptureIntentCatalog.find(latestAssessment?.sceneName, agentCaptureContext.selectedIntentId)
        captureIntentButton.text = "拍摄意图：${selected?.label ?: "自动判断"}  ⌄"
        captureIntentButton.alpha = if (CaptureIntentCatalog.options(latestAssessment?.sceneName).isEmpty()) 0.72f else 1f
    }

    private fun showCaptureIntentPicker() {
        val options = CaptureIntentCatalog.options(latestAssessment?.sceneName)
        val labels = listOf("自动判断") + options.map { it.label }
        val currentIndex = options.indexOfFirst { it.id == agentCaptureContext.selectedIntentId }
            .takeIf { it >= 0 }?.plus(1) ?: 0
        android.app.AlertDialog.Builder(this)
            .setTitle("拍摄意图")
            .setSingleChoiceItems(labels.toTypedArray(), currentIndex) { dialog, which ->
                agentCaptureContext.selectedIntentId = options.getOrNull(which - 1)?.id
                updateCaptureIntentButton()
                dialog.dismiss()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun cancelActiveAgentRequest(reason: String, updateUi: Boolean = true) {
        val request = activeAgentRequest ?: return
        activeAgentRequest = null
        request.cancellation.cancel()
        cloudRequestInFlight = false
        if (updateUi) {
            if (::sendCloudRequestButton.isInitialized) {
                sendCloudRequestButton.isEnabled = true
                sendCloudRequestButton.text = "发送当前画面并获取建议"
            }
            if (::cameraAutoCaptureButton.isInitialized) updateCameraAutoCaptureButton()
            showCameraAdvice("$reason；相机仍可正常拍摄。")
            showStatus("AI 分析已取消")
        }
    }

    private fun hasCompositionChanged(request: ActiveAgentRequest, assessment: SceneAssessment): Boolean {
        if (request.sceneName != assessment.sceneName) return true
        val signature = request.visualSignature
        if (signature != null && java.lang.Long.bitCount(signature xor assessment.visualSignature) >= VISUAL_CHANGE_THRESHOLD) {
            return true
        }
        val before = request.faceBounds
        val after = assessment.faceBounds
        return if (before == null || after == null) before != after else
            kotlin.math.abs(before.centerX() - after.centerX()) >= FACE_MOVE_THRESHOLD ||
                kotlin.math.abs(before.centerY() - after.centerY()) >= FACE_MOVE_THRESHOLD ||
                kotlin.math.abs(before.width() - after.width()) >= FACE_MOVE_THRESHOLD ||
                kotlin.math.abs(before.height() - after.height()) >= FACE_MOVE_THRESHOLD
    }

    private fun updateNavTabAppearance() {
        navTabViews.forEach { (tab, view) ->
            val selected = tab == activeTab
            val title = view.tag as? TextView
            val color = if (selected) Color.rgb(213, 228, 255) else Color.rgb(164, 171, 183)
            view.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(if (selected) Color.rgb(39, 49, 67) else Color.TRANSPARENT)
            }
            view.isSelected = selected
            title?.setTextColor(if (selected) Color.WHITE else Color.rgb(164, 171, 183))
            navTabIconViews[tab]?.setColorFilter(color)
        }
    }

    private fun createPresetsPage(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(24))
        }
        val scroll = ScrollView(this).apply { addView(content) }
        content.addView(settingHeading("风格预设"))
        content.addView(settingDescription("调整影调和照片参数。取景页可实时预览，拍摄后可微调强度；预览与成片的高光、阴影和设备处理可能略有差异。"))

        val presetStrip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        photoPresetStore.allPresets().forEach { preset ->
            presetStrip.addView(TextView(this).apply {
                text = preset.name
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setBackgroundResource(if (preset.id == selectedPresetId) R.drawable.bg_glass_card else R.drawable.bg_glass_button)
                setOnClickListener { selectPreset(preset.id) }
            }, LinearLayout.LayoutParams(-2, dp(42)).apply { rightMargin = dp(8) })
        }
        content.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(presetStrip)
        }, LinearLayout.LayoutParams(-1, dp(46)))

        val selected = photoPresetStore.find(selectedPresetId) ?: PhotoPresets.all.first()
        content.addView(settingLabel("当前：${selected.name} · ${selectedPresetAmount}%"))
        val selectedLabel = settingDescription("${selected.name} · ${selectedPresetAmount}%")
        content.addView(selectedLabel)
        val amountBar = SeekBar(this).apply {
            max = 100
            progress = selectedPresetAmount
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    selectedPresetAmount = progress
                    photoPresetStore.setAmount(selectedPresetId, progress)
                    selectedLabel.text = "${selected.name} · $progress%"
                    refreshPresetPreview()
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }
        content.addView(amountBar)
        content.addView(Button(this).apply {
            text = "恢复此预设默认强度"
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.bg_glass_button)
            setOnClickListener {
                selectedPresetAmount = selected.defaultAmount
                photoPresetStore.setAmount(selectedPresetId, selectedPresetAmount)
                switchTab("presets")
            }
        }, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(4) })

        var recipe = photoPresetStore.recipe(selectedPresetId)
        content.addView(settingLabel("成片调整"))
        addRecipeSlider(content, "曝光", recipe.exposureEv, -2f, 2f) { value ->
            recipe = recipe.copy(exposureEv = value); photoPresetStore.setRecipe(selectedPresetId, recipe)
        }
        addRecipeSlider(content, "对比", recipe.contrast, -1f, 1f) { value ->
            recipe = recipe.copy(contrast = value); photoPresetStore.setRecipe(selectedPresetId, recipe)
        }
        addRecipeSlider(content, "高光", recipe.highlights, -1f, 1f) { value ->
            recipe = recipe.copy(highlights = value); photoPresetStore.setRecipe(selectedPresetId, recipe)
        }
        addRecipeSlider(content, "阴影", recipe.shadows, -1f, 1f) { value ->
            recipe = recipe.copy(shadows = value); photoPresetStore.setRecipe(selectedPresetId, recipe)
        }
        addRecipeSlider(content, "色温", recipe.temperature, -1f, 1f) { value ->
            recipe = recipe.copy(temperature = value); photoPresetStore.setRecipe(selectedPresetId, recipe)
        }
        addRecipeSlider(content, "色调", recipe.tint, -1f, 1f) { value ->
            recipe = recipe.copy(tint = value); photoPresetStore.setRecipe(selectedPresetId, recipe)
        }
        addRecipeSlider(content, "饱和度", recipe.saturation, -1f, 1f) { value ->
            recipe = recipe.copy(saturation = value); photoPresetStore.setRecipe(selectedPresetId, recipe)
        }
        content.addView(Button(this).apply {
            text = "恢复此预设默认调整"
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.bg_glass_button)
            setOnClickListener {
                photoPresetStore.setRecipe(selectedPresetId, selected.recipe)
                switchTab("presets")
            }
        }, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(4) })

        content.addView(Button(this).apply {
            text = "另存为自定义预设"
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.bg_glass_button)
            setOnClickListener { promptCreatePreset(recipe) }
        }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(10) })

        val favoriteIds = photoPresetStore.favorites()
        val recentIds = photoPresetStore.recent()
        if (recentIds.isNotEmpty()) {
            content.addView(settingLabel("最近使用"))
            content.addView(settingDescription(recentIds.mapNotNull(photoPresetStore::find).joinToString("  ·  ") { it.name }))
        }
        if (favoriteIds.isNotEmpty()) {
            content.addView(settingLabel("我的收藏"))
            content.addView(settingDescription(photoPresetStore.allPresets().filter { it.id in favoriteIds }.joinToString("  ·  ") { it.name }))
        }

        photoPresetStore.allPresets().groupBy { it.category }.forEach { (category, presets) ->
            content.addView(settingLabel(category))
            presets.forEach { preset ->
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(8), dp(8), dp(8))
                    setBackgroundResource(if (preset.id == selectedPresetId) R.drawable.bg_glass_card else R.drawable.bg_glass_button)
                    setOnClickListener { selectPreset(preset.id) }
                }
                val description = TextView(this).apply {
                    text = "${preset.name}\n${preset.description}"
                    textSize = 13f
                    setTextColor(Color.WHITE)
                    setPadding(0, dp(4), 0, dp(4))
                    setOnClickListener { selectPreset(preset.id) }
                }
                card.addView(description, LinearLayout.LayoutParams(0, -2, 1f))
                val favorite = TextView(this).apply {
                    text = if (preset.id in favoriteIds) "★" else "☆"
                    textSize = 22f
                    gravity = Gravity.CENTER
                    setTextColor(Color.rgb(255, 205, 90))
                    setPadding(dp(12), dp(4), dp(12), dp(4))
                    setOnClickListener {
                        photoPresetStore.toggleFavorite(preset.id)
                        switchTab("presets")
                    }
                }
                card.addView(favorite, LinearLayout.LayoutParams(dp(48), -2))
                if (preset.isCustom) {
                    card.addView(TextView(this).apply {
                        text = "⋮"
                        textSize = 22f
                        gravity = Gravity.CENTER
                        setTextColor(Color.WHITE)
                        setOnClickListener { showCustomPresetMenu(preset) }
                    }, LinearLayout.LayoutParams(dp(40), -2))
                }
                content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            }
        }
        return scroll
    }

    private fun addRecipeSlider(
        parent: LinearLayout,
        label: String,
        value: Float,
        minValue: Float,
        maxValue: Float,
        onChanged: (Float) -> Unit,
    ) {
        val valueLabel = settingDescription("$label  ${"%.2f".format(Locale.US, value)}")
        parent.addView(valueLabel)
        parent.addView(SeekBar(this).apply {
            max = 200
            progress = (((value - minValue) / (maxValue - minValue)) * max).roundToInt().coerceIn(0, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val adjusted = minValue + (maxValue - minValue) * progress / max.toFloat()
                    valueLabel.text = "$label  ${"%.2f".format(Locale.US, adjusted)}"
                    onChanged(adjusted)
                    refreshPresetPreview()
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        })
    }

    private fun promptCreatePreset(recipe: PhotoRecipe) {
        val nameInput = EditText(this).apply { hint = "例如：我的暖色人像"; setSingleLine(true) }
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("保存为自定义预设")
            .setView(nameInput)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val preset = photoPresetStore.createCustomPreset(
                nameInput.text.toString().ifBlank { "我的${photoPresetStore.find(selectedPresetId)?.name ?: "预设"}" },
                selectedColorStyle,
                selectedPresetAmount,
                recipe,
            )
            rebuildPresetPreviewStrip()
            selectPreset(preset.id)
            dialog.dismiss()
        }
    }

    private fun showCustomPresetMenu(preset: PhotoPreset) {
        android.app.AlertDialog.Builder(this)
            .setTitle(preset.name)
            .setItems(arrayOf("重命名", "删除")) { _, choice ->
                if (choice == 0) {
                    val input = EditText(this).apply { setText(preset.name); setSingleLine(true) }
                    android.app.AlertDialog.Builder(this).setTitle("重命名预设").setView(input)
                        .setPositiveButton("保存") { _, _ ->
                            photoPresetStore.renameCustomPreset(preset.id, input.text.toString())
                            rebuildPresetPreviewStrip()
                            switchTab("presets")
                        }.setNegativeButton("取消", null).show()
                } else {
                    android.app.AlertDialog.Builder(this).setTitle("删除自定义预设？")
                        .setMessage("${preset.name} 将从本机预设列表中移除。")
                        .setPositiveButton("删除") { _, _ ->
                            photoPresetStore.deleteCustomPreset(preset.id)
                            if (selectedPresetId == preset.id) {
                                selectedPresetId = "natural"
                                selectedColorStyle = ColorStyle.NATURAL
                                selectedPresetAmount = photoPresetStore.amount("natural")
                                manualColorOverride = false
                            }
                            rebuildPresetPreviewStrip()
                            switchTab("presets")
                        }.setNegativeButton("取消", null).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun selectPreset(id: String, navigateToPresets: Boolean = true) {
        val preset = photoPresetStore.find(id) ?: return
        if (autoModeEnabled) toggleAutoMode()
        selectedPresetId = id
        photoPresetStore.setSelectedId(id)
        selectedColorStyle = preset.style
        selectedPresetAmount = photoPresetStore.amount(id)
        photoPresetStore.markRecent(id)
        manualColorOverride = true
        updateColorStyleButton()
        rebuildPresetPreviewStrip()
        latestAssessment?.let { compositionOverlay.updateAssessment(it, selectedColorStyle) }
        if (navigateToPresets) switchTab("presets")
    }

    private fun createAgentSettingsPage(): View {
        val config = agentConfigStore.loadConfig()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(28))
            setBackgroundColor(Color.rgb(10, 13, 18))
        }
        val scrollView = ScrollView(this)
        scrollView.addView(content)

        content.addView(settingHeading("云端 Agent 配置"))
        content.addView(settingDescription("OpenAI 兼容 Chat Completions。请求仅在你手动点击后发送，不会自动调用。"))
        val connectionCard = createSettingsCard()
        content.addView(connectionCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        connectionCard.addView(settingLabel("模型连接"))
        connectionCard.addView(settingDescription("支持 OpenAI 兼容接口，可填写服务地址和模型名称。"))

        baseUrlEditText = addConfigField(
            connectionCard,
            "API 基础地址",
            "例如 https://api.deepseek.com",
            config.baseUrl,
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI,
        )
        endpointPathEditText = addConfigField(
            connectionCard,
            "Chat Completions 路径",
            "/chat/completions",
            config.chatCompletionsPath,
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI,
        )
        modelEditText = addConfigField(
            connectionCard,
            "模型名称",
            "例如 deepseek-flash",
            config.model,
            android.text.InputType.TYPE_CLASS_TEXT,
        )
        temperatureEditText = addConfigField(
            connectionCard,
            "Temperature（0–2）",
            "0.3",
            config.temperature.toString(),
            android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL,
        )
        apiKeyEditText = addConfigField(
            connectionCard,
            "API Key（加密保存在本机）",
            "留空则保留当前已保存的 Key",
            "",
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
        )
        apiKeyEditText.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        credentialStatusText = settingDescription(credentialStatus())
        connectionCard.addView(credentialStatusText)

        val configButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val saveButton = Button(this).apply {
            text = "保存配置"
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.bg_glass_button)
            setOnClickListener { saveAgentConfig() }
        }
        val previewButton = Button(this).apply {
            text = "预览请求"
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.bg_glass_button)
            setOnClickListener { previewAgentRequest() }
        }
        configButtons.addView(saveButton, LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(8) })
        configButtons.addView(previewButton, LinearLayout.LayoutParams(0, dp(40), 1f))
        connectionCard.addView(configButtons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        val requestCard = createSettingsCard()
        content.addView(requestCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        requestCard.addView(settingLabel("拍摄建议请求"))
        sendCloudRequestButton = Button(this).apply {
            text = "发送当前画面并获取建议"
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.bg_glass_button)
            setOnClickListener { sendCloudRequest() }
        }
        requestCard.addView(sendCloudRequestButton, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        requestCard.addView(
            settingDescription("发送时会把一张当前取景帧（最长边 1024 像素、压缩后不超过 512 KB）、场景摘要、相机参数和 API Key 发给上方配置的 HTTPS 服务。画面可能包含人脸或私人环境；图像不会保存在本机，也不会上传视频。"),
        )

        val clearKeyButton = Button(this).apply {
            text = "删除本机保存的 API Key"
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.bg_glass_button)
            setOnClickListener {
                agentConfigStore.clearApiKey()
                apiKeyEditText.text.clear()
                credentialStatusText.text = credentialStatus()
            }
        }
        requestCard.addView(clearKeyButton, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(8) })

        val adviceCard = createSettingsCard()
        content.addView(adviceCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        adviceCard.addView(settingLabel("最近一次云端建议"))
        cloudAdviceText = TextView(this).apply {
            text = "尚未获取云端建议。点击拍摄旁边的 AI 按钮发送请求。"
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundResource(R.drawable.bg_glass_button)
            setTextIsSelectable(true)
        }
        adviceCard.addView(cloudAdviceText, matchWidthWrapHeight())
        undoCloudAdviceButton = Button(this).apply {
            text = "撤销上次 AI 调整"
            setTextColor(Color.WHITE)
            visibility = View.GONE
            setBackgroundResource(R.drawable.bg_glass_button)
            setOnClickListener { undoLastCloudAdvice() }
        }
        adviceCard.addView(undoCloudAdviceButton, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(8) })

        val previewCard = createSettingsCard()
        content.addView(previewCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        previewCard.addView(settingLabel("请求预览"))
        requestPreviewText = TextView(this).apply {
            text = "尚未生成请求预览。"
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundResource(R.drawable.bg_glass_button)
            setTextIsSelectable(true)
        }
        previewCard.addView(requestPreviewText, matchWidthWrapHeight())
        previewCard.addView(
            settingDescription("云端调用始终由你手动触发；每次只发送一张压缩取景帧。模型可建议本地预设，客户端只应用预设库中已登记的方案。"),
        )
        return scrollView
    }

    private fun addConfigField(
        parent: LinearLayout,
        label: String,
        hint: String,
        value: String,
        inputType: Int,
    ): EditText {
        parent.addView(settingLabel(label))
        return EditText(this).apply {
            setSingleLine(true)
            setText(value)
            this.hint = hint
            this.inputType = inputType
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundResource(R.drawable.bg_glass_button)
            parent.addView(this, matchWidthWrapHeight())
        }
    }

    private fun createSettingsCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(14))
        setBackgroundResource(R.drawable.bg_glass_card)
    }

    private fun settingHeading(text: String) = TextView(this).apply {
        this.text = text
        textSize = 20f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
    }

    private fun settingLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.LTGRAY)
        setPadding(0, dp(12), 0, dp(4))
    }

    private fun settingDescription(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(Color.GRAY)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun saveAgentConfig() {
        val config = readAgentConfig() ?: return
        try {
            agentConfigStore.saveConfig(config)
            val apiKey = apiKeyEditText.text.toString()
            if (apiKey.isNotBlank()) agentConfigStore.saveApiKey(apiKey)
            apiKeyEditText.text.clear()
            credentialStatusText.text = credentialStatus()
            showAgentConfigStatus("配置已保存到应用私有文件；API Key 使用 Android Keystore 加密保存")
        } catch (_: Exception) {
            showAgentConfigStatus("保存失败，请检查设备存储与安全密钥状态")
        }
    }

    private fun showAgentConfigStatus(message: String) {
        if (::credentialStatusText.isInitialized) {
            credentialStatusText.text = message
        }
        showStatus(message)
    }

    private fun credentialStatus(): String = if (agentConfigStore.hasApiKey()) {
        "API Key：本机已有加密保存的凭证；预览会隐藏密钥"
    } else {
        "API Key：尚未配置"
    }

    private fun previewAgentRequest() {
        val config = readAgentConfig() ?: return
        val requestBody = buildAgentRequestBody(
            config, "[发送时生成]", agentCaptureContext, selectedCaptureIntentId(), manualColorOverride,
            includeImagePlaceholder = true,
        )
        updateRequestPreview(config, requestBody)
        showAgentConfigStatus("请求预览已生成；点击“发送当前画面并获取建议”会上传一张当前取景帧")
    }

    private fun readAgentConfig(): AgentApiConfig? {
        val configFromStore = agentConfigStore.loadConfig()
        val baseUrl = if (::baseUrlEditText.isInitialized) {
            baseUrlEditText.text.toString().trim().trimEnd('/')
        } else {
            configFromStore.baseUrl
        }
        val endpointPath = if (::endpointPathEditText.isInitialized) {
            endpointPathEditText.text.toString().trim()
        } else {
            configFromStore.chatCompletionsPath
        }
        val model = if (::modelEditText.isInitialized) {
            modelEditText.text.toString().trim()
        } else {
            configFromStore.model
        }
        val temperature = if (::temperatureEditText.isInitialized) {
            temperatureEditText.text.toString().trim().toDoubleOrNull() ?: configFromStore.temperature
        } else {
            configFromStore.temperature
        }
        if (!baseUrl.startsWith("https://", ignoreCase = true) || endpointPath.isBlank() || model.isBlank()) {
            showAgentConfigStatus("请填写正确的 HTTPS API 地址、请求路径和模型名称")
            return null
        }
        if (temperature !in 0.0..2.0) {
            showAgentConfigStatus("Temperature 需为 0 到 2 之间的数字")
            return null
        }
        return AgentApiConfig(
            baseUrl = baseUrl,
            chatCompletionsPath = endpointPath,
            model = model,
            temperature = temperature,
        )
    }

    private fun buildAgentRequestBody(
        config: AgentApiConfig,
        requestId: String,
        captureContext: AgentCaptureContext,
        intentId: String?,
        styleLocked: Boolean,
        imageDataUrl: String? = null,
        imageAspectRatio: Float? = null,
        includeImagePlaceholder: Boolean = false,
    ): JSONObject {
        val assessment = latestAssessment
        val scene = JSONObject()
            .put("category", assessment?.sceneName ?: "unknown")
            .put("confidence", assessment?.confidence ?: 0.0)
            .put("composition_hint", assessment?.compositionHint ?: "not_available")
        val activeCamera = camera
        val exposureState = activeCamera?.cameraInfo?.exposureState
        val zoomState = activeCamera?.cameraInfo?.zoomState?.value
        val exposureStep = exposureState?.exposureCompensationStep?.toFloat() ?: 0f
        val exposureIndex = exposureState?.exposureCompensationIndex ?: 0
        val currentControls = JSONObject()
            .put("zoom_ratio", zoomState?.zoomRatio ?: 1.0)
            .put("exposure_ev", exposureIndex * exposureStep)
            .put("color_style", selectedColorStyle.name.lowercase(Locale.US))
            .put("preset_id", selectedPresetId)
            .put("preset_strength", selectedPresetAmount)
        val supportedControls = JSONArray()
        if (zoomState != null && zoomState.maxZoomRatio > zoomState.minZoomRatio) {
            supportedControls.put("zoom_ratio")
        }
        if (exposureState?.isExposureCompensationSupported == true && exposureStep > 0f) {
            supportedControls.put("exposure_ev")
        }
        if (!styleLocked) {
            supportedControls.put("color_style")
            supportedControls.put("preset_id")
            supportedControls.put("preset_strength")
        }
        supportedControls.put("composition")
        val capabilities = JSONObject()
            .put("supported_controls", supportedControls)
            .put("supports_crop_output", true)
        if (zoomState != null && zoomState.maxZoomRatio > zoomState.minZoomRatio) {
            capabilities.put(
                "zoom_ratio_range",
                JSONObject().put("min", zoomState.minZoomRatio).put("max", zoomState.maxZoomRatio),
            )
        }
        if (exposureState?.isExposureCompensationSupported == true && exposureStep > 0f) {
            capabilities.put(
                "exposure_ev_range",
                JSONObject()
                    .put("min", exposureState.exposureCompensationRange.lower * exposureStep)
                    .put("max", exposureState.exposureCompensationRange.upper * exposureStep),
            )
        }
        val userContent = JSONObject()
            .put("schema_version", 2)
            .put("request_id", requestId)
            .put("scene", scene)
            .put("current_controls", currentControls)
            .put("device_capabilities", capabilities)
            .put("capture_context", JSONObject()
                .put("session_id", captureContext.sessionId)
                .put("intent_id", intentId ?: JSONObject.NULL)
                .put("style_locked", styleLocked)
                .apply {
                    captureContext.previousTurn?.let { turn ->
                        put("previous_turn", JSONObject()
                            .put("request_id", turn.requestId)
                            .put("intent_id", turn.intentId ?: JSONObject.NULL)
                            .put("scene", turn.sceneName)
                            .put("summary", turn.summary)
                            .put("subject_type", turn.subjectType)
                            .put("people_count_class", turn.peopleCountClass)
                            .put("completed_at_elapsed_ms", turn.completedAtMs)
                            .put("applied_actions", JSONArray(turn.appliedActions))
                            .put("skipped_actions", JSONArray(turn.skippedActions))
                            .put("failed_actions", JSONArray(turn.failedActions)))
                    }
                })
            .put("request", "只依据本条附带的新图像和结构化上下文生成建议。裁剪需保持主体完整，优先只做轻微构图修正；需要明显移动手机时给 reframe_tip，不给 crop_rect。不要判断人物动作，不要执行操作。")
        intentId?.let { selectedIntent ->
            CaptureIntentCatalog.find(assessment?.sceneName, selectedIntent)?.let { option ->
                userContent.put("capture_intent", JSONObject().put("id", option.id).put("label", option.label))
            }
        }
        if (styleLocked) userContent.put("style_instruction", "用户已手动选择风格预设；尊重当前预设，不得提供风格覆盖动作。")
        imageAspectRatio?.takeIf { it.isFinite() && it > 0f }?.let { aspect ->
            userContent.put(
                "frame_geometry",
                JSONObject()
                    .put("coordinate_space", "upright_non_mirrored_image")
                    .put("aspect_ratio", aspect.toDouble()),
            )
        }
        val userMessageContent = if (imageDataUrl != null || includeImagePlaceholder) {
            JSONArray()
                .put(JSONObject().put("type", "text").put("text", userContent.toString()))
                .put(
                    JSONObject()
                        .put("type", "image_url")
                        .put(
                            "image_url",
                            JSONObject().put(
                                "url",
                                imageDataUrl ?: "data:image/jpeg;base64,[点击发送时采集并压缩当前取景帧]",
                            ),
                        ),
                )
        } else {
            userContent.toString()
        }
        val messages = JSONArray()
            .put(
                JSONObject()
                    .put("role", "system")
                    .put(
                        "content",
            "你是 Camera Agent。只返回 JSON 对象，不要 Markdown。必须原样返回 schema_version=2 和 request_id。格式为 " +
                            "{schema_version:2,request_id:string,summary:string,rationale:string,subject:{type:string,people_count_class:string,confidence:number,regions:[{left:number,top:number,right:number,bottom:number}]},crop_confidence:number,reframe_tip:string|null,crop_rect:null|{left:number,top:number,right:number,bottom:number},suggestions:[{kind:string,value:number|string}]}。" +
                            "subject.type 仅可为 person/food/object/landscape/mixed/unknown；people_count_class 仅可为 single/pair/group/crowded/not_applicable/unknown。置信度均为 0 到 1。" +
                            "crop_rect 使用 0 到 1 的归一化坐标，基于 upright_non_mirrored_image；只在主体完整且裁剪后保留至少 70% 画面时返回，否则为 null。低置信度时不裁剪。明显需要用户移动手机时返回 reframe_tip。" +
                            "kind 仅可为 exposure_ev（目标曝光补偿 EV）、zoom_ratio（目标倍率）、" +
                            "color_style（NATURAL/WARM/SOFT/VIVID/COOL/MONOCHROME）、" +
                            "preset_id（本地预设 ID）或 preset_strength（0 到 100）或 composition（构图提示）。" +
                            "preset_id 只能从 natural、warm_film、faded_film、cinematic、neon、portrait、food、monochrome 中选择。" +
                            "只能依据用户消息附带的这一张图像和结构化数据进行建议；相机控制必须属于 device_capabilities.supported_controls，" +
                            "风格只能建议上述本地预设库中的 preset_id 和 0 到 100 的 preset_strength；style_locked=true 时不得返回任何风格动作。不得判断人物动作、要求上传其他图像或执行操作。",
                    ),
            )
            .put(JSONObject().put("role", "user").put("content", userMessageContent))
        return JSONObject()
            .put("model", config.model)
            .put("temperature", config.temperature.coerceIn(0.0, 2.0))
            .put("stream", false)
            .put("messages", messages)
    }

    private fun attachImageToAgentRequest(template: JSONObject, imageDataUrl: String, imageAspectRatio: Float): JSONObject {
        val requestBody = JSONObject(template.toString())
        val messages = requestBody.getJSONArray("messages")
        val content = messages.getJSONObject(1).getJSONArray("content")
        val textBlock = content.getJSONObject(0)
        val userContent = JSONObject(textBlock.getString("text"))
        userContent.put(
            "frame_geometry",
            JSONObject()
                .put("coordinate_space", "upright_non_mirrored_image")
                .put("aspect_ratio", imageAspectRatio.toDouble()),
        )
        textBlock.put("text", userContent.toString())
        content.getJSONObject(1).getJSONObject("image_url").put("url", imageDataUrl)
        return requestBody
    }

    private fun updateRequestPreview(config: AgentApiConfig, requestBody: JSONObject) {
        if (!::requestPreviewText.isInitialized) return
        val baseUrl = config.baseUrl.trimEnd('/')
        val endpointPath = config.chatCompletionsPath.trimStart('/')
        val hasKeyInUi = ::apiKeyEditText.isInitialized && apiKeyEditText.text.isNotBlank()
        val authorization = if (hasKeyInUi || agentConfigStore.hasApiKey()) {
            "Authorization: Bearer [已隐藏]"
        } else {
            "Authorization: 未配置"
        }
        requestPreviewText.text = buildString {
            append("POST ")
            append(baseUrl)
            append('/')
            append(endpointPath)
            append("\nContent-Type: application/json\n")
            append(authorization)
            append("\n\n")
            append(requestBody.toString(2))
            append("\n\n图像内容在预览中以占位符显示；点击发送时会采集并压缩一张当前取景帧，与场景/设备参数及 API Key 一起发送到该 HTTPS 服务。不会保存请求图或上传视频。")
        }
    }

    private fun sendCloudRequest() {
        if (cloudRequestInFlight) {
            cancelActiveAgentRequest("已取消分析")
            return
        }
        if (cloudAdviceApplying) return
        if (settingsPage == null) settingsPage = createAgentSettingsPage()
        if (::controlsPanel.isInitialized) controlsPanel.visibility = View.GONE
        val config = readAgentConfig() ?: return
        val typedApiKey = if (::apiKeyEditText.isInitialized) apiKeyEditText.text.toString() else ""
        val apiKey = typedApiKey.ifBlank { agentConfigStore.loadApiKey().orEmpty() }
        if (apiKey.isBlank()) {
            showStatus("未配置 API Key，请在模型设置页填写")
            switchTab("settings")
            return
        }
        val capture = imageCapture ?: run {
            showAgentConfigStatus("相机尚未就绪，无法采集取景帧；请求未发送")
            return
        }
        try {
            agentConfigStore.saveConfig(config)
            if (typedApiKey.isNotBlank()) agentConfigStore.saveApiKey(typedApiKey)
            if (::apiKeyEditText.isInitialized) apiKeyEditText.text.clear()
        } catch (_: Exception) {
            showAgentConfigStatus("配置或 API Key 保存失败；请求未发送")
            return
        }
        switchTab("home")
        val requestId = UUID.randomUUID().toString()
        val sceneAtRequest = latestAssessment
        val intentId = selectedCaptureIntentId()
        val styleLocked = manualColorOverride
        val contextAtRequest = AgentCaptureContext(
            sessionId = agentCaptureContext.sessionId,
            selectedIntentId = intentId,
            previousTurn = agentCaptureContext.previousTurn,
        )
        val request = ActiveAgentRequest(
            requestId = requestId,
            sceneName = sceneAtRequest?.sceneName,
            visualSignature = sceneAtRequest?.visualSignature,
            faceBounds = sceneAtRequest?.faceBounds?.let { android.graphics.RectF(it) },
            intentId = intentId,
            selectedPresetId = selectedPresetId,
            styleLocked = styleLocked,
            startedAtMs = SystemClock.elapsedRealtime(),
            cancellation = AgentRequestCancellation(),
        )
        activeAgentRequest = request
        val requestBodyTemplate = buildAgentRequestBody(
            config, requestId, contextAtRequest, intentId, styleLocked, includeImagePlaceholder = true,
        )
        updateRequestPreview(config, requestBodyTemplate)
        latestCloudAdvice = null
        if (::cloudAdviceText.isInitialized) cloudAdviceText.text = "正在采集一张当前取景帧，然后请求云端建议…"
        showCameraAdvice("正在分析当前取景画面…")
        cloudRequestInFlight = true
        if (::sendCloudRequestButton.isInitialized) {
            sendCloudRequestButton.isEnabled = true
            sendCloudRequestButton.text = "取消 AI 分析"
        }
        if (::cameraAutoCaptureButton.isInitialized) cameraAutoCaptureButton.text = "取消 AI 分析"
        showStatus("正在发送取景帧给 AI...")
        try {
            capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: androidx.camera.core.ImageProxy) {
                if (activeAgentRequest?.requestId != requestId || request.cancellation.isCancelled()) {
                    image.close()
                    return
                }
                cloudApiExecutor.execute {
                    try {
                        request.cancellation.throwIfCancelled()
                        val imageDataUrl = CloudImageEncoder.encodeJpegDataUrl(image, request.cancellation)
                        val rotation = image.imageInfo.rotationDegrees % 180 != 0
                        val uprightWidth = if (rotation) image.height else image.width
                        val uprightHeight = if (rotation) image.width else image.height
                        val imageAspectRatio = uprightWidth.toFloat() / uprightHeight.coerceAtLeast(1)
                        val requestBody = attachImageToAgentRequest(requestBodyTemplate, imageDataUrl, imageAspectRatio)
                        val content = OpenAiCompatibleClient().complete(config, apiKey, requestBody, request.cancellation)
                        val advice = CameraAdviceParser.parse(content, requestId, styleLocked)
                        runOnUiThread {
                            if (activeAgentRequest?.requestId != requestId || request.cancellation.isCancelled()) return@runOnUiThread
                            activeAgentRequest = null
                            cloudRequestInFlight = false
                            if (::sendCloudRequestButton.isInitialized) {
                                sendCloudRequestButton.isEnabled = true
                                sendCloudRequestButton.text = "发送当前画面并获取建议"
                            }
                            if (::cameraAutoCaptureButton.isInitialized) updateCameraAutoCaptureButton()
                            latestCloudAdvice = advice
                            latestCloudAdviceAtMs = SystemClock.elapsedRealtime()
                            latestCloudAdviceSceneName = request.sceneName
                            latestCloudAdviceVisualSignature = request.visualSignature
                            latestCloudAdviceFaceBounds = request.faceBounds
                            latestCloudAdviceRequestId = requestId
                            latestAdviceIntentId = intentId
                            latestCloudAdviceAspectRatio = imageAspectRatio
                            applyCloudAdvice(advice)
                            latestCloudAdvice = null
                        }
                    } catch (error: Exception) {
                        runOnUiThread {
                            if (activeAgentRequest?.requestId != requestId) return@runOnUiThread
                            activeAgentRequest = null
                            cloudRequestInFlight = false
                            if (::sendCloudRequestButton.isInitialized) {
                                sendCloudRequestButton.isEnabled = true
                                sendCloudRequestButton.text = "发送当前画面并获取建议"
                            }
                            if (::cameraAutoCaptureButton.isInitialized) updateCameraAutoCaptureButton()
                            if (::cloudAdviceText.isInitialized) cloudAdviceText.text = "未能获取建议：${error.message ?: "网络或响应错误"}"
                            showCameraAdvice("AI 暂不可用\n${error.message ?: "网络或响应错误"}")
                            showStatus("请求失败：${error.message ?: "网络或响应错误"}")
                        }
                    } finally {
                        image.close()
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                runOnUiThread {
                    if (activeAgentRequest?.requestId != requestId) return@runOnUiThread
                    activeAgentRequest = null
                    cloudRequestInFlight = false
                    if (::sendCloudRequestButton.isInitialized) {
                        sendCloudRequestButton.isEnabled = true
                        sendCloudRequestButton.text = "发送当前画面并获取建议"
                    }
                    if (::cameraAutoCaptureButton.isInitialized) updateCameraAutoCaptureButton()
                    if (::cloudAdviceText.isInitialized) cloudAdviceText.text = "未能采集当前取景帧：${exception.message ?: "相机拍摄错误"}"
                    showCameraAdvice("AI 暂不可用\n未能采集当前取景画面。普通相机仍可拍摄。")
                    showStatus("没有发送网络请求；相机拍摄错误")
                }
            }
            })
        } catch (error: Exception) {
            activeAgentRequest = null
            cloudRequestInFlight = false
            sendCloudRequestButton.isEnabled = true
            sendCloudRequestButton.text = "发送当前画面并获取建议"
            cloudAdviceText.text = "未能开始采集取景帧：${error.message ?: "相机错误"}"
            showCameraAdvice("AI 暂不可用\n未能开始分析当前取景画面。普通相机仍可拍摄。")
            showAgentConfigStatus("没有发送网络请求；请重新打开取景后重试")
        }
    }

    private fun formatCloudAdvice(advice: CameraAdvice): String = buildString {
        append(advice.summary)
        if (advice.rationale.isNotBlank()) append("\n理由：${advice.rationale}")
        append("\n主体：${advice.subjectType} · 人数：${advice.peopleCountClass}")
        advice.cropRect?.let { append("\nAI 建议了新的取景区域，可在画面边框中查看。") }
        if (advice.cropRejected) {
            append("\n建议裁剪可能切到主体或保留画面不足，已保留完整取景。")
        } else if (advice.cropRect == null && advice.cropConfidence < MIN_AGENT_CONFIDENCE) {
            append("\n裁剪把握不足，保留当前完整取景。可以轻微移动手机后重新分析。")
        }
        advice.reframeTip?.let { append("\n重新取景：$it") }
        if (advice.actions.isEmpty()) {
            if (advice.cropRect == null) append("\n没有可应用的参数建议。")
        } else {
            append("\n\n建议：")
            advice.actions.forEach { action ->
                append("\n• ")
                append(
                    when (action.kind) {
                        "exposure_ev" -> "曝光补偿 ${action.numberValue} EV"
                        "zoom_ratio" -> "变焦 ${action.numberValue}×"
                        "color_style" -> "影调 ${ColorStyle.valueOf(action.textValue!!).displayName}"
                        "preset_id" -> "风格预设 ${photoPresetStore.find(action.textValue!!)?.name}"
                        "preset_strength" -> "预设强度 ${action.numberValue?.roundToInt()}%"
                        else -> "构图：${action.textValue}"
                    },
                )
            }
        }
    }

    private fun isCloudAdviceStale(advice: CameraAdvice): Boolean {
        if (advice.requestId != latestCloudAdviceRequestId ||
            SystemClock.elapsedRealtime() - latestCloudAdviceAtMs > CLOUD_ADVICE_TTL_MS ||
            latestAssessment?.sceneName != latestCloudAdviceSceneName
        ) return true
        val assessment = latestAssessment ?: return latestCloudAdviceVisualSignature != null
        val snapshot = ActiveAgentRequest(
            requestId = advice.requestId,
            sceneName = latestCloudAdviceSceneName,
            visualSignature = latestCloudAdviceVisualSignature,
            faceBounds = latestCloudAdviceFaceBounds,
            intentId = latestAdviceIntentId,
            selectedPresetId = selectedPresetId,
            styleLocked = manualColorOverride,
            startedAtMs = latestCloudAdviceAtMs,
            cancellation = AgentRequestCancellation(),
        )
        return hasCompositionChanged(snapshot, assessment)
    }

    private fun applyCloudAdvice(advice: CameraAdvice) {
        if (isCloudAdviceStale(advice)) {
            cloudAdviceText.text = formatCloudAdvice(advice) + "\n\n场景已变化或建议已过期，没有应用参数；请重新请求。"
            showCameraAdvice("AI 建议已过期\n请重新分析当前画面。普通相机仍可拍摄。")
            showAgentConfigStatus("云端建议已过期，没有应用参数")
            return
        }
        val activeCamera = camera ?: run {
            showAgentConfigStatus("相机尚未就绪，建议没有应用")
            showCameraAdvice("相机尚未就绪，AI 建议没有应用。")
            return
        }
        val exposureState = activeCamera.cameraInfo.exposureState
        val zoomState = activeCamera.cameraInfo.zoomState.value
        val snapshot = AgentApplySnapshot(
            exposureIndex = exposureState.exposureCompensationIndex,
            zoomRatio = zoomState?.zoomRatio,
            colorStyle = selectedColorStyle,
            manualColorOverride = manualColorOverride,
            presetId = selectedPresetId,
            presetAmount = selectedPresetAmount,
            cropRect = activeAiCropRect,
            cropAspectRatio = activeAiCropAspectRatio,
        )
        val outcomes = mutableListOf<String>()
        val appliedActions = mutableListOf<String>()
        val skippedActions = mutableListOf<String>()
        val failedActions = mutableListOf<String>()
        val cropStateChanges = advice.cropRect != null || activeAiCropRect != null
        val cropFullyVisible = advice.cropRect?.let {
            compositionOverlay.isCropFullyVisible(it, latestCloudAdviceAspectRatio)
        } ?: true
        var pendingActions = advice.actions.size + if (cropStateChanges) 1 else 0
        var appliedCount = 0
        var skippedCount = 0
        var failedCount = 0
        var compositionHintCount = 0
        latestAgentApplySnapshot = snapshot
        undoCloudAdviceButton.visibility = View.VISIBLE
        undoCloudAdviceButton.isEnabled = false
        cameraUndoButton.visibility = View.VISIBLE
        cameraUndoButton.isEnabled = false
        cloudAdviceText.text = formatCloudAdvice(advice) + "\n\n正在应用建议…"
        showCameraAdvice("${advice.summary}\n正在应用支持的相机参数与取景建议…")
        if (pendingActions == 0) {
            undoCloudAdviceButton.visibility = View.GONE
            cameraUndoButton.visibility = View.GONE
            latestAgentApplySnapshot = null
            cloudAdviceText.append("\n没有可应用的参数建议。")
            agentCaptureContext.previousTurn = AgentCaptureTurnContext(
                requestId = advice.requestId,
                intentId = latestAdviceIntentId,
                sceneName = latestCloudAdviceSceneName.orEmpty(),
                summary = advice.summary.take(MAX_CONTEXT_TEXT_LENGTH),
                subjectType = advice.subjectType,
                peopleCountClass = advice.peopleCountClass,
                appliedActions = emptyList(),
                skippedActions = emptyList(),
                failedActions = emptyList(),
                completedAtMs = SystemClock.elapsedRealtime(),
            )
            showCameraAdvice("${advice.summary}\n没有可应用的参数建议。普通相机仍可拍摄。")
            showAgentConfigStatus("云端没有返回可应用的参数")
            return
        }
        cloudAdviceApplying = true
        if (::sendCloudRequestButton.isInitialized) sendCloudRequestButton.isEnabled = false
        fun recordOutcome(message: String, applied: Boolean, failed: Boolean = false, skipped: Boolean = false) {
            outcomes += message
            if (applied) {
                appliedCount++
                appliedActions += message
            }
            if (failed) {
                failedCount++
                failedActions += message
            }
            if (skipped) {
                skippedCount++
                skippedActions += message
            }
            pendingActions--
            if (pendingActions == 0) {
                cloudAdviceApplying = false
                if (::sendCloudRequestButton.isInitialized) sendCloudRequestButton.isEnabled = true
                undoCloudAdviceButton.isEnabled = appliedCount > 0
                cameraUndoButton.isEnabled = appliedCount > 0
                if (appliedCount == 0) {
                    undoCloudAdviceButton.visibility = View.GONE
                    cameraUndoButton.visibility = View.GONE
                    latestAgentApplySnapshot = null
                }
                val summary = "执行结果：已应用 $appliedCount 项，跳过 $skippedCount 项，失败 $failedCount 项；构图提示 $compositionHintCount 条。"
                agentCaptureContext.previousTurn = AgentCaptureTurnContext(
                    requestId = advice.requestId,
                    intentId = latestAdviceIntentId,
                    sceneName = latestCloudAdviceSceneName.orEmpty(),
                    summary = advice.summary.take(MAX_CONTEXT_TEXT_LENGTH),
                    subjectType = advice.subjectType,
                    peopleCountClass = advice.peopleCountClass,
                    appliedActions = appliedActions.take(MAX_CONTEXT_ACTIONS),
                    skippedActions = skippedActions.take(MAX_CONTEXT_ACTIONS),
                    failedActions = failedActions.take(MAX_CONTEXT_ACTIONS),
                    completedAtMs = SystemClock.elapsedRealtime(),
                )
                cloudAdviceText.text = formatCloudAdvice(advice) + "\n\n" + summary + outcomes.joinToString(separator = "\n• ", prefix = "\n• ")
                cameraAdviceText.text = formatCloudAdvice(advice) + "\n" + summary
                if (captureAutomaticallyAfterAi && appliedCount > 0 && skippedCount == 0 && failedCount == 0) {
                    cameraAdviceText.append("\n即将按当前裁剪结果自动拍摄…")
                    cameraContent.postDelayed({
                        if (captureAutomaticallyAfterAi && cameraContent.isShown && hasWindowFocus() &&
                            !photoCaptureInProgress && !cloudAdviceApplying && !isCloudAdviceStale(advice)
                        ) {
                            takePhoto(activeAiCropRect)
                        }
                    }, AUTO_CAPTURE_PREVIEW_DELAY_MS)
                }
                if (::credentialStatusText.isInitialized) credentialStatusText.text = summary
                showAgentConfigStatus(summary)
            }
        }
        advice.actions.sortedBy { if (it.kind == "preset_id") 0 else if (it.kind == "preset_strength") 1 else 2 }.forEach { action ->
            when (action.kind) {
                "exposure_ev" -> {
                    val state = activeCamera.cameraInfo.exposureState
                    val step = state.exposureCompensationStep.toFloat()
                    val range = state.exposureCompensationRange
                    if (autoModeEnabled || !state.isExposureCompensationSupported || step <= 0f) {
                        recordOutcome("曝光补偿：自动模式启用或设备不支持", applied = false, skipped = true)
                    } else {
                        val minEv = range.lower * step
                        val maxEv = range.upper * step
                        val currentEv = state.exposureCompensationIndex * step
                        val requestedEv = action.numberValue?.toFloat() ?: currentEv
                        val targetEv = requestedEv.coerceIn(
                            maxOf(minEv, currentEv - MAX_CLOUD_EXPOSURE_DELTA_EV),
                            minOf(maxEv, currentEv + MAX_CLOUD_EXPOSURE_DELTA_EV),
                        )
                        val targetIndex = (targetEv / step).roundToInt().coerceIn(range.lower, range.upper)
                        val future = activeCamera.cameraControl.setExposureCompensationIndex(targetIndex)
                        future.addListener({
                            runCatching { future.get() }
                                .onSuccess {
                                    updateExposureLabel()
                                    recordOutcome("曝光补偿：${targetIndex * step} EV", applied = true)
                                }
                                .onFailure {
                                    recordOutcome("曝光补偿：设备未接受建议", applied = false, failed = true)
                                }
                        }, ContextCompat.getMainExecutor(this))
                    }
                }
                "zoom_ratio" -> {
                    val state = activeCamera.cameraInfo.zoomState.value
                    if (state == null || state.maxZoomRatio <= state.minZoomRatio) {
                        recordOutcome("变焦：设备不支持", applied = false, skipped = true)
                    } else {
                        val requested = action.numberValue?.toFloat() ?: state.zoomRatio
                        val target = requested.coerceIn(
                            maxOf(state.minZoomRatio, state.zoomRatio / MAX_CLOUD_ZOOM_FACTOR),
                            minOf(state.maxZoomRatio, state.zoomRatio * MAX_CLOUD_ZOOM_FACTOR),
                        )
                        val future = activeCamera.cameraControl.setZoomRatio(target)
                        future.addListener({
                            runCatching { future.get() }
                                .onSuccess { recordOutcome("变焦：${"%.2f".format(Locale.US, target)}×", applied = true) }
                                .onFailure { recordOutcome("变焦：设备未接受建议", applied = false, failed = true) }
                        }, ContextCompat.getMainExecutor(this))
                    }
                }
                "color_style" -> {
                    if (autoModeEnabled) {
                        recordOutcome("影调：自动影调模式启用", applied = false, skipped = true)
                    } else {
                        selectedColorStyle = ColorStyle.valueOf(action.textValue!!)
                        selectedPresetId = PhotoPresets.all.firstOrNull { it.style == selectedColorStyle }?.id ?: "natural"
                        photoPresetStore.setSelectedId(selectedPresetId)
                        selectedPresetAmount = photoPresetStore.amount(selectedPresetId)
                        manualColorOverride = true
                        updateColorStyleButton()
                        latestAssessment?.let { compositionOverlay.updateAssessment(it, selectedColorStyle) }
                        recordOutcome("影调：${selectedColorStyle.displayName}", applied = true)
                    }
                }
                "preset_id" -> {
                    if (autoModeEnabled) {
                        recordOutcome("风格预设：自动影调模式启用", applied = false, skipped = true)
                    } else {
                        val preset = photoPresetStore.find(action.textValue!!)
                        if (preset == null) {
                            recordOutcome("风格预设：本地不存在该预设", applied = false, skipped = true)
                        } else {
                            selectedPresetId = preset.id
                            photoPresetStore.setSelectedId(preset.id)
                            selectedColorStyle = preset.style
                            selectedPresetAmount = photoPresetStore.amount(preset.id)
                            photoPresetStore.markRecent(preset.id)
                            manualColorOverride = true
                            updateColorStyleButton()
                            latestAssessment?.let { compositionOverlay.updateAssessment(it, selectedColorStyle) }
                            recordOutcome("风格预设：${preset.name}", applied = true)
                        }
                    }
                }
                "preset_strength" -> {
                    if (autoModeEnabled) {
                        recordOutcome("预设强度：自动影调模式启用", applied = false, skipped = true)
                    } else {
                        val amount = action.numberValue?.roundToInt()?.coerceIn(0, 100) ?: selectedPresetAmount
                        selectedPresetAmount = amount
                        photoPresetStore.setAmount(selectedPresetId, amount)
                        updateColorStyleButton()
                        recordOutcome("${photoPresetStore.find(selectedPresetId)?.name ?: "风格预设"} 强度：$amount%", applied = true)
                    }
                }
                "composition" -> {
                    compositionHintCount++
                    recordOutcome("构图建议：${action.textValue}", applied = false)
                }
            }
        }
        if (cropStateChanges) {
            when {
                advice.cropRect != null && cropFullyVisible -> {
                    activeAiCropRect = advice.cropRect
                    activeAiCropAspectRatio = latestCloudAdviceAspectRatio
                    compositionOverlay.setAiCropRect(advice.cropRect, activeAiCropAspectRatio)
                    compositionOverlay.visibility = View.VISIBLE
                    recordOutcome("AI 取景裁剪：已在取景框显示", applied = true)
                }
                advice.cropRect != null -> {
                    activeAiCropRect = null
                    compositionOverlay.setAiCropRect(null, activeAiCropAspectRatio)
                    compositionOverlay.visibility = if (guideEnabled) View.VISIBLE else View.GONE
                    recordOutcome("AI 取景裁剪：超出当前可见取景区域，已跳过", applied = false, skipped = true)
                }
                else -> {
                    activeAiCropRect = null
                    compositionOverlay.setAiCropRect(null, activeAiCropAspectRatio)
                    compositionOverlay.visibility = if (guideEnabled) View.VISIBLE else View.GONE
                    recordOutcome("AI 取景裁剪：已恢复完整取景", applied = true)
                }
            }
        }
    }

    private fun undoLastCloudAdvice() {
        val snapshot = latestAgentApplySnapshot ?: return
        val activeCamera = camera ?: run {
            showAgentConfigStatus("相机尚未就绪，无法撤销")
            return
        }
        undoCloudAdviceButton.isEnabled = false
        cameraUndoButton.isEnabled = false
        cloudAdviceApplying = true
        if (::sendCloudRequestButton.isInitialized) sendCloudRequestButton.isEnabled = false
        var pendingRestores = 0
        var failedRestores = 0
        var restoreSchedulingComplete = false
        fun finishIfReady() {
            if (!restoreSchedulingComplete || pendingRestores != 0) return
            if (failedRestores == 0) {
                latestAgentApplySnapshot = null
                undoCloudAdviceButton.visibility = View.GONE
                cameraUndoButton.visibility = View.GONE
                cloudAdviceApplying = false
                if (::sendCloudRequestButton.isInitialized) sendCloudRequestButton.isEnabled = true
                showCameraAdvice("已撤销上次 AI 调整。")
                showAgentConfigStatus("已撤销上次 AI 调整")
            } else {
                cloudAdviceApplying = false
                if (::sendCloudRequestButton.isInitialized) sendCloudRequestButton.isEnabled = true
                undoCloudAdviceButton.isEnabled = true
                cameraUndoButton.isEnabled = true
                showAgentConfigStatus("撤销未完全成功；可重试，失败 $failedRestores 项")
            }
        }
        fun finishOneRestore(success: Boolean) {
            if (!success) failedRestores++
            pendingRestores--
            finishIfReady()
        }
        val exposureState = activeCamera.cameraInfo.exposureState
        if (exposureState.isExposureCompensationSupported) {
            val target = snapshot.exposureIndex.coerceIn(
                exposureState.exposureCompensationRange.lower,
                exposureState.exposureCompensationRange.upper,
            )
            val future = activeCamera.cameraControl.setExposureCompensationIndex(target)
            pendingRestores++
            future.addListener({
                val success = runCatching { future.get() }.isSuccess
                if (success) updateExposureLabel()
                finishOneRestore(success)
            }, ContextCompat.getMainExecutor(this))
        }
        snapshot.zoomRatio?.let { target ->
            val zoomState = activeCamera.cameraInfo.zoomState.value
            if (zoomState != null && zoomState.maxZoomRatio > zoomState.minZoomRatio) {
                val future = activeCamera.cameraControl.setZoomRatio(target.coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio))
                pendingRestores++
                future.addListener({
                    finishOneRestore(runCatching { future.get() }.isSuccess)
                }, ContextCompat.getMainExecutor(this))
            }
        }
        selectedColorStyle = snapshot.colorStyle
        manualColorOverride = snapshot.manualColorOverride
        selectedPresetId = snapshot.presetId
        photoPresetStore.setSelectedId(selectedPresetId)
        selectedPresetAmount = snapshot.presetAmount
        photoPresetStore.setAmount(selectedPresetId, selectedPresetAmount)
        activeAiCropRect = snapshot.cropRect
        activeAiCropAspectRatio = snapshot.cropAspectRatio
        compositionOverlay.setAiCropRect(snapshot.cropRect, snapshot.cropAspectRatio, animate = false)
        compositionOverlay.visibility = if (guideEnabled || snapshot.cropRect != null) View.VISIBLE else View.GONE
        updateColorStyleButton()
        latestAssessment?.let { compositionOverlay.updateAssessment(it, selectedColorStyle) }
        restoreSchedulingComplete = true
        if (pendingRestores > 0) showAgentConfigStatus("正在撤销上次 AI 调整…")
        finishIfReady()
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(480, 360))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                sceneAnalyzer?.close()
                val analyzer = AgentSceneAnalyzer(::onSceneAssessment)
                analysis.setAnalyzer(analysisExecutor, analyzer)

                provider.unbindAll()
                try {
                    camera = provider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        capture,
                        analysis,
                    )
                    imageAnalysis = analysis
                    sceneAnalyzer = analyzer
                } catch (analysisError: Exception) {
                    provider.unbindAll()
                    analyzer.close()
                    camera = provider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        capture,
                    )
                    imageAnalysis = null
                    sceneAnalyzer = null
                }
                imageCapture = capture
                baselineExposureIndex = camera?.cameraInfo?.exposureState?.exposureCompensationIndex ?: 0
                observeCameraControls()
                autoModeButton.isEnabled = imageAnalysis != null
                if (imageAnalysis == null) {
                    showStatus("取景就绪；此设备不支持实时场景分析")
                } else {
                    showStatus("取景就绪 · 正在分析场景")
                }
            } catch (error: Exception) {
                showStatus("相机启动失败：${error.localizedMessage ?: "未知错误"}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun observeCameraControls() {
        val activeCamera = camera ?: return
        activeCamera.cameraInfo.zoomState.removeObservers(this)
        activeCamera.cameraInfo.zoomState.observe(this) { zoomState ->
            updateZoomControls(zoomState)
        }
        updateExposureLabel()
    }

    private fun updateZoomControls(zoomState: ZoomState) {
        val zoomSupported = zoomState.maxZoomRatio > zoomState.minZoomRatio
        zoomSeekBarUpdating = true
        zoomSeekBar.progress = (zoomState.linearZoom * zoomSeekBar.max)
            .roundToInt()
            .coerceIn(0, zoomSeekBar.max)
        zoomSeekBarUpdating = false
        zoomSeekBar.isEnabled = zoomSupported
        zoomLabel.text = if (zoomSupported) {
            "变焦 ${"%.1f".format(Locale.US, zoomState.zoomRatio)}×"
        } else {
            "此镜头不支持变焦"
        }
    }

    private fun onSceneAssessment(assessment: SceneAssessment) {
        val previous = latestAssessment
        latestAssessment = assessment
        if (agentCaptureContext.selectedIntentId != null &&
            CaptureIntentCatalog.find(assessment.sceneName, agentCaptureContext.selectedIntentId) == null
        ) {
            agentCaptureContext.selectedIntentId = null
        }
        updateCaptureIntentButton()
        activeAgentRequest?.let { request ->
            if (hasCompositionChanged(request, assessment)) cancelActiveAgentRequest("取景画面已明显变化，请重新分析")
        }
        if (previous != null && previous.sceneName != assessment.sceneName && activeAgentRequest != null) {
            cancelActiveAgentRequest("场景已变化，请重新分析")
        }
        if (previous != null && previous.sceneName != assessment.sceneName) {
            agentCaptureContext.previousTurn = null
        }
        if (latestCloudAdvice != null && assessment.sceneName != latestCloudAdviceSceneName) {
            latestCloudAdvice = null
            if (::cloudAdviceText.isInitialized) cloudAdviceText.append("\n\n场景已变化，请重新获取建议。")
        }
        recommendedColorStyle = assessment.recommendedColorStyle
        if (autoModeEnabled) {
            selectedColorStyle = recommendedColorStyle
            selectedPresetId = PhotoPresets.all.firstOrNull { it.style == selectedColorStyle }?.id ?: "natural"
            selectedPresetAmount = photoPresetStore.amount(selectedPresetId)
            photoPresetStore.setSelectedId(selectedPresetId)
            manualColorOverride = false
            applyAutoExposure(assessment.exposureCompensationOffset)
            applyAutoFocus(assessment)
        } else if (!manualColorOverride) {
            selectedColorStyle = ColorStyle.NATURAL
        }
        updateColorStyleButton()
        compositionOverlay.updateAssessment(assessment, selectedColorStyle)
        if (::statusText.isInitialized) {
            val confidenceLabel = if (assessment.confidence > 0f) {
                "${(assessment.confidence * 100).roundToInt()}%"
            } else {
                "未分类"
            }
            statusText.text = "${assessment.sceneName} · $confidenceLabel\n${assessment.compositionHint}"
        }
    }

    private fun toggleAutoMode() {
        if (imageAnalysis == null) {
            showStatus("当前相机没有可用的实时分析流")
            return
        }
        autoModeEnabled = !autoModeEnabled
        autoModeButton.text = if (autoModeEnabled) "自动调整：开启" else "自动调整：关闭"
        if (autoModeEnabled) {
            baselineExposureIndex = camera?.cameraInfo?.exposureState?.exposureCompensationIndex ?: 0
            lastAutoExposureIndex = null
            lastMeteringPoint = null
            manualColorOverride = false
            latestAssessment?.let { assessment ->
                selectedColorStyle = assessment.recommendedColorStyle
                selectedPresetId = PhotoPresets.all.firstOrNull { it.style == selectedColorStyle }?.id ?: "natural"
                selectedPresetAmount = photoPresetStore.amount(selectedPresetId)
                photoPresetStore.setSelectedId(selectedPresetId)
                applyAutoExposure(assessment.exposureCompensationOffset)
            }
        } else {
            restoreBaselineExposure()
            lastMeteringPoint = null
        }
        updateColorStyleButton()
        latestAssessment?.let { compositionOverlay.updateAssessment(it, selectedColorStyle) }
        updateExposureLabel()
        if (latestAssessment == null) {
            showStatus(if (autoModeEnabled) "自动调整已开启，等待场景识别" else "自动调整已关闭")
        }
    }

    private fun cycleColorStyle() {
        val styles = ColorStyle.values()
        val nextIndex = (styles.indexOf(selectedColorStyle) + 1) % styles.size
        selectedColorStyle = styles[nextIndex]
        selectedPresetId = PhotoPresets.all.firstOrNull { it.style == selectedColorStyle }?.id ?: "natural"
        photoPresetStore.setSelectedId(selectedPresetId)
        selectedPresetAmount = photoPresetStore.amount(selectedPresetId)
        photoPresetStore.markRecent(selectedPresetId)
        manualColorOverride = true
        updateColorStyleButton()
        animatePresetSelection()
        latestAssessment?.let { compositionOverlay.updateAssessment(it, selectedColorStyle) }
    }

    private fun updateColorStyleButton() {
        val selectedPresetName = photoPresetStore.find(selectedPresetId)?.name ?: selectedColorStyle.displayName
        if (::colorStyleButton.isInitialized) {
            colorStyleButton.isEnabled = !autoModeEnabled
            val recommended = recommendedColorStyle.displayName
            colorStyleButton.text = if (autoModeEnabled) {
                "自动影调：${selectedColorStyle.displayName}"
            } else {
                "影调：$selectedPresetName ${selectedPresetAmount}% · 建议：$recommended"
            }
        }
        if (::quickColorButton.isInitialized) {
            quickColorButton.isEnabled = !autoModeEnabled
            quickColorButton.text = selectedPresetName
            quickColorButton.contentDescription = "当前风格：$selectedPresetName，点按切换预设"
            quickColorButton.background = cameraPillBackground(
                selected = true,
                accentColor = presetAccentColor(photoPresetStore.find(selectedPresetId)),
            )
        }
        refreshPresetPreview()
    }

    private fun refreshPresetPreview() {
        if (!::previewView.isInitialized || !::compositionOverlay.isInitialized || !::photoPresetStore.isInitialized) return
        val preset = photoPresetStore.find(selectedPresetId) ?: PhotoPresets.all.first()
        val recipe = photoPresetStore.recipe(preset.id)
        val signature = "${preset.id}:${selectedPresetAmount}:$recipe"
        if (signature == appliedPreviewSignature) return
        // Keep preset rendering on the overlay; applying RenderEffect to PreviewView can
        // interrupt CameraX's live surface composition on some devices.
        compositionOverlay.setPreviewTint(PresetPreviewEffect.fallbackTint(preset, recipe, selectedPresetAmount))
        appliedPreviewSignature = signature
        updatePresetPreviewStripSelection()
    }

    private fun createPresetPreviewStrip(): View {
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(14), 0, dp(14), 0)
        }
        presetPreviewStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        scroll.addView(presetPreviewStrip, android.widget.FrameLayout.LayoutParams(-2, -1))
        rebuildPresetPreviewStrip()
        return scroll
    }

    private fun rebuildPresetPreviewStrip() {
        if (!::presetPreviewStrip.isInitialized) return
        presetPreviewStrip.removeAllViews()
        photoPresetStore.allPresets().forEach { preset ->
            val accentColor = presetAccentColor(preset)
            val chipLabel = TextView(this).apply {
                text = preset.name
                textSize = 11f
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(Color.rgb(232, 235, 241))
                setPadding(dp(7), 0, 0, 0)
                maxLines = 1
            }
            val chip = LinearLayout(this).apply {
                tag = preset.id
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(11), dp(6), dp(13), dp(6))
                background = cameraPillBackground(preset.id == selectedPresetId, accentColor)
                contentDescription = "预览${preset.name}预设"
                isClickable = true
                isFocusable = true
                isSelected = preset.id == selectedPresetId
                addView(View(this@MainActivity).apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(accentColor)
                    }
                }, LinearLayout.LayoutParams(dp(10), dp(10)))
                addView(chipLabel, LinearLayout.LayoutParams(-2, -2))
                setOnClickListener {
                    selectPreset(preset.id, navigateToPresets = false)
                    animatePresetSelection()
                }
            }
            presetPreviewStrip.addView(chip, LinearLayout.LayoutParams(-2, dp(48)).apply {
                rightMargin = dp(8)
            })
        }
        updatePresetPreviewStripSelection()
    }

    private fun updatePresetPreviewStripSelection() {
        if (!::presetPreviewStrip.isInitialized) return
        for (index in 0 until presetPreviewStrip.childCount) {
            val chip = presetPreviewStrip.getChildAt(index) as? LinearLayout ?: continue
            val selected = chip.tag == selectedPresetId
            val preset = photoPresetStore.find(chip.tag as? String ?: "")
            val accentColor = presetAccentColor(preset)
            chip.isSelected = selected
            chip.background = cameraPillBackground(selected, accentColor)
            (chip.getChildAt(1) as? TextView)?.setTextColor(
                if (selected) Color.WHITE else Color.rgb(205, 211, 221),
            )
        }
    }

    private fun presetAccentColor(preset: PhotoPreset?): Int = when (preset?.style) {
        ColorStyle.WARM, ColorStyle.PORTRAIT -> Color.rgb(242, 177, 132)
        ColorStyle.FADED_FILM -> Color.rgb(211, 185, 145)
        ColorStyle.CINEMATIC, ColorStyle.COOL -> Color.rgb(143, 187, 226)
        ColorStyle.NEON, ColorStyle.VIVID -> Color.rgb(199, 163, 255)
        ColorStyle.FOOD -> Color.rgb(248, 190, 117)
        ColorStyle.MONOCHROME -> Color.rgb(210, 216, 224)
        else -> Color.rgb(183, 204, 239)
    }

    private fun cameraPillBackground(selected: Boolean, accentColor: Int): android.graphics.drawable.Drawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(18).toFloat()
            setColor(if (selected) Color.rgb(39, 49, 67) else Color.argb(174, 12, 16, 24))
            val strokeColor = if (selected) {
                Color.argb(150, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
            } else {
                Color.argb(74, 255, 255, 255)
            }
            setStroke(dp(1), strokeColor)
        }

    private fun updateCameraAutoCaptureButton() {
        if (::cameraAutoCaptureButton.isInitialized) {
            cameraAutoCaptureButton.text = when {
                cloudRequestInFlight -> "取消 AI 分析"
                captureAutomaticallyAfterAi -> "AI 后自动拍摄：开"
                else -> "AI 后自动拍摄：关"
            }
        }
    }

    private fun showCameraAdvice(message: String, show: Boolean = true) {
        if (!::cameraAdvicePanel.isInitialized || !::cameraAdviceText.isInitialized) return
        if (!show) {
            cameraAdvicePanel.visibility = View.GONE
            return
        }
        cameraAdviceText.text = message
        if (cameraAdvicePanel.visibility != View.VISIBLE) {
            cameraAdvicePanel.alpha = 0f
            cameraAdvicePanel.visibility = View.VISIBLE
            cameraAdvicePanel.animate().alpha(1f).setDuration(CAMERA_ADVICE_FADE_DURATION_MS).start()
        }
    }

    private fun animatePresetSelection() {
        if (!::quickColorButton.isInitialized || android.provider.Settings.Global.getFloat(
                contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        ) return
        quickColorButton.animate().cancel()
        quickColorButton.animate()
            .scaleX(0.94f)
            .scaleY(0.94f)
            .setDuration(80)
            .withEndAction {
                quickColorButton.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(120)
                    .start()
            }
            .start()
    }

    private fun applyAutoExposure(offset: Int) {
        val activeCamera = camera ?: return
        val state = activeCamera.cameraInfo.exposureState
        if (!state.isExposureCompensationSupported) return
        val range = state.exposureCompensationRange
        val target = (baselineExposureIndex + offset).coerceIn(range.lower, range.upper)
        if (target == lastAutoExposureIndex || target == state.exposureCompensationIndex) {
            lastAutoExposureIndex = target
            return
        }
        lastAutoExposureIndex = target
        val future = activeCamera.cameraControl.setExposureCompensationIndex(target)
        future.addListener({
            try {
                future.get()
                updateExposureLabel()
            } catch (error: Exception) {
                showStatus("自动曝光调整失败：${error.cause?.localizedMessage ?: error.localizedMessage ?: "设备未接受请求"}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun restoreBaselineExposure() {
        val activeCamera = camera ?: return
        val state = activeCamera.cameraInfo.exposureState
        if (!state.isExposureCompensationSupported) return
        val target = baselineExposureIndex.coerceIn(
            state.exposureCompensationRange.lower,
            state.exposureCompensationRange.upper,
        )
        lastAutoExposureIndex = null
        val future = activeCamera.cameraControl.setExposureCompensationIndex(target)
        future.addListener({
            try {
                future.get()
                updateExposureLabel()
            } catch (error: Exception) {
                showStatus("恢复曝光失败：${error.cause?.localizedMessage ?: error.localizedMessage ?: "设备未接受请求"}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun applyAutoFocus(assessment: SceneAssessment) {
        val faceBounds = assessment.faceBounds ?: return
        val activeCamera = camera ?: return
        if (previewView.width <= 0 || previewView.height <= 0) return
        val center = compositionOverlay.mapNormalizedPoint(
            faceBounds.centerX(),
            faceBounds.centerY(),
            assessment.imageAspectRatio,
        )
        val normalizedX = center.x / previewView.width
        val normalizedY = center.y / previewView.height
        val previous = lastMeteringPoint
        if (previous != null && kotlin.math.abs(previous.x - normalizedX) < FOCUS_UPDATE_THRESHOLD &&
            kotlin.math.abs(previous.y - normalizedY) < FOCUS_UPDATE_THRESHOLD
        ) return

        lastMeteringPoint = PointF(normalizedX, normalizedY)
        val point = previewView.meteringPointFactory.createPoint(center.x, center.y)
        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
        ).setAutoCancelDuration(FOCUS_AUTO_CANCEL_SECONDS, TimeUnit.SECONDS).build()
        val future = activeCamera.cameraControl.startFocusAndMetering(action)
        future.addListener({
            try {
                future.get()
            } catch (error: Exception) {
                showStatus("自动对焦区域调整失败：${error.cause?.localizedMessage ?: error.localizedMessage ?: "设备未接受请求"}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun applyZoomProgress(progress: Int) {
        val activeCamera = camera ?: return
        val linearZoom = progress.toFloat() / zoomSeekBar.max.coerceAtLeast(1)
        val zoomFuture = activeCamera.cameraControl.setLinearZoom(linearZoom)
        zoomFuture.addListener({
            try {
                zoomFuture.get()
            } catch (error: Exception) {
                showStatus("变焦调整失败：${error.cause?.localizedMessage ?: error.localizedMessage ?: "设备未接受请求"}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun adjustExposure(direction: Int) {
        if (autoModeEnabled) {
            showStatus("自动调整开启时，曝光由 Agent 控制")
            return
        }
        val activeCamera = camera ?: return
        val exposureState = activeCamera.cameraInfo.exposureState
        if (!exposureState.isExposureCompensationSupported) {
            showStatus("此设备不支持曝光补偿")
            return
        }
        val range = exposureState.exposureCompensationRange
        val nextIndex = (exposureState.exposureCompensationIndex + direction).coerceIn(range.lower, range.upper)
        val exposureFuture = activeCamera.cameraControl.setExposureCompensationIndex(nextIndex)
        exposureFuture.addListener({
            try {
                exposureFuture.get()
                updateExposureLabel()
            } catch (error: Exception) {
                showStatus("曝光调整失败：${error.cause?.localizedMessage ?: error.localizedMessage ?: "设备未接受请求"}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun updateExposureLabel() {
        val activeCamera = camera ?: return
        val state = activeCamera.cameraInfo.exposureState
        exposureDownButton.isEnabled = !autoModeEnabled && state.isExposureCompensationSupported &&
            state.exposureCompensationIndex > state.exposureCompensationRange.lower
        exposureUpButton.isEnabled = !autoModeEnabled && state.isExposureCompensationSupported &&
            state.exposureCompensationIndex < state.exposureCompensationRange.upper
        if (!state.isExposureCompensationSupported) {
            exposureLabel.text = "曝光补偿不可用"
            return
        }
        val ev = state.exposureCompensationIndex * state.exposureCompensationStep.toFloat()
        exposureLabel.text = "曝光 ${if (ev > 0) "+" else ""}${"%.1f".format(Locale.US, ev)} EV"
    }

    private fun takePhoto(cropRect: NormalizedCropRect? = activeAiCropRect) {
        if (photoCaptureInProgress) return
        if (activeAgentRequest != null) cancelActiveAgentRequest("已开始拍摄，AI 建议已取消")
        val capture = imageCapture ?: run {
            showStatus("相机尚未就绪")
            return
        }
        photoCaptureInProgress = true
        shutterButton.isEnabled = false
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val capturedFile = File(cacheDir, "capture_$timestamp.jpg")
        val presetAtCapture = (photoPresetStore.find(selectedPresetId) ?: PhotoPresets.all.first())
            .copy(recipe = photoPresetStore.recipe(selectedPresetId))
        val amountAtCapture = selectedPresetAmount
        val output = ImageCapture.OutputFileOptions.Builder(capturedFile).build()

        capture.takePicture(output, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                photoProcessingExecutor.execute {
                    prepareCapturedPhoto(capturedFile, timestamp, presetAtCapture, amountAtCapture, cropRect)
                }
            }

            override fun onError(exception: ImageCaptureException) {
                capturedFile.delete()
                runOnUiThread {
                    photoCaptureInProgress = false
                    shutterButton.isEnabled = true
                    showStatus("拍摄失败：${exception.localizedMessage ?: "未知错误"}")
                }
            }
        })
    }

    private fun prepareCapturedPhoto(
        capturedFile: File,
        timestamp: String,
        preset: PhotoPreset,
        amount: Int,
        cropRect: NormalizedCropRect?,
    ) {
        var photoInputFile = capturedFile
        val croppedOnly = cropRect != null
        if (cropRect != null) {
            val croppedFile = File(cacheDir, "cropped_$timestamp.jpg")
            try {
                PhotoCropProcessor.crop(capturedFile, croppedFile, cropRect)
                capturedFile.delete()
                photoInputFile = croppedFile
            } catch (error: Exception) {
                capturedFile.delete()
                croppedFile.delete()
                runOnUiThread {
                    activeAiCropRect = null
                    compositionOverlay.setAiCropRect(null, activeAiCropAspectRatio)
                    compositionOverlay.visibility = if (guideEnabled) View.VISIBLE else View.GONE
                    photoCaptureInProgress = false
                    shutterButton.isEnabled = true
                    showCameraAdvice("AI 裁剪无法完成，未保存未裁剪照片。已恢复普通取景，你可以直接拍摄或重新获取建议。")
                    showStatus("照片裁剪失败：${error.localizedMessage ?: "输出区域无效"}")
                }
                return
            } catch (error: OutOfMemoryError) {
                capturedFile.delete()
                croppedFile.delete()
                runOnUiThread {
                    activeAiCropRect = null
                    compositionOverlay.setAiCropRect(null, activeAiCropAspectRatio)
                    compositionOverlay.visibility = if (guideEnabled) View.VISIBLE else View.GONE
                    photoCaptureInProgress = false
                    shutterButton.isEnabled = true
                    showCameraAdvice("设备内存不足，未保存未裁剪照片。已恢复普通取景，你可以直接拍摄。")
                    showStatus("设备内存不足，裁剪失败")
                }
                return
            }
        }
        val processedFile = File(cacheDir, "processed_$timestamp.jpg")
        var outputFile = photoInputFile
        var styleProcessingFailed = false
        if (amount > 0 && (preset.style != ColorStyle.NATURAL || preset.recipe != PhotoRecipe())) {
            try {
                PhotoColorProcessor.process(photoInputFile, processedFile, preset, preset.recipe, amount)
                outputFile = processedFile
            } catch (_: Exception) {
                styleProcessingFailed = true
            } catch (_: OutOfMemoryError) {
                styleProcessingFailed = true
            }
        }

        val previewFile = outputFile
        runOnUiThread {
            showCapturePreview(
                photoInputFile,
                previewFile,
                processedFile,
                timestamp,
                preset,
                amount,
                styleProcessingFailed,
                croppedOnly,
            )
        }
    }

    private fun showCapturePreview(
        originalFile: File,
        processedFile: File,
        temporaryProcessedFile: File,
        timestamp: String,
        preset: PhotoPreset,
        amount: Int,
        processingFailed: Boolean,
        croppedOnly: Boolean,
    ) {
        fun previewBitmap(file: File): android.graphics.Bitmap? {
            val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
            return android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
        }
        var currentAmount = amount
        var processingRevision = 0
        var previewBusy = false
        var currentProcessedFile = if (processedFile.exists()) processedFile else originalFile
        var currentProcessedBitmap = previewBitmap(currentProcessedFile)
        val originalBitmap = previewBitmap(originalFile)
        val image = android.widget.ImageView(this).apply {
            setImageBitmap(currentProcessedBitmap ?: originalBitmap)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
        }
        val title = TextView(this).apply {
            text = when {
                processingFailed && croppedOnly -> "裁剪已完成 · 风格处理失败"
                processingFailed -> "处理失败，当前展示原片"
                croppedOnly -> "AI 裁剪成片 · ${preset.name} ${currentAmount}%"
                else -> "成片预览 · ${preset.name} ${currentAmount}%"
            }
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val previewContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addView(title)
            addView(image, LinearLayout.LayoutParams(-1, dp(300)))
        }
        val amountLabel = settingDescription("风格强度 · ${currentAmount}%")
        var saveButton: Button? = null
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var pendingPreviewRunnable: Runnable? = null
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle(if (croppedOnly) "AI 裁剪预览" else "拍摄预览")
            .setView(previewContent)
            .setPositiveButton(
                when {
                    croppedOnly && processingFailed -> "保存裁剪照片"
                    croppedOnly -> "保存裁剪成片"
                    processingFailed -> "保存原片"
                    else -> "保存成片"
                },
                null,
            )
            .setNeutralButton(if (processingFailed || croppedOnly) "" else "保存原片", null)
            .setNegativeButton("重拍", null)
            .create()

        if (!processingFailed) {
            previewContent.addView(amountLabel)
            previewContent.addView(SeekBar(this).apply {
                max = 100
                progress = currentAmount
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        processingRevision++
                        amountLabel.text = "风格强度 · ${progress}% · 正在更新预览"
                        saveButton?.isEnabled = false
                        pendingPreviewRunnable?.let(mainHandler::removeCallbacks)
                        val revision = processingRevision
                        val amountToRender = progress
                        pendingPreviewRunnable = Runnable {
                            if (!dialog.isShowing) return@Runnable
                            previewBusy = true
                            val previewFile = File(cacheDir, "preview_${timestamp}_$revision.jpg")
                            photoProcessingExecutor.execute {
                                val resultBitmap = try {
                                    PhotoColorProcessor.process(originalFile, previewFile, preset, preset.recipe, amountToRender)
                                    previewBitmap(previewFile)
                                } catch (_: Exception) {
                                    null
                                } catch (_: OutOfMemoryError) {
                                    null
                                }
                                runOnUiThread {
                                    previewBusy = false
                                    if (!dialog.isShowing || revision != processingRevision || resultBitmap == null) {
                                        resultBitmap?.recycle()
                                        previewFile.delete()
                                        if (dialog.isShowing && revision == processingRevision) {
                                            amountLabel.text = "风格強度 · ${currentAmount}% · 预览更新失败"
                                            saveButton?.isEnabled = true
                                        }
                                        return@runOnUiThread
                                    }
                                    val previousFile = currentProcessedFile
                                    val previousBitmap = currentProcessedBitmap
                                    currentProcessedFile = previewFile
                                    currentProcessedBitmap = resultBitmap
                                    currentAmount = amountToRender
                                    image.animate().cancel()
                                    image.animate().alpha(0.55f).setDuration(70L).withEndAction {
                                        image.setImageBitmap(currentProcessedBitmap ?: originalBitmap)
                                        image.animate().alpha(1f).setDuration(110L).start()
                                    }.start()
                                    title.text = if (croppedOnly) {
                                        "AI 裁剪成片 · ${preset.name} ${currentAmount}%"
                                    } else {
                                        "成片预览 · ${preset.name} ${currentAmount}%"
                                    }
                                    amountLabel.text = "风格强度 · ${currentAmount}%"
                                    saveButton?.isEnabled = true
                                    previousBitmap?.takeIf { it !== originalBitmap }?.recycle()
                                    if (previousFile != originalFile && previousFile != temporaryProcessedFile) previousFile.delete()
                                }
                            }
                        }
                        mainHandler.postDelayed(pendingPreviewRunnable!!, PREVIEW_EDIT_DEBOUNCE_MS)
                    }
                    override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar?) = Unit
                })
            })
        }

        previewContent.addView(Button(this).apply {
            text = if (croppedOnly) "按住查看未调色裁剪" else "按住查看原片"
            setOnTouchListener { _, event ->
                val showOriginal = event.action == android.view.MotionEvent.ACTION_DOWN
                image.setImageBitmap(if (showOriginal) originalBitmap else currentProcessedBitmap ?: originalBitmap)
                title.text = if (showOriginal) {
                    if (croppedOnly) "未调色裁剪 · 松开返回成片" else "原片对比 · 松开返回成片"
                } else if (croppedOnly) {
                    "AI 裁剪成片 · ${preset.name} ${currentAmount}%"
                } else {
                    "成片预览 · ${preset.name} ${currentAmount}%"
                }
                showOriginal
            }
        }, LinearLayout.LayoutParams(-1, dp(42)))

        dialog.setOnDismissListener {
            pendingPreviewRunnable?.let(mainHandler::removeCallbacks)
            photoCaptureInProgress = false
            shutterButton.isEnabled = true
            if (currentProcessedBitmap !== originalBitmap) currentProcessedBitmap?.recycle()
            originalBitmap?.recycle()
            originalFile.delete()
            temporaryProcessedFile.delete()
            if (currentProcessedFile != originalFile && currentProcessedFile != temporaryProcessedFile) currentProcessedFile.delete()
        }
        dialog.show()
        saveButton = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
        if (croppedOnly || processingFailed || preset.style == ColorStyle.NATURAL || amount == 0) {
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).visibility = View.GONE
        }
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (previewBusy) return@setOnClickListener
            val file = if (processingFailed) originalFile else currentProcessedFile
            val label = when {
                croppedOnly && processingFailed -> "AI裁剪"
                croppedOnly -> "AI裁剪_${preset.name}"
                processingFailed -> "原片"
                else -> preset.name
            }
            if (savePhotoToGallery(file, timestamp, label)) {
                if (!processingFailed) {
                    photoPresetStore.setAmount(preset.id, currentAmount)
                    if (selectedPresetId == preset.id) {
                        selectedPresetAmount = currentAmount
                        updateColorStyleButton()
                    }
                }
                dialog.dismiss()
            }
        }
        dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            if (savePhotoToGallery(originalFile, timestamp, "原片")) dialog.dismiss()
        }
        dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            dialog.dismiss()
            takePhoto(if (croppedOnly) activeAiCropRect else null)
        }
    }

    private fun savePhotoToGallery(file: File, timestamp: String, label: String): Boolean {
        var photoUri: android.net.Uri? = null
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "CameraAgent_${timestamp}_$label.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraAgent")
            }
            photoUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("无法创建相册项目")
            val output = contentResolver.openOutputStream(photoUri, "w") ?: error("无法写入相册项目")
            output.use { destination -> file.inputStream().use { source -> source.copyTo(destination) } }
            showStatus("已保存${label}到 Pictures/CameraAgent")
            return true
        } catch (error: Exception) {
            photoUri?.let { contentResolver.delete(it, null, null) }
            showStatus("照片保存失败：${error.localizedMessage ?: "未知错误"}")
            return false
        }
    }

    private fun showCapabilityReport() {
        val activeCamera = camera ?: run {
            showStatus("相机尚未就绪")
            return
        }
        val report = try {
            CameraCapabilityReport.create(
                activeCamera,
                activeCamera.cameraInfo.zoomState.value,
            )
        } catch (error: Exception) {
            "读取相机能力失败：${error.localizedMessage ?: "未知错误"}"
        }
        val reportView = TextView(this).apply {
            text = report
            textSize = 14f
            setTextColor(Color.BLACK)
            setPadding(24, 16, 24, 16)
            setTextIsSelectable(true)
        }
        val scrollView = ScrollView(this).apply {
            addView(reportView)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("当前后置相机能力")
            .setView(scrollView)
            .setPositiveButton("复制报告") { _, _ ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Camera Agent capability report", report))
                showStatus("设备能力报告已复制")
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun hasCameraPermission(): Boolean = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.CAMERA,
    ) == PackageManager.PERMISSION_GRANTED

    private fun showStatus(message: String) {
        if (::statusText.isInitialized) {
            statusText.text = message
        } else {
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun matchWidthWrapHeight() = LinearLayout.LayoutParams(-1, -2)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val FOCUS_UPDATE_THRESHOLD = 0.06f
        const val FACE_MOVE_THRESHOLD = 0.12f
        const val VISUAL_CHANGE_THRESHOLD = 18
        const val MIN_AGENT_CONFIDENCE = 0.65f
        const val MAX_CONTEXT_TEXT_LENGTH = 200
        const val MAX_CONTEXT_ACTIONS = 6
        const val FOCUS_AUTO_CANCEL_SECONDS = 3L
        const val CLOUD_ADVICE_TTL_MS = 30_000L
        const val MAX_CLOUD_EXPOSURE_DELTA_EV = 1.0f
        const val MAX_CLOUD_ZOOM_FACTOR = 1.5f
        const val CAMERA_PREFERENCES = "camera_agent_preferences"
        const val KEY_AUTO_CAPTURE_AFTER_AI = "auto_capture_after_ai"
        const val AUTO_CAPTURE_PREVIEW_DELAY_MS = 420L
        const val CAMERA_ADVICE_FADE_DURATION_MS = 160L
        const val PREVIEW_EDIT_DEBOUNCE_MS = 140L
    }
}
