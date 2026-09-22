package com.example.polarh10

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.polarh10.adapter.SessionsAdapter
import com.example.polarh10.db.DatabaseHelper
import com.example.polarh10.model.SessionSummary
import java.util.concurrent.Executors

/**
 * ============================================================
 * 历史记录页 —— 【历史查询】第一层：记录列表
 * ============================================================
 * 展示全部记录会话（开始时间、持续时长、设备名称、各类样本条数），
 * 点击进入【单次记录详情】查看图表；支持删除会话（级联删除全部样本）。
 *
 * 页面结构遵循「记录列表 → 单次记录详情 → 图表切换」的展示方式。
 */
class HistoryActivity : AppCompatActivity() {

    /** 【历史查询】数据库入口 */
    private lateinit var db: DatabaseHelper

    /** 会话列表适配器 */
    private lateinit var adapter: SessionsAdapter

    /** 「暂无历史记录」空态提示 */
    private lateinit var textEmpty: TextView

    /** 单线程执行器：数据库查询放到后台线程，避免阻塞 UI */
    private val executor = Executors.newSingleThreadExecutor()

    // ============================================================
    // 【生命周期】界面创建：初始化列表与点击/删除回调
    // ============================================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        db = DatabaseHelper(this)
        textEmpty = findViewById(R.id.textEmpty)

        adapter = SessionsAdapter(
            // 点击某条会话 → 进入详情页（图表切换）
            onClick = { session ->
                startActivity(
                    Intent(this, SessionDetailActivity::class.java)
                        .putExtra(SessionDetailActivity.EXTRA_SESSION_ID, session.id)
                )
            },
            // 点击删除 → 弹确认框（删除不可恢复）
            onDelete = { session -> confirmDelete(session) }
        )

        findViewById<RecyclerView>(R.id.recyclerSessions).apply {
            layoutManager = LinearLayoutManager(this@HistoryActivity)
            adapter = this@HistoryActivity.adapter
        }
    }

    /** 每次回到本页都刷新列表（从详情页返回、删除后都需要） */
    override fun onResume() {
        super.onResume()
        loadSessions()
    }

    // ============================================================
    // 【历史查询】会话列表加载
    // ============================================================

    /** 【历史查询】后台线程查询全部会话，回到 UI 线程刷新列表与空态提示 */
    private fun loadSessions() {
        executor.execute {
            val sessions = db.getSessions()
            runOnUiThread {
                adapter.update(sessions)
                textEmpty.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    // ============================================================
    // 【数据记录】会话删除（带确认）
    // ============================================================

    /** 【数据记录】删除前弹确认框；确认后删除会话（样本级联删除）并刷新列表 */
    private fun confirmDelete(session: SessionSummary) {
        AlertDialog.Builder(this)
            .setTitle("删除会话 #${session.id}？")
            .setMessage("将同时删除该会话的全部 HR / ECG / ACC 样本，此操作不可恢复。")
            .setPositiveButton("删除") { _, _ ->
                executor.execute {
                    db.deleteSession(session.id)
                    loadSessions()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ============================================================
    // 【生命周期】销毁：关闭后台线程
    // ============================================================
    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}
