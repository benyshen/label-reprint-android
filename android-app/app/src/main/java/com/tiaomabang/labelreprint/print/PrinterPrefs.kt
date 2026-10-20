package com.tiaomabang.labelreprint.print

import android.content.Context

/**
 * 记住上次用的打印机。
 *
 * 为什么必须记：经典蓝牙靠系统配对表还能"翻到"，但 BLE 打印机**不在配对表里**，
 * 一重启 App 就得重扫一遍；车间里每天开机要打几十张标签，这个等待完全是浪费。
 * 所以把 "BT:<mac>" / "BLE:<mac>" 和显示名一起落盘，开机直接连。
 *
 * 只存这一个文件，不用 DataStore（就三个字段，别为它引依赖）。
 */
class PrinterPrefs(ctx: Context) {

    private val sp = ctx.applicationContext.getSharedPreferences("printer", Context.MODE_PRIVATE)

    /** "BT:AC:3F:.." / "BLE:AC:3F:.."；空串 = 没选过 */
    var spec: String
        get() = sp.getString(KEY_SPEC, "").orEmpty()
        private set(v) = sp.edit().putString(KEY_SPEC, v).apply()

    /** 界面显示用的名字（配对名或 BLE 广播名） */
    var name: String
        get() = sp.getString(KEY_NAME, "").orEmpty()
        private set(v) = sp.edit().putString(KEY_NAME, v).apply()

    /** 启动时自动连上次那台 */
    var autoConnect: Boolean
        get() = sp.getBoolean(KEY_AUTO, true)
        set(v) = sp.edit().putBoolean(KEY_AUTO, v).apply()

    fun remember(spec: String, name: String) {
        this.spec = spec.trim()
        this.name = name.trim()
    }

    fun forget() {
        sp.edit().remove(KEY_SPEC).remove(KEY_NAME).apply()
    }

    private companion object {
        const val KEY_SPEC = "spec"
        const val KEY_NAME = "name"
        const val KEY_AUTO = "auto_connect"
    }
}
