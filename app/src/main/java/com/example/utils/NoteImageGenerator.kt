package com.example.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import com.example.data.NoteEntity
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray

/**
 * High-resolution PNG export for the two-panel notes sheet.
 * The output is rendered at 2x A4 width; PDF remains vector-based and
 * thermal printing continues to use the printer's native dot width.
 */
object NoteImageGenerator {
  private const val PAGE_W = 595
  private const val MARGIN = 28f
  private const val SCALE = 2

  fun generate(context: Context, note: NoteEntity): File {
    val rightRows = JSONArray(note.rightRowsJson).toRows()
    val leftRows = JSONArray(note.leftRowsJson).toRows()
    val totalRows = maxOf(rightRows.size, leftRows.size)
    val titlePaint = textPaint(context, 20f, Color.rgb(25, 55, 90), true)
    val headerPaint = textPaint(context, 12f, Color.WHITE, true)
    val bodyPaint = textPaint(context, 13.5f, Color.BLACK)
    val mutedPaint = textPaint(context, 10.5f, Color.DKGRAY)
    val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(170, 180, 192)
      strokeWidth = 0.8f
    }
    val headerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(35, 76, 119) }
    val halfGap = 14f
    val halfW = (PAGE_W - MARGIN * 2 - halfGap) / 2f
    val qtyW = 54f
    val detailW = halfW - qtyW
    val headerH = 30f

    data class RowLayout(val qty: String, val detail: StaticLayout, val height: Float)
    fun measure(rows: List<Pair<String, String>>): List<RowLayout> = rows.map { (qty, detail) ->
      val layout = StaticLayout.Builder.obtain(detail, 0, detail.length, bodyPaint, (detailW - 10f).toInt())
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setTextDirection(TextDirectionHeuristics.RTL)
        .setIncludePad(false)
        .setMaxLines(3)
        .build()
      RowLayout(qty, layout, maxOf(34f, layout.height + 8f))
    }

    val right = measure(rightRows)
    val left = measure(leftRows)
    val rowsHeight = (0 until totalRows).sumOf { index ->
      maxOf(right.getOrNull(index)?.height ?: 0f, left.getOrNull(index)?.height ?: 0f, 34f).toDouble()
    }.toFloat()
    val date = SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale.US).format(Date(note.updatedAt))
    val contentHeight = MARGIN + 27f + 8f + 18f + 12f + headerH + rowsHeight + MARGIN
    val bitmap = Bitmap.createBitmap(PAGE_W * SCALE, (contentHeight * SCALE).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.scale(SCALE.toFloat(), SCALE.toFloat())
    canvas.drawColor(Color.WHITE)

    var y = MARGIN
    drawRtl(canvas, "ملاحظة", MARGIN, y, PAGE_W - MARGIN * 2, titlePaint)
    y += 35f
    drawRtl(canvas, "التاريخ: $date", MARGIN, y, PAGE_W - MARGIN * 2, mutedPaint)
    y += 24f

    val rightX = PAGE_W - MARGIN - halfW
    val leftX = MARGIN
    fun drawPanel(x: Float, rows: List<RowLayout>) {
      val qtyX = x + halfW - qtyW
      canvas.drawRect(x, y, x + halfW, y + headerH, headerFill)
      drawRtl(canvas, "التفاصيل", x + 5f, y + 7f, detailW - 10f, headerPaint)
      drawRtl(canvas, "الكمية", qtyX + 2f, y + 7f, qtyW - 4f, headerPaint, Layout.Alignment.ALIGN_CENTER)
      canvas.drawLine(x, y, x, y + headerH + rowsHeight, linePaint)
      canvas.drawLine(x + halfW, y, x + halfW, y + headerH + rowsHeight, linePaint)
      canvas.drawLine(qtyX, y, qtyX, y + headerH + rowsHeight, linePaint)
      canvas.drawLine(x, y + headerH, x + halfW, y + headerH, linePaint)
      for (index in 0 until totalRows) {
        val rowTop = y + headerH + (0 until index).sumOf { i ->
          maxOf(right.getOrNull(i)?.height ?: 0f, left.getOrNull(i)?.height ?: 0f, 34f).toDouble()
        }.toFloat()
        val rowHeight = maxOf(right.getOrNull(index)?.height ?: 0f, left.getOrNull(index)?.height ?: 0f, 34f)
        rows.getOrNull(index)?.let { row ->
          drawRtl(canvas, row.detail.text.toString(), x + 5f, rowTop + 4f, detailW - 10f, bodyPaint)
          drawRtl(canvas, row.qty, qtyX + 2f, rowTop + 4f, qtyW - 4f, bodyPaint, Layout.Alignment.ALIGN_CENTER)
        }
        canvas.drawLine(x, rowTop + rowHeight, x + halfW, rowTop + rowHeight, linePaint)
      }
    }
    drawPanel(rightX, right)
    drawPanel(leftX, left)
    val output = File(context.filesDir, "notes/note_${note.id}_HD.png").apply { parentFile?.mkdirs() }
    FileOutputStream(output).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bitmap.recycle()
    return output
  }

  private fun JSONArray.toRows(): List<Pair<String, String>> =
    (0 until length()).mapNotNull { index ->
      val obj = optJSONObject(index) ?: return@mapNotNull null
      val qty = obj.optString("qty", "").trim()
      val detail = obj.optString("detail", "").trim()
      if (qty.isBlank() && detail.isBlank()) null else qty to detail
    }

  private fun textPaint(context: Context, size: Float, color: Int, bold: Boolean = false) =
    TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      textSize = size
      this.color = color
      typeface = if (bold) ArabicFontHelper.getBold(context) else ArabicFontHelper.getRegular(context)
    }

  private fun drawRtl(
    canvas: Canvas,
    text: String,
    x: Float,
    y: Float,
    width: Float,
    paint: TextPaint,
    alignment: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL
  ) {
    val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt().coerceAtLeast(1))
      .setAlignment(alignment)
      .setTextDirection(TextDirectionHeuristics.RTL)
      .setIncludePad(false)
      .setMaxLines(3)
      .build()
    canvas.save()
    canvas.translate(x, y)
    layout.draw(canvas)
    canvas.restore()
  }
}
