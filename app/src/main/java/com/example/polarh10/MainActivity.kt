package com.example.polarh10

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.polarh10.db.DatabaseHelper
import com.example.polarh10.model.AccSample
import com.example.polarh10.model.EcgSample
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.AxisBase
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.polar.androidcommunications.api.ble.model.DisInfo
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApiCallback
import com.polar.sdk.api.PolarBleApiDefaultImpl
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarHealthThermometerData
import com.polar.sdk.api.model.PolarHrData
import com.polar.sdk.api.model.PolarSensorSetting
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * ============================================================
 * 主界面 Activity —— 应用的入口与总控
 * ============================================================
 * 本类承担六大功能域（代码中均以横幅注释标出）：
 *
 *  【配对/发现】  扫描附近 Polar 设备并让用户选择          —— scanDevices / showDeviceDialog
 *  【通信】      Polar BLE SDK 初始化、连接管理与回调      —— initPolarApi / setupButtons
 *  【数据采集】   HR 心率通知、ECG 心电流、ACC 加速度流     —— hrNotificationReceived / toggleEcg / toggleAcc
 *  【数据记录】   记录会话管理，把采集到的数据写入 SQLite   —— toggleSession / 各流回调内的 insert 调用
 *  【实时图表】   主界面 60 秒滚动心率折线图               —— setupLiveChart / addLiveHrPoint
 *  【系统】      蓝牙运行时权限申请、生命周期清理           —— requestPermissionsIfNeeded / onDestroy
 *
 * SDK 用法参考官方文档：
 * https://github.com/polarofficial/polar-ble-sdk/blob/master/documentation/products/PolarH10.md
 */
class MainActivity : AppCompatActivity() {

    companion object {
        /** Logcat 日志标签 */
        private const val TAG = "PolarH10"

        /** 运行时权限请求码（【系统】权限申请回调时使用） */
        private const val REQ_PERMISSIONS = 42

        /** 【数据采集-ACC】H10 加速度计可选采样率档位（官方文档：25/50/100/200Hz） */
        private val ACC_RATE_OPTIONS = listOf(25, 50, 100, 200)

        /** 【数据采集-ACC】H10 加速度计可选量程档位（官方文档：2/4/8G） */
        private val ACC_RANGE_OPTIONS = listOf(2, 4, 8)
    }

    // ------------------------------------------------------------
    // 核心对象
    // ------------------------------------------------------------

    /** 【通信】Polar BLE SDK 主接口：扫描、连接、数据流都通过它调用 */
    private lateinit var api: PolarBleApi

    /** 【数据记录】SQLite 数据库帮助类：会话与样本的写入/查询入口 */
    private lateinit var db: DatabaseHelper

    // ------------------------------------------------------------
    // 界面控件
    // ------------------------------------------------------------

    /** 设备 ID 输入框（可手动输入，也可由扫描结果填入） */
    private lateinit var editDeviceId: EditText

    /** 连接状态文本（未连接/连接中/已连接/已断开） */
    private lateinit var textStatus: TextView

    /** 设备电量文本 */
    private lateinit var textBattery: TextView

    /** 大号心率数值（bpm） */
    private lateinit var textHr: TextView

    /** RR 间期文本（ms） */
    private lateinit var textRr: TextView

    /** 当前会话已保存样本计数文本 */
    private lateinit var textCounts: TextView

    /** 【配对】扫描设备按钮 */
    private lateinit var btnScan: Button

    /** 【通信】连接/断开按钮（同一按钮根据状态切换） */
    private lateinit var btnConnect: Button

    /** 【数据记录】开始/结束记录会话按钮 */
    private lateinit var btnSession: Button

    /** 【数据采集-ECG】ECG 流开关按钮 */
    private lateinit var btnEcg: Button

    /** 【数据采集-ACC】ACC 流开关按钮 */
    private lateinit var btnAcc: Button

    /** 【历史查询】跳转到历史记录页按钮 */
    private lateinit var btnHistory: Button

    /** 【数据采集-ACC】采样率下拉框（25/50/100/200Hz） */
    private lateinit var spinnerAccRate: Spinner

    /** 【数据采集-ACC】量程下拉框（2/4/8G） */
    private lateinit var spinnerAccRange: Spinner

    // ------------------------------------------------------------
    // 运行状态
    // ------------------------------------------------------------

