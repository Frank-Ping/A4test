package com.example.polarh10

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.polarh10.db.DatabaseHelper
import com.example.polarh10.model.SessionSummary
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.AxisBase
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.ceil

/**
 * ============================================================
 * 单次记录详情页 —— 【历史查询】第二层：图表切换
 * ============================================================
 * 通过 HR / RR / ECG / ACC 四个页签查看该次记录的数据图表：
 *
 *  【图表-HR】 单条折线图：横轴 = 记录开始经过的时间（分:秒），纵轴 = 心率（bpm）；
 *              默认展示整次记录，支持缩放；上方显示平均/最低/最高心率
 *  【图表-RR】 逐次心跳折线图：横轴 = 心跳序号，纵轴 = RR 间期（ms）；
 *              默认展示整次记录，支持缩放；上方显示平均/最低/最高 RR
 *  【图表-ECG】可滚动波形图：横轴 = 经过时间（秒），纵轴 = 电压（µV）；
 *              默认 5 秒窗口，左右滑动查看其他时段，支持缩放
 *  【图表-ACC】三轴折线图：横轴 = 经过时间（秒），纵轴 = 加速度（mG）；
 *              默认 10 秒窗口；X/Y/Z 固定颜色、可分别隐藏
 *
 * 数据查询统一走后台线程（[executor]），图表渲染在 UI 线程完成。
 */
class SessionDetailActivity : AppCompatActivity() {

    companion object {
        /** Intent 传参键：会话 id */
        const val EXTRA_SESSION_ID = "extra_session_id"

        /** 【图表】ECG / ACC 图表点数上限，超过则按步长抽稀以保证滑动流畅 */
        private const val MAX_POINTS = 100_000
    }

    /** 四种图表页签 */
    private enum class Mode { HR, RR, ECG, ACC }

    // ------------------------------------------------------------
    // 成员变量
    // ------------------------------------------------------------

    /** 【历史查询】数据库入口 */
    private lateinit var db: DatabaseHelper

    /** 折线图控件（四个页签共用一张图，切换时重设数据） */
    private lateinit var chart: LineChart

    /** 标题：会话 id + 设备 + 开始时间 */
    private lateinit var textDetailTitle: TextView

    /** 统计行：平均/最低/最高（HR、RR）或样本数与时长（ECG、ACC） */
    private lateinit var textStats: TextView

    /** 【图表-ACC】三轴显示开关容器（仅 ACC 页签可见） */
    private lateinit var accToggles: View

    /** 四个页签按钮（按模式索引，用于高亮当前页签） */
    private lateinit var tabButtons: Map<Mode, Button>

    /** 单线程执行器：数据库查询在后台线程执行 */
    private val executor = Executors.newSingleThreadExecutor()

    /** 会话开始时间的显示格式 */
    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    /** 当前查看的会话 id（由 Intent 传入） */
    private var sessionId: Long = -1L

    /** 【历史查询】当前会话的元信息（开始时间用于 HR 横轴零点） */
    private var session: SessionSummary? = null

    /** 当前页签 */
    private var mode = Mode.HR

    // ------------------------------------------------------------
    // 【图表】横轴坐标格式化器
    // ------------------------------------------------------------

    /** 【图表-HR】横轴：秒数 → 分:秒（如 90 → "1:30"） */
    private val minSecFormatter = object : ValueFormatter() {
        override fun getAxisLabel(value: Float, axis: AxisBase?): String {
            val total = value.toInt()
            return "%d:%02d".format(total / 60, total % 60)
        }
    }

    /** 【图表-ECG/ACC】横轴：秒数（如 "12 s"） */
    private val secFormatter = object : ValueFormatter() {
        override fun getAxisLabel(value: Float, axis: AxisBase?): String =
            "%.0f s".format(value)
    }

    /** 【图表-RR】横轴：心跳序号（整数） */
    private val indexFormatter = object : ValueFormatter() {
        override fun getAxisLabel(value: Float, axis: AxisBase?): String =
            value.toInt().toString()
    }

    // ============================================================
    // 【生命周期】界面创建：绑定控件、加载会话信息、默认展示 HR 图表
    // ============================================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_session_detail)

        sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
        if (sessionId == -1L) {
            finish()                                     // 非法进入：直接关闭
            return
        }

        db = DatabaseHelper(this)
        textDetailTitle = findViewById(R.id.textDetailTitle)
        textStats = findViewById(R.id.textStats)
        accToggles = findViewById(R.id.accToggles)
        chart = findViewById(R.id.chart)

        // 页签按钮：点击切换到对应图表
        tabButtons = mapOf(
            Mode.HR to findViewById(R.id.btnTabHr),
            Mode.RR to findViewById(R.id.btnTabRr),
            Mode.ECG to findViewById(R.id.btnTabEcg),
            Mode.ACC to findViewById(R.id.btnTabAcc)
        )
        tabButtons.forEach { (m, btn) -> btn.setOnClickListener { switchMode(m) } }

        // 【图表-ACC】三个复选框分别控制 X/Y/Z 曲线的显示与隐藏
        findViewById<CheckBox>(R.id.cbX).setOnCheckedChangeListener { _, c -> setAccVisible(0, c) }
        findViewById<CheckBox>(R.id.cbY).setOnCheckedChangeListener { _, c -> setAccVisible(1, c) }
        findViewById<CheckBox>(R.id.cbZ).setOnCheckedChangeListener { _, c -> setAccVisible(2, c) }

        setupChartBase()

