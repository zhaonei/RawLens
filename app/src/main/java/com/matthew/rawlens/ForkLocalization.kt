// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView

/**
 * Chinese text for this fork, applied at runtime.
 *
 * Upstream has no string resources at all -- every label is an inline literal -- so translation
 * cannot be a `values-zh-rCN/strings.xml` drop-in. This rewrites labels in the live view tree.
 * It edits no upstream file, so an upstream rebase cannot conflict with it, and turning it off
 * is deleting one line from the activity.
 *
 * Two passes, because most labels are updated by `setText` without a layout pass (a status line
 * that changes width never lays out again):
 *  1. [exact] whole-string matches, e.g. a settings row title;
 *  2. [wordRules] whole-word rewrites inside composite strings the app builds itself, e.g.
 *     "LK-R\nAUTO HDR ×12" or "READY • ZSL WARMING".
 * Re-applied from an OnPreDraw hook (throttled), so labels set later are caught too.
 *
 * Still English by construction: numbers and unit tokens ("ISO 800", "24mm", "×12"), anything
 * drawn on a Canvas (the zoom track's own labels) and Toast/Log text. Those need the string
 * extraction pass, not this layer.
 */
internal object ForkLocalization {

    private val exact: Map<String, String> = mapOf(
        // Pages / sections
        "Settings" to "设置", "Photo" to "照片", "Video" to "视频", "General" to "通用",
        "Merge" to "合并", "Lenses" to "镜头", "About" to "关于", "Develop" to "显影",
        "Burst" to "连拍", "Output" to "输出", "Viewfinder" to "取景器", "Camera" to "相机",
        "Looks" to "风格", "Exposure" to "曝光", "Color" to "色彩", "Focus" to "对焦",
        "Storage" to "存储", "Permissions" to "权限", "Diagnostics" to "诊断",
        "Presets" to "预设", "Frames" to "画框", "Crop" to "裁切", "Merges" to "合并",
        "Develop queue" to "显影队列", "Welcome" to "欢迎",
        // Settings rows
        "Tone mapper" to "色调映射", "Shadow lift" to "阴影提亮",
        "Highlight recovery" to "高光恢复", "White balance" to "白平衡",
        "Shutter speed" to "快门速度", "ISO" to "ISO", "Zoom" to "变焦",
        "Resolution" to "分辨率", "Quality" to "质量", "Format" to "格式",
        "Frame count" to "帧数", "Denoise" to "降噪", "Sharpening" to "锐化",
        "Black & white" to "黑白", "Network" to "网络模型", "HDR" to "HDR",
        "Compression" to "压缩", "Bit depth" to "位深", "Profile" to "配置",
        "Calibration" to "校准", "Black level" to "黑电平", "White level" to "白电平",
        "Noise model" to "噪声模型", "Lens shading" to "镜头阴影",
        "Timer" to "定时器", "Grid" to "网格", "Level" to "水平仪",
        "Histogram" to "直方图", "Waveform" to "波形图", "Zebras" to "斑马纹",
        "Focus peaking" to "对焦峰值", "False color" to "伪色",
        "Reset" to "重置", "Done" to "完成", "Cancel" to "取消", "Apply" to "应用",
        "Delete" to "删除", "Rename" to "重命名", "Save" to "保存", "Share" to "分享",
        "Close" to "关闭", "Back" to "返回", "OK" to "确定", "Yes" to "是", "No" to "否",
        "On" to "开", "Off" to "关", "Auto" to "自动", "Manual" to "手动",
        "Disabled" to "已关闭", "Enabled" to "已开启", "Default" to "默认",
    )

    /** Whole-word rewrites for strings the app assembles at runtime. */
    private val wordRules: List<Pair<Regex, String>> = listOf(
        Regex("\\bSHARP\\b") to "锐利",
        Regex("\\bOFF\\b") to "关",
        Regex("\\bAUTO\\b") to "自动",
        Regex("\\bWARMING\\b") to "预热中",
        Regex("\\bMERGING\\b") to "合并中",
        Regex("\\bUNAVAILABLE\\b") to "不可用",
        Regex("\\bREADY\\b") to "就绪",
        Regex("\\bSAVING\\b") to "保存中",
        Regex("\\bSAVED\\b") to "已保存",
        Regex("\\bERROR\\b") to "错误",
        Regex("\\bFAILED\\b") to "失败",
        Regex("\\bCANCELLED\\b") to "已取消",
        Regex("\\bCAPTURING\\b") to "拍摄中",
        Regex("\\bPROCESSING\\b") to "处理中",
        Regex("\\bBUSY\\b") to "忙",
        Regex("\\bNO OTHER RAW LENS\\b") to "没有其他 RAW 镜头",
        Regex("\\bLENS\\b") to "镜头",
    )

    private var installed = false
    private val attached = HashMap<View, Long>()

    /** Call once from the first activity's onCreate with its Application. */
    fun installOn(application: Application) {
        if (installed) return
        installed = true
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) = attach(activity.window.decorView)
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /**
     * Re-runs before every frame, throttled: `setText` without a size change never triggers a
     * layout pass, so a layout listener alone missed most of the interface.
     */
    private fun attach(root: View) {
        synchronized(attached) {
            if (attached.containsKey(root)) return
            attached[root] = 0L
            root.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    val now = SystemClock.uptimeMillis()
                    val last = synchronized(attached) { attached[root] ?: 0L }
                    if (now - last >= 120L) {
                        synchronized(attached) { attached[root] = now }
                        apply(root)
                    }
                    return true
                }
            })
            apply(root)
        }
    }

    fun apply(root: View?) {
        walk(root)
    }

    private fun walk(view: View?) {
        when (view) {
            null -> return
            is TextView -> {
                translate(view.text)?.let { if (it != view.text) view.text = it }
                translate(view.contentDescription)?.let { view.contentDescription = it }
            }
            is ViewGroup -> for (index in 0 until view.childCount) walk(view.getChildAt(index))
        }
    }

    private fun translate(value: CharSequence?): CharSequence? {
        if (value == null || value.isEmpty()) return value
        val text = value.toString()
        exact[text]?.let { return it }
        var result = text
        for ((pattern, replacement) in wordRules) {
            if (pattern.containsMatchIn(result)) result = pattern.replace(result, replacement)
        }
        return result
    }
}
