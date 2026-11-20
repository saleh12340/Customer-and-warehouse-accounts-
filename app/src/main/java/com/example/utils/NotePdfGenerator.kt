package com.example.utils

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
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
import org.json.JSONObject

object NotePdfGenerator {
  private const val PAGE_W = 595
  private const val PAGE_H = 842
  private const val MARGIN = 28f

  fun generate(context: Context, note: NoteEntity): File {
    val rightRows = JSONArray(note.rightRowsJson).toRows()
    val leftRows = JSONArray(note.leftRowsJson).toRows()
    val total = maxOf(rightRows.size, leftRows.size, 1)
    val rowsPerPage = 21
    val pageCount = (total + rowsPerPage - 1) / rowsPerPage
    val doc = PdfDocument()
    val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(170, 180, 192)
      strokeWidth = 0.8f
    }
    val title = textPaint(17f, Color.rgb(25, 55, 90), true)
    val header = textPaint(10.5f, Color.WHITE, true)
    val body = textPaint(10.5f, Color.BLACK)
    val muted = textPaint(9f, Color.DKGRAY)
    val headerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(35, 76, 119) }
    val halfGap = 12f
    val halfW = (PAGE_W - MARGIN * 2 - halfGap) / 2f
    val qtyW = 48f
    val detailW = halfW - qtyW
    val date = SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale.US).format(Date(note.updatedAt))
    val output = File(context.filesDir, "notes/note_${note.id}.pdf").apply { parentFile?.mkdirs() }

    try {
      for (pageIndex in 0 until pageCount) {
        val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageIndex + 1).create())
        val canvas = page.canvas
        var y = MARGIN
        drawRtl(canvas, "ملاحظة", MARGIN, y, PAGE_W - MARGIN * 2, title)
        y += 30f
        drawRtl(canvas, "التاريخ: $date", MARGIN, y, PAGE_W - MARGIN * 2, muted)
        y += 22f
        val tableTop = y
        val rightX = PAGE_W - MARGIN - halfW
        val leftX = MARGIN
        drawPanel(canvas, rightRows, pageIndex, rowsPerPage, rightX, tableTop, halfW, qtyW, detailW, headerFill, header, body, line)
        drawPanel(canvas, leftRows, pageIndex, rowsPerPage, leftX, tableTop, halfW, qtyW, detailW, headerFill, header, body, line)
        drawRtl(canvas, "صفحة ${pageIndex + 1} / $pageCount", MARGIN, PAGE_H - 25f, PAGE_W - MARGIN * 2, muted)
        doc.finishPage(page)
      }
      FileOutputStream(output).use { doc.writeTo(it) }
      return output
    } finally {
      doc.close()
    }
  }

  private fun drawPanel(
    canvas: Canvas, rows: List<Pair<String, String>>, pageIndex: Int, pageSize: Int,
    x: Float, top: Float, width: Float, qtyW: Float, detailW: Float,
    fill: Paint, header: TextPaint, body: TextPaint, line: Paint
  ) {
    val headerH = 25f
    val rowH = 29f
    val qtyX = x + width - qtyW
    val detailX = x
    canvas.drawRect(x, top, x + width, top + headerH, fill)
    drawRtl(canvas, "التفاصيل", detailX + 5f, top + 5f, detailW - 10f, header)
    drawRtl(canvas, "الكمية", qtyX + 2f, top + 5f, qtyW - 4f, header, Layout.Alignment.ALIGN_CENTER)
    val from = pageIndex * pageSize
    for (r in 0 until pageSize) {
      val y = top + headerH + r * rowH
      val item = rows.getOrNull(from + r)
      if (item != null) {
        drawRtl(canvas, item.second, detailX + 5f, y + 5f, detailW - 10f, body)
        drawRtl(canvas, item.first, qtyX + 2f, y + 5f, qtyW - 4f, body, Layout.Alignment.ALIGN_CENTER)
      }
      canvas.drawLine(x, y + rowH, x + width, y + rowH, line)
      canvas.drawLine(qtyX, y, qtyX, y + rowH, line)
    }
    canvas.drawLine(x, top, x, top + headerH + pageSize * rowH, line)
    canvas.drawLine(x + width, top, x + width, top + headerH + pageSize * rowH, line)
    canvas.drawLine(x, top + headerH, x + width, top + headerH, line)
  }

  private fun JSONArray.toRows(): List<Pair<String, String>> =
    (0 until length()).mapNotNull { i ->
      val o = optJSONObject(i) ?: return@mapNotNull null
      val qty = o.optString("qty", "").trim()
      val detail = o.optString("detail", "").trim()
      if (qty.isBlank() && detail.isBlank()) null else qty to detail
    }

  private fun textPaint(size: Float, color: Int, bold: Boolean = false) =
    TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      textSize = size
      this.color = color
      typeface = if (bold) android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
      else android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
    }

  private fun drawRtl(
    canvas: Canvas, text: String, x: Float, y: Float, width: Float, paint: TextPaint,
    alignment: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL
  ) {
    val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt().coerceAtLeast(1))
      .setAlignment(alignment)
      .setTextDirection(TextDirectionHeuristics.RTL)
      .setIncludePad(false)
      .setMaxLines(2)
      .build()
    canvas.save()
    canvas.translate(x, y)
    layout.draw(canvas)
    canvas.restore()
  }
}
