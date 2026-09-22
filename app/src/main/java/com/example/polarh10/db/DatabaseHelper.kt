package com.example.polarh10.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.polarh10.model.AccSample
import com.example.polarh10.model.EcgSample
import com.example.polarh10.model.HrSample
import com.example.polarh10.model.HrStats
import com.example.polarh10.model.SessionSummary

/**
 * ============================================================
 * SQLite 存储层 —— 【数据记录】与【历史查询】的统一入口
 * ============================================================
 * 表结构：
 *  - sessions    ：记录会话（开始/结束时间、设备 ID、备注）
 *  - hr_samples  ：心率样本（1Hz，bpm，含 RR 间期字符串）
 *  - ecg_samples ：心电样本（130Hz，µV）——数据量大，批量事务写入
 *  - acc_samples ：加速度样本（25/50/100/200Hz，mG）
 *
 * 三张样本表均通过外键关联 sessions，并启用 ON DELETE CASCADE：
 * 删除会话时其全部样本自动级联删除。
 */
class DatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        /** 数据库文件名（位于应用私有目录 databases/ 下） */
        private const val DB_NAME = "polar_h10.db"

        /** 数据库版本号（表结构变更时递增，触发 onUpgrade） */
        private const val DB_VERSION = 1

        /** 会话表名 */
        const val TABLE_SESSIONS = "sessions"

        /** 心率样本表名 */
        const val TABLE_HR = "hr_samples"

        /** 心电样本表名 */
        const val TABLE_ECG = "ecg_samples"

        /** 加速度样本表名 */
        const val TABLE_ACC = "acc_samples"
    }

    /** 【数据记录】每次打开数据库时启用外键约束（级联删除的前提） */
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    /** 【数据记录】首次创建数据库：建 4 张表 + 3 个 session_id 索引（加速按会话查询） */
    override fun onCreate(db: SQLiteDatabase) {
        // 会话表：一次"记录会话"的元信息
        db.execSQL(
            """CREATE TABLE $TABLE_SESSIONS(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id TEXT NOT NULL,
                start_time INTEGER NOT NULL,
                end_time INTEGER,
                note TEXT
            )"""
        )
        // 心率样本表：timestamp 为手机毫秒时间戳；rr 为逗号分隔的 RR 间期（ms），可空
        db.execSQL(
            """CREATE TABLE $TABLE_HR(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL,
                timestamp INTEGER NOT NULL,
                hr INTEGER NOT NULL,
                rr TEXT,
                FOREIGN KEY(session_id) REFERENCES $TABLE_SESSIONS(id) ON DELETE CASCADE
            )"""
        )
        // 心电样本表：timestamp 为设备纳秒时间戳；voltage 单位 µV
        db.execSQL(
            """CREATE TABLE $TABLE_ECG(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL,
                timestamp INTEGER NOT NULL,
                voltage INTEGER NOT NULL,
                FOREIGN KEY(session_id) REFERENCES $TABLE_SESSIONS(id) ON DELETE CASCADE
            )"""
        )
        // 加速度样本表：timestamp 为设备纳秒时间戳；x/y/z 单位 mG
        db.execSQL(
            """CREATE TABLE $TABLE_ACC(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL,
                timestamp INTEGER NOT NULL,
                x INTEGER NOT NULL,
                y INTEGER NOT NULL,
                z INTEGER NOT NULL,
                FOREIGN KEY(session_id) REFERENCES $TABLE_SESSIONS(id) ON DELETE CASCADE
            )"""
        )
        // 按会话查询样本的索引（历史详情页图表加载的性能保障）
        db.execSQL("CREATE INDEX idx_hr_session ON $TABLE_HR(session_id)")
        db.execSQL("CREATE INDEX idx_ecg_session ON $TABLE_ECG(session_id)")
        db.execSQL("CREATE INDEX idx_acc_session ON $TABLE_ACC(session_id)")
    }

    /** 【数据记录】数据库升级：简单策略——删表重建（示例项目不保留旧数据） */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_ACC")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_ECG")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_HR")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_SESSIONS")
        onCreate(db)
    }

    // ============================================================
    // 【数据记录】会话的创建 / 结束 / 删除
    // ============================================================

    /**
     * 【数据记录】创建一条记录会话。
     * @param deviceId 采集设备 ID
     * @param note     备注（可空）
     * @return 新会话的数据库 id（后续样本都挂在该 id 下）
     */
    fun createSession(deviceId: String, note: String? = null): Long {
        val values = ContentValues().apply {
            put("device_id", deviceId)
            put("start_time", System.currentTimeMillis())
            put("note", note)
        }
        return writableDatabase.insert(TABLE_SESSIONS, null, values)
    }

    /** 【数据记录】结束会话：回写结束时间（默认当前时间） */
    fun endSession(sessionId: Long, endTime: Long = System.currentTimeMillis()) {
        val values = ContentValues().apply { put("end_time", endTime) }
        writableDatabase.update(TABLE_SESSIONS, values, "id=?", arrayOf(sessionId.toString()))
    }

    /** 【数据记录】删除会话：外键 ON DELETE CASCADE 级联删除三张样本表中的相关数据 */
    fun deleteSession(sessionId: Long) {
        writableDatabase.delete(TABLE_SESSIONS, "id=?", arrayOf(sessionId.toString()))
    }

    // ============================================================
    // 【数据记录】样本写入
    // ============================================================

    /**
     * 【数据记录】写入一条心率样本（1Hz，单条插入即可）。
     * @param timestamp 手机毫秒时间戳
     * @param hr        心率（bpm）
     * @param rr        逗号分隔的 RR 间期（ms），无 RR 数据时传 null
     */
    fun insertHr(sessionId: Long, timestamp: Long, hr: Int, rr: String?) {
        val values = ContentValues().apply {
            put("session_id", sessionId)
            put("timestamp", timestamp)
            put("hr", hr)
            if (rr != null) put("rr", rr)
        }
        writableDatabase.insert(TABLE_HR, null, values)
    }

    /**
     * 【数据记录】批量写入 ECG 样本。
     * ECG 为 130Hz 高频数据，必须用「事务 + 预编译语句」批量插入，
     * 否则逐条自动提交的磁盘 I/O 会拖垮采集线程。
     */
    fun insertEcgBatch(samples: List<EcgSample>) {
        if (samples.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.compileStatement(
                "INSERT INTO $TABLE_ECG(session_id, timestamp, voltage) VALUES(?,?,?)"
            ).use { stmt ->
                for (s in samples) {
                    stmt.bindLong(1, s.sessionId)
                    stmt.bindLong(2, s.timestamp)
                    stmt.bindLong(3, s.voltage.toLong())
                    stmt.executeInsert()
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 【数据记录】批量写入加速度样本（同样采用事务 + 预编译语句） */
    fun insertAccBatch(samples: List<AccSample>) {
        if (samples.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.compileStatement(
                "INSERT INTO $TABLE_ACC(session_id, timestamp, x, y, z) VALUES(?,?,?,?,?)"
            ).use { stmt ->
                for (s in samples) {
                    stmt.bindLong(1, s.sessionId)
                    stmt.bindLong(2, s.timestamp)
                    stmt.bindLong(3, s.x.toLong())
                    stmt.bindLong(4, s.y.toLong())
                    stmt.bindLong(5, s.z.toLong())
                    stmt.executeInsert()
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ============================================================
    // 【历史查询】会话与样本的读取
    // ============================================================

    /**
     * 【历史查询】按 id 查询单个会话（含三张样本表的样本数）。
     * 详情页加载图表前用它获取会话开始时间等元信息。
     */
    fun getSession(sessionId: Long): SessionSummary? {
        val sql = """
            SELECT s.id, s.device_id, s.start_time, s.end_time, s.note,
                (SELECT COUNT(*) FROM $TABLE_HR  h WHERE h.session_id = s.id),
                (SELECT COUNT(*) FROM $TABLE_ECG e WHERE e.session_id = s.id),
                (SELECT COUNT(*) FROM $TABLE_ACC a WHERE a.session_id = s.id)
            FROM $TABLE_SESSIONS s WHERE s.id = ?
        """
        readableDatabase.rawQuery(sql, arrayOf(sessionId.toString())).use { c ->
            return if (c.moveToFirst()) {
                SessionSummary(
                    id = c.getLong(0),
                    deviceId = c.getString(1),
                    startTime = c.getLong(2),
                    endTime = if (c.isNull(3)) null else c.getLong(3),
                    note = c.getString(4),
                    hrCount = c.getLong(5),
                    ecgCount = c.getLong(6),
                    accCount = c.getLong(7)
                )
            } else null
        }
    }

    /** 【历史查询】全部会话列表（按开始时间倒序），每条附带三类样本的条数统计 */
    fun getSessions(): List<SessionSummary> {
        val result = mutableListOf<SessionSummary>()
        val sql = """
            SELECT s.id, s.device_id, s.start_time, s.end_time, s.note,
                (SELECT COUNT(*) FROM $TABLE_HR  h WHERE h.session_id = s.id),
                (SELECT COUNT(*) FROM $TABLE_ECG e WHERE e.session_id = s.id),
                (SELECT COUNT(*) FROM $TABLE_ACC a WHERE a.session_id = s.id)
            FROM $TABLE_SESSIONS s
            ORDER BY s.start_time DESC
        """
        readableDatabase.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) {
                result.add(
                    SessionSummary(
                        id = c.getLong(0),
                        deviceId = c.getString(1),
                        startTime = c.getLong(2),
                        endTime = if (c.isNull(3)) null else c.getLong(3),
                        note = c.getString(4),
                        hrCount = c.getLong(5),
                        ecgCount = c.getLong(6),
                        accCount = c.getLong(7)
                    )
                )
            }
        }
        return result
    }

    /** 【历史查询】某会话的心率统计：条数 / 平均值 / 最大值 / 最小值（详情页 HR 图表上方展示） */
    fun getHrStats(sessionId: Long): HrStats {
        readableDatabase.rawQuery(
            "SELECT COUNT(*), AVG(hr), MAX(hr), MIN(hr) FROM $TABLE_HR WHERE session_id=?",
            arrayOf(sessionId.toString())
        ).use { c ->
            return if (c.moveToFirst()) {
                HrStats(
                    count = c.getLong(0),
                    avg = if (c.isNull(1)) 0.0 else c.getDouble(1),
                    max = if (c.isNull(2)) 0 else c.getInt(2),
                    min = if (c.isNull(3)) 0 else c.getInt(3)
                )
            } else HrStats(0, 0.0, 0, 0)
        }
    }

    /** 【历史查询】分页查询心率样本（按时间升序；limit/offset 分页，传 Int.MAX_VALUE 即全量） */
    fun getHrSamples(sessionId: Long, limit: Int, offset: Int): List<HrSample> {
        val result = mutableListOf<HrSample>()
        readableDatabase.rawQuery(
            "SELECT id, session_id, timestamp, hr, rr FROM $TABLE_HR " +
                "WHERE session_id=? ORDER BY timestamp ASC LIMIT ? OFFSET ?",
            arrayOf(sessionId.toString(), limit.toString(), offset.toString())
        ).use { c ->
            while (c.moveToNext()) {
                result.add(
                    HrSample(
                        id = c.getLong(0),
                        sessionId = c.getLong(1),
                        timestamp = c.getLong(2),
                        hr = c.getInt(3),
                        rr = c.getString(4)
                    )
                )
            }
        }
        return result
    }

    /** 【历史查询】分页查询 ECG 样本（按设备时间戳升序） */
    fun getEcgSamples(sessionId: Long, limit: Int, offset: Int): List<EcgSample> {
        val result = mutableListOf<EcgSample>()
        readableDatabase.rawQuery(
            "SELECT id, session_id, timestamp, voltage FROM $TABLE_ECG " +
                "WHERE session_id=? ORDER BY timestamp ASC LIMIT ? OFFSET ?",
            arrayOf(sessionId.toString(), limit.toString(), offset.toString())
        ).use { c ->
            while (c.moveToNext()) {
                result.add(EcgSample(c.getLong(0), c.getLong(1), c.getLong(2), c.getInt(3)))
            }
        }
        return result
    }

    /** 【历史查询】分页查询加速度样本（按设备时间戳升序） */
    fun getAccSamples(sessionId: Long, limit: Int, offset: Int): List<AccSample> {
        val result = mutableListOf<AccSample>()
        readableDatabase.rawQuery(
            "SELECT id, session_id, timestamp, x, y, z FROM $TABLE_ACC " +
                "WHERE session_id=? ORDER BY timestamp ASC LIMIT ? OFFSET ?",
            arrayOf(sessionId.toString(), limit.toString(), offset.toString())
        ).use { c ->
            while (c.moveToNext()) {
                result.add(
                    AccSample(
                        c.getLong(0), c.getLong(1), c.getLong(2),
                        c.getInt(3), c.getInt(4), c.getInt(5)
                    )
                )
            }
        }
        return result
    }
}
