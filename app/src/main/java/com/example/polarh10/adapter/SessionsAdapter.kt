package com.example.polarh10.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.polarh10.R
import com.example.polarh10.model.SessionSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ============================================================
 * 会话列表适配器 —— 【历史查询】记录列表的每一行
 * ============================================================
 * 每行展示：会话 id、开始时间、设备名称、持续时长、三类样本条数，
 * 以及删除按钮。点击行进入单次记录详情。
 *
 * @param onClick  点击某行（进入详情页）的回调
 * @param onDelete 点击「删除」按钮的回调
 */
class SessionsAdapter(
    private val onClick: (SessionSummary) -> Unit,
    private val onDelete: (SessionSummary) -> Unit
) : RecyclerView.Adapter<SessionsAdapter.VH>() {

    /** 当前展示的会话数据（由 HistoryActivity 查询后注入） */
    private val items = mutableListOf<SessionSummary>()

    /** 开始时间的显示格式 */
    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    /** 【历史查询】整体替换列表数据并刷新 */
    fun update(newItems: List<SessionSummary>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    /** 单行视图的控件持有者 */
    class VH(view: View) : RecyclerView.ViewHolder(view) {
        /** 标题行：会话 id + 开始时间 */
        val title: TextView = view.findViewById(R.id.textSessionTitle)

        /** 副标题行：设备、时长、样本条数、备注 */
        val sub: TextView = view.findViewById(R.id.textSessionSub)

        /** 删除按钮 */
        val btnDelete: Button = view.findViewById(R.id.btnDeleteSession)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_session, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val s = items[position]
        // 标题：会话 #id · 开始时间
        holder.title.text = "会话 #${s.id} · ${timeFmt.format(Date(s.startTime))}"
        // 持续时长：end_time 为空说明会话未正常结束
        val duration = if (s.endTime == null) {
            "未结束"
        } else {
            "${(s.endTime - s.startTime) / 1000} 秒"
        }
        // 副标题：设备、时长、三类样本条数，有备注则追加
        holder.sub.text = buildString {
            append("设备: ${s.deviceId} · 时长: $duration\n")
            append("HR ${s.hrCount} 条 · ECG ${s.ecgCount} 条 · ACC ${s.accCount} 条")
            s.note?.takeIf { it.isNotBlank() }?.let { append("\n备注: $it") }
        }
        holder.itemView.setOnClickListener { onClick(s) }      // 进入详情
        holder.btnDelete.setOnClickListener { onDelete(s) }    // 删除会话
    }
}
