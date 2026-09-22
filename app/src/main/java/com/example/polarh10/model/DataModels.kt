package com.example.polarh10.model

/**
 * ============================================================
 * 数据模型层 —— 数据库表结构与内存对象的对应定义
 * ============================================================
 * 与 [com.example.polarh10.db.DatabaseHelper] 中的四张表一一对应，
 * 供【数据记录】写入与【历史查询】读取时传递数据使用。
 */

/** 【历史查询】一次记录会话的汇总信息（sessions 表 + 各样本表的条数统计） */
data class SessionSummary(
    /** 会话数据库 id（主键） */
    val id: Long,
    /** 采集设备 ID */
    val deviceId: String,
    /** 会话开始时间（手机毫秒时间戳） */
    val startTime: Long,
    /** 会话结束时间（手机毫秒时间戳）；null 表示会话未正常结束 */
    val endTime: Long?,
    /** 备注（可空） */
    val note: String?,
    /** 心率样本条数 */
    val hrCount: Long,
    /** ECG 样本条数 */
    val ecgCount: Long,
    /** ACC 样本条数 */
    val accCount: Long
)

/** 【数据记录/历史查询】心率样本（hr_samples 表）：hr 单位 bpm；rr 为逗号分隔的 RR 间期（ms），可空 */
data class HrSample(
    /** 样本数据库 id */
    val id: Long,
    /** 所属会话 id（外键） */
    val sessionId: Long,
    /** 采样时间（手机毫秒时间戳） */
    val timestamp: Long,
    /** 心率值（bpm） */
    val hr: Int,
    /** 本次通知携带的 RR 间期列表（逗号分隔，单位 ms）；无 RR 数据时为 null */
    val rr: String?
)

/** 【数据记录/历史查询】心电样本（ecg_samples 表）：130Hz，voltage 单位 µV */
data class EcgSample(
    /** 样本数据库 id（插入时填 0，由数据库自增） */
    val id: Long,
    /** 所属会话 id（外键） */
    val sessionId: Long,
    /** 采样时间（设备纳秒时间戳） */
    val timestamp: Long,
    /** 心电电压（µV） */
    val voltage: Int
)

/** 【数据记录/历史查询】加速度样本（acc_samples 表）：x/y/z 单位 mG（毫重力加速度） */
data class AccSample(
    /** 样本数据库 id（插入时填 0，由数据库自增） */
    val id: Long,
    /** 所属会话 id（外键） */
    val sessionId: Long,
    /** 采样时间（设备纳秒时间戳） */
    val timestamp: Long,
    /** X 轴加速度（mG） */
    val x: Int,
    /** Y 轴加速度（mG） */
    val y: Int,
    /** Z 轴加速度（mG） */
    val z: Int
)

/** 【历史查询】某会话的心率统计结果（详情页 HR 图表上方展示） */
data class HrStats(
    /** 心率样本条数 */
    val count: Long,
    /** 平均心率（bpm） */
    val avg: Double,
    /** 最高心率（bpm） */
    val max: Int,
    /** 最低心率（bpm） */
    val min: Int
)
