// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * Chinese text for this fork, applied at runtime.
 *
 * Upstream 2.4.58 has no string resources at all -- every label is a Kotlin/XML literal -- so
 * translation cannot be a `values-zh-rCN/strings.xml` drop-in. This walks the view tree and
 * rewrites exact matches from [zh], which covers the static labels (settings rows, section
 * titles, buttons, status words) without touching a single upstream file, so an upstream
 * rebase cannot conflict with it.
 *
 * LIMITS, by construction: only exact whole-string matches are translated. Composite text that
 * the app builds at runtime ("LK-R\nAUTO HDR ×12", "ISO 800", "24mm") stays English, and so do
 * anything drawn on a Canvas (the zoom track's own labels) and Toast/Log strings. Making those
 * Chinese is the string-extraction pass, not this layer.
 *
 * Turning this off is one line: drop the [install] call from the activity.
 */
internal object ForkLocalization {

    private val zh: Map<String, String> = mapOf(
        // Tabs / pages
        "Settings" to "设置", "Photo" to "照片", "Video" to "视频", "General" to "通用",
        "Merge" to "合并", "Lenses" to "镜头", "About" to "关于", "Develop" to "显影",
        "Burst" to "连拍", "Output" to "输出", "Viewfinder" to "取景器",
        "Looks" to "风格", "Exposure" to "曝光", "Color" to "色彩",
        // Merge modes
        "SHARP" to "锐利", "MFSR" to "MFSR", "LK-R" to "LK-R", "OFF" to "关",
        "AUTO" to "自动", "ON" to "开", "WARMING" to "预热中", "MERGING" to "合并中",
        "UNAVAILABLE" to "不可用", "SAVED" to "已保存", "SAVING" to "保存中",
        "READY" to "就绪", "ERROR" to "错误", "CANCEL" to "取消", "DONE" to "完成",
        // Settings rows
        "Tone mapper" to "色调映射", "Shadow lift" to "阴影提亮",
        "Highlight recovery" to "高光恢复", "White balance" to "白平衡",
        "Shutter speed" to "快门速度", "Focus" to "对焦", "Zoom" to "变焦",
        "Resolution" to "分辨率", "Quality" to "质量", "Format" to "格式",
        "Frame count" to "帧数", "Denoise" to "降噪", "Sharpening" to "锐化",
        "Black & white" to "黑白", "Network" to "网络模型", "HDR" to "HDR",
        "RAW" to "RAW", "JPEG" to "JPEG", "DNG" to "DNG", "Ultra HDR" to "Ultra HDR",
        "Compression" to "压缩", "Bit depth" to "位深", "Profile" to "配置",
        "Calibration" to "校准", "Black level" to "黑电平", "White level" to "白电平",
        "Noise model" to "噪声模型", "Lens shading" to "镜头阴影",
        "Timer" to "定时器", "Grid" to "网格", "Level" to "水平仪",
        "Histogram" to "直方图", "Waveform" to "波形图", "Zebras" to "斑马纹",
        "Focus peaking" to "对焦峰值", "False color" to "伪色",
        "Reset" to "重置", "Done" to "完成", "Cancel" to "取消", "Apply" to "应用",
        "Delete" to "删除", "Rename" to "重命名", "Save" to "保存", "Share" to "分享",
    )

    /** Rewrites [root] and its descendants in place; safe to call repeatedly. */
    fun apply(root: View?) {
        walk(root)
    }

    private fun walk(view: View?) {
        when (view) {
            null -> return
            is TextView -> {
                val current = view.text?.toString()
                if (current != null) {
                    val translated = zh[current]
                    if (translated != null) view.text = translated
                }
                val description = view.contentDescription?.toString()
                if (description != null) {
                    val translated = zh[description]
                    if (translated != null) view.contentDescription = translated
                }
            }
            is ViewGroup -> for (index in 0 until view.childCount) walk(view.getChildAt(index))
        }
    }

    /**
     * Applies once now and again after every layout pass: pages, panels and sheets are inflated
     * or made visible later, and each of those changes fires a layout.
     */
    fun install(root: View) {
        apply(root)
        root.viewTreeObserver.addOnGlobalLayoutListener { apply(root) }
    }
}
