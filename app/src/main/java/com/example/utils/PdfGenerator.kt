package com.example.utils

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.example.data.InvoiceEntity
import com.example.data.InvoiceItemEntity
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PdfGenerator {
  private const val PAGE_WIDTH = 595
  private const val PAGE_HEIGHT = 842
  private const val MARGIN = 36f
  private const val CONTENT_WIDTH = PAGE_WIDTH - MARGIN * 2

  fun generateInvoicePdf(
    context: Context,
    invoice: InvoiceEntity,
    items: List<InvoiceItemEntity>
  ): File? {
    val document = PdfDocument()
    return try {
      val safeNumber = invoice.invoiceNumber.replace(Regex("[^A-Za-z0-9_-]"), "_").take(48)
      val output = File(context.cacheDir, "invoice_${safeNumber.ifBlank { invoice.id.toString() }}.pdf")
      val date = SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale.getDefault()).format(Date(invoice.date))

      val titlePaint = textPaint(20f, Color.rgb(18, 55, 92), true)
      val bodyPaint = textPaint(11f, Color.BLACK)
      val smallPaint = textPaint(9f, Color.DKGRAY)
      val headerPaint = textPaint(10f, Color.WHITE, true)
      val totalPaint = textPaint(12f, Color.BLACK, true)
      val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY
        strokeWidth = 0.8f
      }
      val headerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(25, 75, 125)
      }

      val pageChunks = items.ifEmpty { emptyList() }.chunked(14).ifEmpty { listOf(emptyList()) }
      pageChunks.forEachIndexed { pageIndex, pageItems ->
        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageIndex + 1).create()
        val page = document.startPage(pageInfo)
        val canvas = page.canvas
        var y = MARGIN + 4f

        if (pageIndex == 0) {
          y += drawRtl(canvas, "بقالة العزي للمواد الغذائية", MARGIN, y, CONTENT_WIDTH, titlePaint) + 5f
          y += drawRtl(canvas, "فاتورة رقم: ${invoice.invoiceNumber}", MARGIN, y, CONTENT_WIDTH, totalPaint) + 4f
          y += drawRtl(canvas, "التاريخ: $date", MARGIN, y, CONTENT_WIDTH, bodyPaint) + 4f
          y += drawRtl(canvas, "الطرف: ${invoice.partyName}    |    النوع: ${invoice.type}", MARGIN, y, CONTENT_WIDTH, bodyPaint) + 10f
        } else {
          y += drawRtl(canvas, "بقالة العزي للمواد الغذائية - تابع الفاتورة ${invoice.invoiceNumber}", MARGIN, y, CONTENT_WIDTH, totalPaint) + 14f
        }

        val tableTop = y
        canvas.drawRoundRect(MARGIN, tableTop, PAGE_WIDTH - MARGIN, tableTop + 30f, 3f, 3f, headerFill)

        // RTL order: item name at the right, then quantity, unit price, total at the left.
        val nameRight = PAGE_WIDTH - MARGIN
        val qtyRight = nameRight - 230f
        val priceRight = qtyRight - 65f
        val totalRight = priceRight - 100f
        drawRtl(canvas, "الصنف", nameRight - 230f, tableTop + 8f, 222f, headerPaint)
        drawRtl(canvas, "الكمية", qtyRight - 65f, tableTop + 8f, 60f, headerPaint)
        drawRtl(canvas, "سعر الوحدة", priceRight - 100f, tableTop + 8f, 95f, headerPaint)
        drawRtl(canvas, "الإجمالي", MARGIN + 4f, tableTop + 8f, 95f, headerPaint)

        y = tableTop + 38f
        pageItems.forEach { item ->
          val rowTop = y
          drawRtl(canvas, item.itemName, nameRight - 230f, rowTop, 222f, bodyPaint)
          drawRtl(canvas, item.quantity.toString(), qtyRight - 65f, rowTop, 60f, bodyPaint)
          drawRtl(canvas, formatMoney(item.unitPrice), priceRight - 100f, rowTop, 95f, bodyPaint)
          drawRtl(canvas, formatMoney(item.totalPrice), MARGIN + 4f, rowTop, 95f, totalPaint)
          y += 30f
          canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, linePaint)
          y += 5f
        }

        if (pageIndex == pageChunks.lastIndex) {
          y += 14f
          canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, linePaint)
          y += 16f
          y += drawRtl(canvas, "إجمالي الفاتورة: ${formatMoney(invoice.totalAmount)} ر.ي", MARGIN, y, CONTENT_WIDTH, totalPaint) + 7f
          y += drawRtl(canvas, "المدفوع: ${formatMoney(invoice.paidAmount)} ر.ي", MARGIN, y, CONTENT_WIDTH, bodyPaint) + 7f
          drawRtl(canvas, "المتبقي: ${formatMoney(invoice.remainingAmount)} ر.ي", MARGIN, y, CONTENT_WIDTH, totalPaint)
        }
        drawRtl(canvas, "صفحة ${pageIndex + 1} من ${pageChunks.size}", MARGIN, PAGE_HEIGHT - 28f, CONTENT_WIDTH, smallPaint)
        document.finishPage(page)
      }

      FileOutputStream(output).use { stream -> document.writeTo(stream) }
      output
    } catch (error: Exception) {
      error.printStackTrace()
      null
    } finally {
      document.close()
    }
  }

  private fun textPaint(size: Float, color: Int, bold: Boolean = false) =
    TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      textSize = size
      this.color = color
      typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
    }

  private fun drawRtl(
    canvas: Canvas,
    text: String,
    x: Float,
    y: Float,
    width: Float,
    paint: TextPaint
  ): Int {
    val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt().coerceAtLeast(1))
      .setAlignment(Layout.Alignment.ALIGN_OPPOSITE)
      .setTextDirection(TextDirectionHeuristics.RTL)
      .setIncludePad(false)
      .setMaxLines(2)
      .build()
    canvas.save()
    canvas.translate(x, y)
    layout.draw(canvas)
    canvas.restore()
    return layout.height
  }

  private fun formatMoney(value: Double): String =
    String.format(Locale.US, "%,.2f", value)

  fun getUriForFile(context: Context, file: File): Uri {
    return FileProvider.getUriForFile(
      context,
      "${context.packageName}.fileprovider",
      file
    )
  }
}
