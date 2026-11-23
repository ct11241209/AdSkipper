package com.adskip.app

import android.util.Log
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 内存环形日志。只保留最近 400 条，供主界面的「查看日志」看，
 * 方便排查某个 App 为什么没跳过，然后补规则。
 *
 * 注意：DateTimeFormatter 是线程安全的（SimpleDateFormat 不是），
 * 这里会被 UI 线程、无障碍扫描线程、DNS 线程、通知监听同时调用。
 */
object LogStore {

    private const val TAG = "AdSkipper"
    private const val MAX = 400

    private val buffer = ArrayDeque<String>()
    private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun log(tag: String, msg: String) {
        val line = try {
            LocalTime.now().format(timeFormat) + " [" + tag + "] " + msg
        } catch (t: Throwable) {
            "??:??:?? [" + tag + "] " + msg
        }
        synchronized(buffer) {
            buffer.addLast(line)
            while (buffer.size > MAX) buffer.removeFirst()
        }
        Log.d(TAG, line)
    }

    fun snapshot(): List<String> = synchronized(buffer) { ArrayList(buffer) }

    fun clear() = synchronized(buffer) { buffer.clear() }
}