    /** 【通信】当前已连接的设备 ID；null 表示未连接 */
    private var connectedDeviceId: String? = null

    /** 【通信】在线流功能（ECG/ACC）是否已就绪（等 bleSdkFeatureReady 回调置位） */
    private var streamingFeatureReady = false

    /** 【数据记录】当前记录会话的数据库 id；-1 表示未在记录（此时数据仅实时显示不保存） */
    private var sessionId: Long = -1L

    /** 【配对】设备扫描订阅（RxJava Disposable，用于取消扫描） */
    private var scanDisposable: Disposable? = null

    /** 【数据采集-ECG】ECG 数据流订阅；非 null 表示 ECG 流正在运行 */
    private var ecgDisposable: Disposable? = null

    /** 【数据采集-ACC】ACC 数据流订阅；非 null 表示 ACC 流正在运行 */
    private var accDisposable: Disposable? = null

    /** 【数据记录】当前会话已保存的心率条数 */
    private var hrSaved = 0

    /** 【数据记录】当前会话已保存的 ECG 样本数 */
    private var ecgSaved = 0

    /** 【数据记录】当前会话已保存的 ACC 样本数 */
    private var accSaved = 0

    // ------------------------------------------------------------
    // 【实时图表】实时心率折线图相关状态
    // ------------------------------------------------------------

    /** 主界面实时心率折线图控件 */
    private lateinit var chartLiveHr: LineChart

    /** 实时曲线数据集（懒创建，断开后清空重建） */
    private var liveSet: LineDataSet? = null

    /** 实时曲线横轴零点（本次连接后第一个心率点的时刻，毫秒） */
    private var liveStartMs = 0L

    /** 【配对】本次扫描发现的设备列表（按 deviceId 去重） */
    private val foundDevices = mutableListOf<PolarDeviceInfo>()

    // ============================================================
    // 【生命周期】界面创建：绑定控件、初始化图表/数据库/SDK、申请权限
    // ============================================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // ---- 绑定界面控件 ----
        editDeviceId = findViewById(R.id.editDeviceId)
        textStatus = findViewById(R.id.textStatus)
        textBattery = findViewById(R.id.textBattery)
        textHr = findViewById(R.id.textHr)
        textRr = findViewById(R.id.textRr)
        textCounts = findViewById(R.id.textCounts)
        btnScan = findViewById(R.id.btnScan)
        btnConnect = findViewById(R.id.btnConnect)
        btnSession = findViewById(R.id.btnSession)
        btnEcg = findViewById(R.id.btnEcg)
        btnAcc = findViewById(R.id.btnAcc)
        btnHistory = findViewById(R.id.btnHistory)
        chartLiveHr = findViewById(R.id.chartLiveHr)
        spinnerAccRate = findViewById(R.id.spinnerAccRate)
        spinnerAccRange = findViewById(R.id.spinnerAccRange)

