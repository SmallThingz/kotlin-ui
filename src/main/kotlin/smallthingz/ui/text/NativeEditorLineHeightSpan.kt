/*
 * Line-height metric calculation adapted from AndroidX ui-text 1.11.2,
 * LineHeightStyleSpan.android.kt. Copyright 2019 The Android Open Source Project.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package smallthingz.ui.text

import android.graphics.Paint
import android.text.Editable
import android.text.Spanned
import android.text.style.LineHeightSpan
import smallthingz.ui.text.style.LineHeightStyle

class NativeEditableLineHeightSpan(private val heightPx: Float, private val style:LineHeightStyle) : LineHeightSpan {
    private var endIndex = 0
    private var metrics: NativeEditorLineHeightMetrics? = null
    fun install(value: Editable) {
        value.getSpans(0, value.length, NativeEditableLineHeightSpan::class.java).forEach(value::removeSpan)
        reset(value.length, value.endsWith('\n'))
        value.setSpan(this, 0, value.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
    }

    private fun reset(length: Int, trailingNewline: Boolean) {
        endIndex = if (length == 0 || trailingNewline) length + 1 else length
        metrics = null
    }
    override fun chooseHeight(text: CharSequence, start: Int, end: Int,
                              spanstartv: Int, v: Int, fm: Paint.FontMetricsInt) {
        if (fm.descent - fm.ascent <= 0) return
        val first = start == 0
        val last = end == endIndex
        val trimFirst = style.trim == LineHeightStyle.Trim.Both || style.trim == LineHeightStyle.Trim.FirstLineTop
        val trimLast = style.trim == LineHeightStyle.Trim.Both || style.trim == LineHeightStyle.Trim.LastLineBottom
        if (first && last && trimFirst && trimLast) return
        val value = metrics ?: nativeEditorLineHeightMetrics(fm.ascent, fm.descent, heightPx,
            if (style.alignment == LineHeightStyle.Alignment.Center) NativeTypographyLineHeightAlignment.CENTER
            else NativeTypographyLineHeightAlignment.PROPORTIONAL, false).also { metrics = it }
        if (!(first && trimFirst)) fm.ascent = value.ascent
        if (!(last && trimLast)) fm.descent = value.descent
    }
}

class RetentionLineHeightSpan(private val heightPx: Float) : LineHeightSpan {
    private var endIndex = 0
    private var metrics: NativeEditorLineHeightMetrics? = null
    fun install(value: Editable) {
        value.getSpans(0, value.length, RetentionLineHeightSpan::class.java).forEach(value::removeSpan)
        reset(value.length, value.endsWith('\n'))
        value.setSpan(this, 0, value.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
    }

    private fun reset(length: Int, trailingNewline: Boolean) {
        endIndex = if (length == 0 || trailingNewline) length + 1 else length
        metrics = null
    }
    override fun chooseHeight(text: CharSequence, start: Int, end: Int,
                              spanstartv: Int, v: Int, fm: Paint.FontMetricsInt) {
        if (fm.descent - fm.ascent <= 0) return
        val first = start == 0
        val last = end == endIndex
        if (first && last) return
        val value = metrics ?: nativeEditorLineHeightMetrics(fm.ascent, fm.descent, heightPx,
            NativeTypographyLineHeightAlignment.PROPORTIONAL, true).also { metrics = it }
        fm.ascent = if (first) value.firstAscent else value.ascent
        fm.descent = if (last) value.lastDescent else value.descent
    }
}