        // 【历史查询】后台读取会话元信息，然后默认展示 HR 图表
        executor.execute {
            session = db.getSession(sessionId)
            runOnUiThread {
                val s = session
                textDetailTitle.text = if (s != null) {
                    "会话 #${s.id} · ${s.deviceId} · ${timeFmt.format(Date(s.startTime))}"
                } else {
                    "会话 #$sessionId"
                }
                switchMode(Mode.HR)
            }
        }
    }

    // ============================================================
    // 【图表】通用配置与页签切换
    // ============================================================

    /** 【图表】图表基础样式：可拖动、双指缩放、横轴在底部、隐藏右轴 */
    private fun setupChartBase() {
        chart.description.isEnabled = false            // 关闭描述文字
        chart.setTouchEnabled(true)
        chart.isDragEnabled = true                     // 拖动查看
        chart.setScaleEnabled(true)
        chart.setPinchZoom(true)                       // 双指捏合缩放
        chart.setDrawGridBackground(false)
        chart.axisRight.isEnabled = false
        chart.xAxis.position = XAxis.XAxisPosition.BOTTOM
        chart.setNoDataText("暂无数据")
    }

    /**
     * 【图表】切换页签：高亮当前按钮、控制 ACC 开关与图例显隐，
     * 清空旧图并启动对应数据的【历史查询】。
     */
    private fun switchMode(newMode: Mode) {
        mode = newMode
        tabButtons.forEach { (m, btn) -> btn.alpha = if (m == newMode) 1.0f else 0.55f }
        accToggles.visibility = if (newMode == Mode.ACC) View.VISIBLE else View.GONE
        chart.legend.isEnabled = (newMode == Mode.ACC) // 只有三轴图需要图例
        chart.clear()
        textStats.text = "加载中…"
        when (newMode) {
            Mode.HR -> loadHr()
            Mode.RR -> loadRr()
            Mode.ECG -> loadEcg()
            Mode.ACC -> loadAcc()
        }
    }

    // ============================================================
    // 【图表-HR】心率折线图：横轴 = 经过时间（分:秒），纵轴 = bpm
    // ============================================================

    /**
     * 【历史查询 + 图表-HR】查询全部心率样本并渲染。
     * 横轴零点取会话开始时间；默认展示整次记录（fitScreen），可双指缩放；
     * 统计行显示平均 / 最低 / 最高心率。
     */
    private fun loadHr() {
        executor.execute {
            val samples = db.getHrSamples(sessionId, Int.MAX_VALUE, 0)
            // 横轴零点：会话开始时间（取不到时退化为首个样本时间）
            val startMs = session?.startTime ?: samples.firstOrNull()?.timestamp ?: 0L
            val entries = samples.map {
                Entry((it.timestamp - startMs) / 1000f, it.hr.toFloat())
            }
            runOnUiThread {
                if (entries.isEmpty()) {
                    showEmpty("该会话没有心率数据")
                    return@runOnUiThread
                }
                val hrs = samples.map { it.hr }
                textStats.text = "平均 %.1f · 最低 %d · 最高 %d bpm（共 %d 条，双指缩放查看细节）".format(
                    hrs.average(), hrs.min(), hrs.max(), hrs.size
                )
                chart.xAxis.valueFormatter = minSecFormatter
                renderSingleLine(entries, "心率 (bpm)", Color.parseColor("#E53935"), fullRange = true)
            }
        }
    }

    // ============================================================
    // 【图表-RR】逐次心跳折线图：横轴 = 心跳序号，纵轴 = RR 间期（ms）
    // ============================================================

    /**
     * 【历史查询 + 图表-RR】查询心率样本并解析其中的 RR 间期。
     * 存储时 RR 以逗号拼接在一条心率记录里，这里逐拍展开为独立数据点；
     * 统计行显示平均 / 最低 / 最高 RR。
     */
    private fun loadRr() {
        executor.execute {
            val samples = db.getHrSamples(sessionId, Int.MAX_VALUE, 0)
            val entries = mutableListOf<Entry>()
            var beat = 0f                                // 心跳序号（从 1 开始递增）
            samples.forEach { s ->
                s.rr?.split(",")?.forEach { token ->
                    token.trim().toFloatOrNull()?.let { entries.add(Entry(++beat, it)) }
                }
            }
            runOnUiThread {
                if (entries.isEmpty()) {
                    showEmpty("该会话没有 RR 间期数据")
                    return@runOnUiThread
                }
                val rrs = entries.map { it.y }
                textStats.text = "平均 %.0f · 最低 %.0f · 最高 %.0f ms（共 %d 次心跳，双指缩放查看细节）".format(
                    rrs.average(), rrs.min(), rrs.max(), rrs.size
                )
                chart.xAxis.valueFormatter = indexFormatter
                renderSingleLine(entries, "RR 间期 (ms)", Color.parseColor("#8E24AA"), fullRange = true)
            }
        }
    }

    // ============================================================
    // 【图表-ECG】可滚动波形图：默认 5 秒窗口
    // ============================================================

    /**
     * 【历史查询 + 图表-ECG】查询全部 ECG 样本并渲染波形。
     * 横轴 = 相对首个样本的经过秒数（设备纳秒时间戳换算）；
     * 默认只显示前 5 秒窗口，左右滑动查看其他时段，支持缩放；
     * 超过 [MAX_POINTS] 个点时按步长抽稀并在统计行注明。
     */
    private fun loadEcg() {
        executor.execute {
            val samples = db.getEcgSamples(sessionId, Int.MAX_VALUE, 0)
            val firstTs = samples.firstOrNull()?.timestamp ?: 0L
            val stride = strideFor(samples.size)         // 抽稀步长（1 = 不抽稀）
            val entries = samples.filterIndexed { i, _ -> i % stride == 0 }.map {
                Entry((it.timestamp - firstTs) / 1e9f, it.voltage.toFloat())
            }
            val durationSec = if (samples.size > 1) {
                (samples.last().timestamp - firstTs) / 1e9
            } else 0.0
            runOnUiThread {
                if (entries.isEmpty()) {
                    showEmpty("该会话没有 ECG 数据")
                    return@runOnUiThread
                }
                textStats.text = "共 ${samples.size} 个样本 · 时长 %.1f 秒（默认显示前 5 秒，左右滑动查看更多，双指缩放）".format(
                    durationSec
                ) + if (stride > 1) " · 已按 1/$stride 抽稀显示" else ""
                chart.xAxis.valueFormatter = secFormatter
                renderSingleLine(entries, "ECG (µV)", Color.parseColor("#00897B"), fullRange = false, windowSec = 5f)
            }
        }
    }

    // ============================================================
    // 【图表-ACC】三轴折线图：默认 10 秒窗口，X/Y/Z 固定颜色可分别隐藏
    // ============================================================

    /**
     * 【历史查询 + 图表-ACC】查询全部加速度样本并渲染三轴曲线。
     * X 红 / Y 绿 / Z 蓝固定配色；默认前 10 秒窗口，可滑动缩放；
     * 复选框可分别隐藏单轴曲线。
     */
    private fun loadAcc() {
        executor.execute {
            val samples = db.getAccSamples(sessionId, Int.MAX_VALUE, 0)
            val firstTs = samples.firstOrNull()?.timestamp ?: 0L
            val stride = strideFor(samples.size)
            val filtered = samples.filterIndexed { i, _ -> i % stride == 0 }
            // 三条曲线共用同一横轴（经过秒数）
            val ex = filtered.map { Entry((it.timestamp - firstTs) / 1e9f, it.x.toFloat()) }
            val ey = filtered.map { Entry((it.timestamp - firstTs) / 1e9f, it.y.toFloat()) }
            val ez = filtered.map { Entry((it.timestamp - firstTs) / 1e9f, it.z.toFloat()) }
            val durationSec = if (samples.size > 1) {
                (samples.last().timestamp - firstTs) / 1e9
            } else 0.0
            runOnUiThread {
                if (ex.isEmpty()) {
                    showEmpty("该会话没有加速度数据")
                    return@runOnUiThread
                }
                textStats.text = "共 ${samples.size} 个样本 · 时长 %.1f 秒（默认显示前 10 秒，左右滑动查看更多，双指缩放）".format(
                    durationSec
                ) + if (stride > 1) " · 已按 1/$stride 抽稀显示" else ""
                chart.xAxis.valueFormatter = secFormatter

                val setX = makeSet(ex, "X", Color.parseColor("#E53935"))  // X 轴：红
                val setY = makeSet(ey, "Y", Color.parseColor("#43A047"))  // Y 轴：绿
                val setZ = makeSet(ez, "Z", Color.parseColor("#1E88E5"))  // Z 轴：蓝
                chart.data = LineData(setX, setY, setZ)
                chart.notifyDataSetChanged()
                chart.setVisibleXRangeMaximum(10f)       // 默认 10 秒窗口
                chart.moveViewToX(0f)                    // 从记录起点开始显示
                chart.invalidate()
            }
        }
    }

    // ============================================================
    // 【图表】渲染辅助
    // ============================================================

    /** 【图表】构造一个无圆点、无数值标签的折线数据集 */
    private fun makeSet(entries: List<Entry>, label: String, color: Int): LineDataSet =
        LineDataSet(entries, label).apply {
            this.color = color
            setDrawCircles(false)
            setDrawValues(false)
            lineWidth = 1.2f
        }

    /**
     * 【图表】渲染单条折线。
     * @param fullRange true  = 展示整次记录（HR/RR）；
     *                  false = 按 [windowSec] 秒窗口显示（ECG/ACC）
     */
    private fun renderSingleLine(
        entries: List<Entry>,
        label: String,
        color: Int,
        fullRange: Boolean,
        windowSec: Float = 0f
    ) {
        chart.data = LineData(makeSet(entries, label, color))
        chart.notifyDataSetChanged()
        if (fullRange) {
            chart.fitScreen()                            // 整段数据缩放到全屏可见
        } else {
            chart.setVisibleXRangeMaximum(windowSec)     // 固定秒数窗口
            chart.moveViewToX(0f)
        }
        chart.invalidate()
    }

    /** 【图表-ACC】按数据集下标（0=X, 1=Y, 2=Z）切换单轴曲线的显隐 */
    private fun setAccVisible(index: Int, visible: Boolean) {
        chart.data?.getDataSetByIndex(index)?.isVisible = visible
        chart.invalidate()
    }

    /** 【图表】无数据时清空图表并在统计行提示 */
    private fun showEmpty(msg: String) {
        chart.clear()
        chart.invalidate()
        textStats.text = msg
    }

    /** 【图表】计算抽稀步长：总数 ≤ [MAX_POINTS] 时为 1（不抽稀），否则向上取整 */
    private fun strideFor(count: Int): Int =
        if (count <= MAX_POINTS) 1 else ceil(count.toDouble() / MAX_POINTS).toInt()

    // ============================================================
    // 【生命周期】销毁：关闭后台线程
    // ============================================================
    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}