        db = DatabaseHelper(this)          // 【数据记录】打开/创建 SQLite 数据库
        setupLiveChart()                   // 【实时图表】初始化心率曲线样式
        setupAccSpinners()                 // 【数据采集-ACC】初始化采样率/量程下拉框
        initPolarApi()                     // 【通信】初始化 Polar BLE SDK 并注册回调
        setupButtons()                     // 【UI 交互】绑定各按钮点击事件
        requestPermissionsIfNeeded()       // 【系统】申请蓝牙运行时权限
    }

    // ============================================================
    // 【实时图表】主界面 60 秒滚动心率折线图
    // ============================================================

    /**
     * 【实时图表】初始化实时心率折线图。
     * 样式与历史详情页保持一致：红色折线、无圆点、横轴为 分:秒 格式。
     */
    private fun setupLiveChart() {
        chartLiveHr.description.isEnabled = false      // 关闭右下角描述文字
        chartLiveHr.setTouchEnabled(true)              // 允许触摸
        chartLiveHr.isDragEnabled = true               // 允许拖动
        chartLiveHr.setScaleEnabled(true)              // 允许缩放
        chartLiveHr.setPinchZoom(true)                 // 双指捏合缩放
        chartLiveHr.setDrawGridBackground(false)       // 不绘制网格背景
        chartLiveHr.axisRight.isEnabled = false        // 隐藏右侧 Y 轴
        chartLiveHr.legend.isEnabled = false           // 单条曲线不需要图例
        chartLiveHr.xAxis.position = XAxis.XAxisPosition.BOTTOM // 横轴放底部
        // 横轴标签格式：秒数 → 分:秒（如 90 → "1:30"）
        chartLiveHr.xAxis.valueFormatter = object : ValueFormatter() {
            override fun getAxisLabel(value: Float, axis: AxisBase?): String {
                val total = value.toInt()
                return "%d:%02d".format(total / 60, total % 60)
            }
        }
        chartLiveHr.setNoDataText("等待心率数据…")
    }

    /**
     * 【实时图表】每次心率通知追加一个数据点。
     * 视口为 60 秒滚动窗口并自动跟随最新数据；仅保留最近 10 分钟的点以防内存膨胀。
     *
     * @param hr 当前心率值（bpm）
     */
    private fun addLiveHrPoint(hr: Int) {
        val now = System.currentTimeMillis()
        if (liveStartMs == 0L) liveStartMs = now       // 第一个点作为横轴零点
        val x = (now - liveStartMs) / 1000f            // 横轴：经过秒数

        // 懒创建数据集：红色折线、无圆点、无数值标签（与历史页 HR 图一致）
        val set = liveSet ?: LineDataSet(mutableListOf<Entry>(), "心率 (bpm)").apply {
            color = Color.parseColor("#E53935")
            setDrawCircles(false)
            setDrawValues(false)
            lineWidth = 1.5f
        }.also {
            liveSet = it
            chartLiveHr.data = LineData(it)
        }

        set.addEntry(Entry(x, hr.toFloat()))
        // 内存控制：丢弃 10 分钟之前的旧点
        while (set.entryCount > 0 && set.getEntryForIndex(0).x < x - 600f) {
            set.removeFirst()
        }

        // 刷新图表并让 60 秒窗口跟随到最新位置（右缘留 5 秒边距）
        chartLiveHr.data.notifyDataChanged()
        chartLiveHr.notifyDataSetChanged()
        chartLiveHr.setVisibleXRangeMaximum(60f)
        chartLiveHr.moveViewToX(kotlin.math.max(0f, x - 55f))
        chartLiveHr.invalidate()
    }

    /** 【实时图表】清空实时曲线（设备断开时调用，重连后从零开始绘制） */
    private fun clearLiveChart() {
        liveSet = null
        liveStartMs = 0L
        chartLiveHr.clear()
        chartLiveHr.invalidate()
    }

    // ============================================================
    // 【数据采集-ACC】采样率/量程下拉选项
    // ============================================================

    /** 【数据采集-ACC】初始化两个下拉框，默认选中最高档（200Hz / 8G） */
    private fun setupAccSpinners() {
        spinnerAccRate.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            ACC_RATE_OPTIONS.map { "$it Hz" }
        )
        spinnerAccRate.setSelection(ACC_RATE_OPTIONS.size - 1) // 默认 200Hz

        spinnerAccRange.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            ACC_RANGE_OPTIONS.map { "${it}G" }
        )
        spinnerAccRange.setSelection(ACC_RANGE_OPTIONS.size - 1) // 默认 8G
    }

    /** 【数据采集-ACC】ACC 流运行期间锁定下拉框，停止后恢复可改 */
    private fun setAccSpinnersEnabled(enabled: Boolean) {
        spinnerAccRate.isEnabled = enabled
        spinnerAccRange.isEnabled = enabled
    }

    // ============================================================
    // 【通信】Polar BLE SDK 初始化与设备回调
    // ============================================================

    /**
     * 【通信】创建 PolarBleApi 实例并注册设备回调。
     *
     * 开启的 SDK 功能特性：
     *  - FEATURE_HR                    ：标准蓝牙心率通知（1Hz，含 RR 间期）
     *  - FEATURE_POLAR_ONLINE_STREAMING：在线数据流（ECG / ACC 必需）
     *  - FEATURE_POLAR_SDK_MODE        ：H10 的 SDK 模式（开启高采样率流的必需模式）
     *  - FEATURE_BATTERY_INFO          ：电量信息
     *  - FEATURE_DEVICE_INFO           ：设备信息（型号、固件版本等）
     */
    private fun initPolarApi() {
        api = PolarBleApiDefaultImpl.defaultImplementation(
            applicationContext,
            setOf(
                PolarBleApi.PolarBleSdkFeature.FEATURE_HR,
                PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING,
                PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_SDK_MODE,
                PolarBleApi.PolarBleSdkFeature.FEATURE_BATTERY_INFO,
                PolarBleApi.PolarBleSdkFeature.FEATURE_DEVICE_INFO
            )
        )

        // ---- 注册回调：连接状态、功能就绪、电量、心率通知都在这里异步到达 ----
        api.setApiCallback(object : PolarBleApiCallback() {

            /** 【系统】手机蓝牙开关状态变化 */
            override fun blePowerStateChanged(powered: Boolean) {
                Log.d(TAG, "BLE power: $powered")
                if (!powered) runOnUiThread { toast("请先打开手机蓝牙") }
            }

            /** 【通信】设备连接成功：记录设备 ID 并刷新界面 */
            override fun deviceConnected(polarDeviceInfo: PolarDeviceInfo) {
                Log.d(TAG, "connected: ${polarDeviceInfo.deviceId}")
                connectedDeviceId = polarDeviceInfo.deviceId
                runOnUiThread {
                    textStatus.text = "状态：已连接 ${polarDeviceInfo.deviceId}"
                    updateConnectButton()
                }
            }

            /** 【通信】设备正在连接中 */
            override fun deviceConnecting(polarDeviceInfo: PolarDeviceInfo) {
                runOnUiThread { textStatus.text = "状态：正在连接 ${polarDeviceInfo.deviceId} …" }
            }

            /** 【通信】设备断开：清理状态、停止所有数据流、清空实时图表 */
            override fun deviceDisconnected(polarDeviceInfo: PolarDeviceInfo) {
                Log.d(TAG, "disconnected: ${polarDeviceInfo.deviceId}")
                connectedDeviceId = null
                streamingFeatureReady = false
                stopStreams()
                runOnUiThread {
                    textStatus.text = "状态：已断开"
                    textHr.text = "-- bpm"
                    clearLiveChart()
                    updateConnectButton()
                }
            }

            /**
             * 【通信】某项 SDK 功能在设备上已就绪。
             * 只有收到 FEATURE_POLAR_ONLINE_STREAMING 就绪后，才能启动 ECG/ACC 流。
             */
            override fun bleSdkFeatureReady(
                identifier: String,
                feature: PolarBleApi.PolarBleSdkFeature
            ) {
                Log.d(TAG, "feature ready: $feature")
                if (feature == PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING) {
                    streamingFeatureReady = true
                }
            }

            /** 【通信】设备信息（DIS）回调：型号/固件等，仅记录日志 */
            override fun disInformationReceived(identifier: String, uuid: UUID, value: String) {
                Log.d(TAG, "DIS $uuid: $value")
            }

            /** 【通信】设备信息（DIS）回调的另一重载形式（6.16.x 接口要求实现） */
            override fun disInformationReceived(identifier: String, disInfo: DisInfo) {
                Log.d(TAG, "DIS $disInfo")
            }

            /** 【通信】体温服务通知（H10 不支持，接口要求的空实现） */
            override fun htsNotificationReceived(
                identifier: String,
                data: PolarHealthThermometerData
            ) {
                // 空实现：H10 无体温传感器
            }

            /** 【通信】设备电量回调 */
            override fun batteryLevelReceived(identifier: String, level: Int) {
                runOnUiThread { textBattery.text = "电量：$level%" }
            }

            /**
             * 【数据采集-HR】心率通知回调（1Hz，连接后自动推送，无需启动流）。
             * 同时承担两件事：
             *  1. 【实时图表】更新大号心率、RR 文本与实时折线图（UI 线程）
             *  2. 【数据记录】若记录会话开启，把该样本写入 SQLite hr_samples 表
             */
            override fun hrNotificationReceived(
                identifier: String,
                data: PolarHrData.PolarHrSample
            ) {
                val rrText = if (data.rrAvailable) data.rrsMs.joinToString(",") else "--"
                runOnUiThread {
                    textHr.text = "${data.hr} bpm"
                    textRr.text = "RR 间期：$rrText ms"
                    addLiveHrPoint(data.hr)              // 实时折线图追加一个点
                }
                // 【数据记录】会话开启时持久化心率（RR 列表拼成逗号字符串存储）
                val sid = sessionId
                if (sid != -1L) {
                    db.insertHr(
                        sid,
                        System.currentTimeMillis(),
                        data.hr,
                        if (data.rrAvailable) data.rrsMs.joinToString(",") else null
                    )
                    hrSaved++
                    runOnUiThread { updateCounts() }
                }
            }
        })
    }

    // ============================================================
    // 【UI 交互】按钮事件绑定
    // ============================================================

    /** 绑定全部按钮点击事件：扫描 / 连接 / 会话 / ECG / ACC / 历史查询 */
    private fun setupButtons() {
        btnScan.setOnClickListener { scanDevices() }

        // 【通信】连接/断开二合一按钮
        btnConnect.setOnClickListener {
            val connected = connectedDeviceId
            if (connected != null) {
                api.disconnectFromDevice(connected)      // 已连接 → 断开
            } else {
                val id = editDeviceId.text.toString().trim().uppercase()
                if (id.isEmpty()) {
                    toast("请先扫描选择设备，或手动输入设备 ID")
                    return@setOnClickListener
                }
                try {
                    textStatus.text = "状态：正在连接 $id …"
                    api.connectToDevice(id)              // 未连接 → 按设备 ID 发起连接
                } catch (e: Exception) {
                    Log.e(TAG, "connect failed", e)
                    toast("连接失败：${e.message}")
                }
            }
        }

        btnSession.setOnClickListener { toggleSession() }   // 【数据记录】会话开关
        btnEcg.setOnClickListener { toggleEcg() }           // 【数据采集】ECG 流开关
        btnAcc.setOnClickListener { toggleAcc() }           // 【数据采集】ACC 流开关

        // 【历史查询】进入历史记录页
        btnHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
    }

    // ============================================================
    // 【配对/发现】BLE 扫描与设备选择
    // ============================================================

    /**
     * 【配对/发现】扫描附近 BLE 设备 8 秒，发现的设备去重后收集到列表，
     * 扫描结束弹出设备选择对话框。
     */
    private fun scanDevices() {
        foundDevices.clear()
        toast("扫描中（约 8 秒）…")
        scanDisposable?.dispose()                        // 取消上一次未完成的扫描
        scanDisposable = api.searchForDevice()
            .take(8, TimeUnit.SECONDS)                   // 8 秒后自动结束扫描
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { info ->                                // 每发现一个设备：按 deviceId 去重收集
                    if (foundDevices.none { it.deviceId == info.deviceId }) {
                        foundDevices.add(info)
                    }
                },
                { e ->                                   // 扫描失败
                    Log.e(TAG, "scan error", e)
                    toast("扫描失败：${e.message}")
                },
                { showDeviceDialog() }                   // 扫描完成：弹出选择框
            )
    }

    /** 【配对/发现】弹出设备列表对话框，选中后把设备 ID 填入输入框 */
    private fun showDeviceDialog() {
        if (foundDevices.isEmpty()) {
            toast("未发现设备，请确认 H10 已佩戴并处于可连接状态")
            return
        }
        val names = foundDevices.map { "${it.name}  (${it.deviceId})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择设备")
            .setItems(names) { _, which ->
                editDeviceId.setText(foundDevices[which].deviceId)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ============================================================
    // 【数据记录】记录会话管理
    // ============================================================

    /**
     * 【数据记录】开始/结束记录会话。
     * 开始：在 sessions 表插入一行并清零计数器，此后所有流入数据写入 SQLite；
     * 结束：回写会话结束时间，此后数据仅实时显示不再保存。
     */
    private fun toggleSession() {
        if (sessionId == -1L) {
            // ---- 开始会话 ----
            val device = connectedDeviceId
                ?: editDeviceId.text.toString().trim().ifEmpty { "unknown" }
            sessionId = db.createSession(device)         // sessions 表新增一行
            hrSaved = 0; ecgSaved = 0; accSaved = 0
            btnSession.text = "结束记录会话"
            toast("会话 #$sessionId 已开始，数据将保存到 SQLite")
        } else {
            // ---- 结束会话 ----
            db.endSession(sessionId)                     // 回写 end_time
            toast("会话 #$sessionId 已保存")
            sessionId = -1L
            btnSession.text = "开始记录会话（保存到 SQLite）"
        }
        updateCounts()
    }

    // ============================================================
    // 【数据采集-ECG】心电在线流（固定 130Hz，µV）
    // ============================================================

    /**
     * 【数据采集-ECG】启动/停止 ECG 在线流。
     * 官方 API 流程：requestStreamSettings（查询可用参数）
     *             → startEcgStreaming（启动 Flowable 数据流）。
     * H10 的 ECG 采样率固定 130Hz，故直接使用 maxSettings()。
     * 【数据记录】会话开启时，每个样本（自带 ns 时间戳、µV 电压）批量写入 SQLite。
     */
    private fun toggleEcg() {
        // ---- 已在采集 → 停止流 ----
        if (ecgDisposable != null) {
            ecgDisposable?.dispose()
            ecgDisposable = null
            btnEcg.text = "开始 ECG 流"
            return
        }
        // ---- 前置检查：必须已连接且在线流功能就绪 ----
        val id = connectedDeviceId
        if (id == null) {
            toast("请先连接设备")
            return
        }
        if (!streamingFeatureReady) {
            toast("在线流功能尚未就绪，请稍候再试")
            return
        }
        btnEcg.text = "停止 ECG 流"
        ecgDisposable = api.requestStreamSettings(id, PolarBleApi.PolarDeviceDataType.ECG)
            .flatMapPublisher { settings ->
                Log.d(TAG, "ECG available settings: ${settings.settings}")
                api.startEcgStreaming(id, settings.maxSettings())
            }
            .subscribe(
                { data ->
                    // 【数据记录】仅在会话开启时落库
                    val sid = sessionId
                    if (sid != -1L) {
                        // SDK 6.16.x 数据模型：每个样本自带 timeStamp（ns）；
                        // 电压值在子类 com.polar.sdk.api.model.EcgSample.voltage（µV）
                        val batch = data.samples.mapNotNull { s ->
                            val voltage =
                                (s as? com.polar.sdk.api.model.EcgSample)?.voltage
                                    ?: return@mapNotNull null
                            EcgSample(
                                id = 0,                // 数据库自增 id，此处填 0 占位
                                sessionId = sid,
                                timestamp = s.timeStamp,
                                voltage = voltage
                            )
                        }
                        db.insertEcgBatch(batch)       // 事务批量插入（130Hz 高频必需）
                        ecgSaved += batch.size
                        runOnUiThread { updateCounts() }
                    }
                },
                { e ->
                    // 流出错：复位按钮状态
                    Log.e(TAG, "ECG stream error", e)
                    ecgDisposable = null
                    runOnUiThread {
                        btnEcg.text = "开始 ECG 流"
                        toast("ECG 流错误：${e.message}")
                    }
                }
            )
    }

    // ============================================================
    // 【数据采集-ACC】加速度在线流（25/50/100/200Hz 可选，mG）
    // ============================================================

    /**
     * 【数据采集-ACC】启动/停止加速度在线流。
     * 与 ECG 不同：使用界面下拉框选择的采样率与量程构建设置，
     * 若设备实际不支持所选档位则回退到其可用最大值（双层保护）。
     * 【数据记录】会话开启时，三轴样本（自带 ns 时间戳，x/y/z 单位 mG）批量写入 SQLite。
     */
    private fun toggleAcc() {
        // ---- 已在采集 → 停止流并解锁下拉框 ----
        if (accDisposable != null) {
            accDisposable?.dispose()
            accDisposable = null
            btnAcc.text = "开始 ACC 流"
            setAccSpinnersEnabled(true)
            return
        }
        // ---- 前置检查 ----
        val id = connectedDeviceId
        if (id == null) {
            toast("请先连接设备")
            return
        }
        if (!streamingFeatureReady) {
            toast("在线流功能尚未就绪，请稍候再试")
            return
        }
        // 读取下拉框选择的档位
        val wantRate = ACC_RATE_OPTIONS[spinnerAccRate.selectedItemPosition]
        val wantRange = ACC_RANGE_OPTIONS[spinnerAccRange.selectedItemPosition]
        btnAcc.text = "停止 ACC 流"
        setAccSpinnersEnabled(false)                     // 流运行期间锁定档位选择
        accDisposable = api.requestStreamSettings(id, PolarBleApi.PolarDeviceDataType.ACC)
            .flatMapPublisher { settings ->
                // 校验所选档位是否在设备可用范围内，不支持则回退到可用最大值
                val availRates =
                    settings.settings[PolarSensorSetting.SettingType.SAMPLE_RATE] ?: emptySet()
                val availRanges =
                    settings.settings[PolarSensorSetting.SettingType.RANGE] ?: emptySet()
                val rate = if (wantRate in availRates) wantRate else (availRates.maxOrNull() ?: wantRate)
                val range = if (wantRange in availRanges) wantRange else (availRanges.maxOrNull() ?: wantRange)
                Log.d(TAG, "ACC 请求 ${wantRate}Hz/${wantRange}G，实际使用 ${rate}Hz/${range}G")
                // 用所选档位构建设置；构造失败（组合非法）时兜底用 maxSettings
                val chosen = try {
                    PolarSensorSetting(
                        mapOf(
                            PolarSensorSetting.SettingType.SAMPLE_RATE to rate,
                            PolarSensorSetting.SettingType.RANGE to range,
                            PolarSensorSetting.SettingType.CHANNELS to 3   // 三轴
                        )
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "ACC 自定义设置无效，回退到 maxSettings", e)
                    settings.maxSettings()
                }
                api.startAccStreaming(id, chosen)
            }
            .subscribe(
                { data ->
                    // 【数据记录】仅在会话开启时落库
                    val sid = sessionId
                    if (sid != -1L) {
                        // 每个样本自带 timeStamp（ns），x/y/z 单位 mG
                        val batch = data.samples.map { s ->
                            AccSample(
                                id = 0,
                                sessionId = sid,
                                timestamp = s.timeStamp,
                                x = s.x, y = s.y, z = s.z
                            )
                        }
                        db.insertAccBatch(batch)       // 事务批量插入
                        accSaved += batch.size
                        runOnUiThread { updateCounts() }
                    }
                },
                { e ->
                    // 流出错：复位按钮与下拉框状态
                    Log.e(TAG, "ACC stream error", e)
                    accDisposable = null
                    runOnUiThread {
                        btnAcc.text = "开始 ACC 流"
                        setAccSpinnersEnabled(true)
                        toast("ACC 流错误：${e.message}")
                    }
                }
            )
    }

    // ============================================================
    // 【辅助】状态刷新与通用工具
    // ============================================================

    /** 【数据采集】停止全部数据流并复位相关按钮/下拉框（设备断开或退出时调用） */
    private fun stopStreams() {
        ecgDisposable?.dispose(); ecgDisposable = null
        accDisposable?.dispose(); accDisposable = null
        btnEcg.text = "开始 ECG 流"
        btnAcc.text = "开始 ACC 流"
        setAccSpinnersEnabled(true)
    }

    /** 【通信】根据连接状态刷新连接按钮文字（连接 ⇄ 断开连接） */
    private fun updateConnectButton() {
        btnConnect.text = if (connectedDeviceId != null) "断开连接" else "连接"
    }

    /** 【数据记录】刷新会话样本计数显示 */
    private fun updateCounts() {
        textCounts.text = if (sessionId == -1L) {
            "未在记录会话（实时数据仅显示，不保存）"
        } else {
            "会话 #$sessionId 已保存 → HR: $hrSaved 条 · ECG: $ecgSaved 条 · ACC: $accSaved 条"
        }
    }

    // ============================================================
    // 【系统】蓝牙运行时权限
    // ============================================================

    /**
     * 【系统】按 Android 版本申请所需权限：
     *  - Android 12+（API 31+）：BLUETOOTH_SCAN / BLUETOOTH_CONNECT
     *  - Android 11 及以下    ：ACCESS_FINE_LOCATION（BLE 扫描需要）
     */
    private fun requestPermissionsIfNeeded() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_PERMISSIONS)
        }
    }

    /** 【辅助】弹出短时提示 */
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ============================================================
    // 【生命周期】界面销毁：停止扫描/流、补写会话结束时间、断开设备并释放 SDK
    // ============================================================
    override fun onDestroy() {
        scanDisposable?.dispose()
        stopStreams()
        // 退出时若会话未手动结束，自动补写结束时间，避免产生"未结束"会话
        if (sessionId != -1L) db.endSession(sessionId)
        try {
            connectedDeviceId?.let { api.disconnectFromDevice(it) }
            api.shutDown()
        } catch (e: Exception) {
            Log.w(TAG, "shutdown: ${e.message}")
        }
        super.onDestroy()
    }
}
