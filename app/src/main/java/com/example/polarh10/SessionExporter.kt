package com.example.polarh10

import android.content.Context
import com.example.polarh10.db.DatabaseHelper
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Streams all stored samples, independent of chart downsampling. */
object SessionExporter {
    fun export(context: Context, sessionId: Long, output: OutputStream) {
        DatabaseHelper(context).use { helper ->
            val db = helper.readableDatabase
            db.beginTransaction()
            try {
                db.rawQuery("SELECT end_time FROM sessions WHERE id=?", arrayOf(sessionId.toString())).use {
                    check(it.moveToFirst()) { "记录不存在" }
                    check(!it.isNull(0)) { "请先结束记录，再导出该会话" }
                }
                ZipOutputStream(output.buffered()).use { zip ->
                    val writer = OutputStreamWriter(zip, Charsets.UTF_8)
                    fun row(values: List<String?>) {
                        writer.write(values.joinToString(",") { "\"" + (it ?: "").replace("\"", "\"\"") + "\"" })
                        writer.write("\r\n")
                    }
                    fun csv(name: String, headers: List<String>, sql: String) {
                        zip.putNextEntry(ZipEntry(name))
                        writer.write("\uFEFF")
                        row(headers)
                        db.rawQuery(sql, arrayOf(sessionId.toString())).use { c ->
                            while (c.moveToNext()) row((0 until c.columnCount).map { c.getString(it) })
                        }
                        writer.flush()
                        zip.closeEntry()
                    }
                    csv("session.csv", listOf("session_id", "device_id", "start_unix_ms", "end_unix_ms", "note"),
                        "SELECT id,device_id,start_time,end_time,note FROM sessions WHERE id=?")
                    csv("hr.csv", listOf("sample_id", "received_unix_ms", "heart_rate_bpm", "rr_intervals_ms"),
                        "SELECT id,timestamp,hr,rr FROM hr_samples WHERE session_id=? ORDER BY timestamp,id")
                    zip.putNextEntry(ZipEntry("rr.csv"))
                    writer.write("\uFEFF")
                    row(listOf("beat_index", "hr_sample_id", "received_unix_ms", "rr_ms"))
                    var beat = 0L
                    db.rawQuery("SELECT id,timestamp,rr FROM hr_samples WHERE session_id=? ORDER BY timestamp,id",
                        arrayOf(sessionId.toString())).use { c ->
                        while (c.moveToNext()) {
                            c.getString(2)?.split(",")?.forEach { token ->
                                if (token.isNotBlank()) row(listOf((++beat).toString(), c.getString(0), c.getString(1), token.trim()))
                            }
                        }
                    }
                    writer.flush()
                    zip.closeEntry()
                    csv("ecg.csv", listOf("sample_id", "device_timestamp_ns", "voltage_uV"),
                        "SELECT id,timestamp,voltage FROM ecg_samples WHERE session_id=? ORDER BY timestamp,id")
                    csv("acc.csv", listOf("sample_id", "device_timestamp_ns", "x_mG", "y_mG", "z_mG"),
                        "SELECT id,timestamp,x,y,z FROM acc_samples WHERE session_id=? ORDER BY timestamp,id")
                    zip.putNextEntry(ZipEntry("README.txt"))
                    writer.write("CSV: UTF-8 with BOM. Empty fields mean missing values.\r\n")
                    writer.write("HR/RR received_unix_ms: phone notification reception time, not individual beat time.\r\n")
                    writer.write("ECG/ACC device_timestamp_ns: original device clock, not Unix time.\r\n")
                    writer.write("RR beat_index counts available intervals; missing beats are not reconstructed.\r\n")
                    writer.write("All stored samples are exported without filtering or chart downsampling.\r\n")
                    writer.flush()
                    zip.closeEntry()
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }
}