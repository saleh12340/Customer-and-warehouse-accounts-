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
  private const val BOTTOM_LIMIT = PAGE_HEIGHT - 56f

  fun generateInvoicePdf(
    context: Context,
    invoice: InvoiceEntity,
    items: List<InvoiceItemEntity>,
    partyBalanceAfter: Double? = null
  ): File? {
    val document = PdfDocument()
    return try {
      val settings = StorePreferences.getSettings(context)
      val currency = settings.currency
      val safeNumber = invoice.invoiceNumber.replace(Regex("[^A-Za-z0-9_-]"), "_").take(48)
      val pdfDir = File(context.filesDir, "invoices").apply { mkdirs() }
      val output = File(pdfDir, "invoice_${safeNumber.ifBlank { invoice.id.toString() }}.pdf")
      val date = SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale.US).format(Date(invoice.date))

      val titlePaint = textPaint(19f, Color.rgb(15, 76, 156), true)
      val subPaint = textPaint(10f, Color.rgb(90, 98, 106))
      val bodyPaint = textPaint(11f, Color.BLACK)
      val smallPaint = textPaint(9f, Color.DKGRAY)
      val headerPaint = textPaint(10f, Color.WHITE, true)
      val totalPaint = textPaint(11.5f, Color.BLACK, true)
      val accentTotalPaint = textPaint(12.5f, Color.rgb(15, 76, 156), true)
      val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(218, 222, 226)
        strokeWidth = 0.8f
      }
      val headerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(15, 76, 156) }
      val summaryFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(241, 245, 252) }

      // RTL column widths summing to CONTENT_WIDTH (523f)
      val totalW = 110f
      val priceW = 95f
      val qtyW = 65f
      val nameW = CONTENT_WIDTH - totalW - priceW - qtyW

      val colTotalX = MARGIN
      val colPriceX = colTotalX + totalW
      val colQtyX = colPriceX + priceW
      val colNameX = colQtyX + qtyW

      val measured = measureItemsForTest(items)
      val pagesItems = planPages(measured)
      val lastPageItemsH = pagesItems.last().sumOf { it.rowH.toDouble() }.toFloat()
      val lastPageStartY = if (pagesItems.size == 1) 185f + 34f else 108f + 34f
      val summaryHeight = if (partyBalanceAfter != null && invoice.partyId > 0) 136f else 92f
      val summaryNeedsExtraPage = (lastPageStartY + lastPageItemsH + summaryHeight) > BOTTOM_LIMIT
      val totalPagesCount = pagesItems.size + if (summaryNeedsExtraPage) 1 else 0

      var pageNumber = 0
      pagesItems.forEachIndexed { idx, pageRows ->
        pageNumber++
        var pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
        var page = document.startPage(pageInfo)
        var canvas = page.canvas
        var y = MARGIN

        if (idx == 0) {
          y += drawRtl(canvas, settings.storeName, MARGIN, y, CONTENT_WIDTH, titlePaint, Layout.Alignment.ALIGN_NORMAL) + 4f
          val storeContact = listOfNotNull(
            settings.storePhone.takeIf { it.isNotBlank() }?.let { "تلفون: $it" },
            settings.storeAddress.takeIf { it.isNotBlank() }
          ).joinToString("  |  ")
          if (storeContact.isNotBlank()) {
            y += drawRtl(canvas, storeContact, MARGIN, y, CONTENT_WIDTH, subPaint, Layout.Alignment.ALIGN_NORMAL) + 4f
          }
          val typeAr = InvoiceImageGenerator.invoiceTypeArabic(invoice.type)
          y += drawRtl(canvas, "$typeAr   |   رقم الفاتورة: ${invoice.invoiceNumber}", MARGIN, y, CONTENT_WIDTH, totalPaint, Layout.Alignment.ALIGN_NORMAL) + 4f
          y += drawRtl(canvas, "التاريخ: $date", MARGIN, y, CONTENT_WIDTH, bodyPaint, Layout.Alignment.ALIGN_NORMAL) + 4f
          val partyLabel = if (invoice.type.equals("PURCHASE", true)) "المورد" else "العميل"
          y += drawRtl(canvas, "$partyLabel: ${invoice.partyName}", MARGIN, y, CONTENT_WIDTH, totalPaint, Layout.Alignment.ALIGN_NORMAL) + 10f
        } else {
          y += drawRtl(canvas, "${settings.storeName} - تابع فاتورة ${invoice.invoiceNumber}", MARGIN, y, CONTENT_WIDTH, totalPaint, Layout.Alignment.ALIGN_NORMAL) + 12f
        }

        // Table Header
        val tableTop = y
        canvas.drawRoundRect(MARGIN, tableTop, PAGE_WIDTH - MARGIN, tableTop + 28f, 4f, 4f, headerFill)
        drawRtl(canvas, "الصنف", colNameX + 6f, tableTop + 7f, nameW - 12f, headerPaint, Layout.Alignment.ALIGN_NORMAL)
        drawRtl(canvas, "الكمية", colQtyX + 4f, tableTop + 7f, qtyW - 8f, headerPaint, Layout.Alignment.ALIGN_CENTER)
        drawRtl(canvas, "سعر الوحدة", colPriceX + 4f, tableTop + 7f, priceW - 8f, headerPaint, Layout.Alignment.ALIGN_CENTER)
        drawRtl(canvas, "الإجمالي", colTotalX + 6f, tableTop + 7f, totalW - 12f, headerPaint, Layout.Alignment.ALIGN_OPPOSITE)

        y = tableTop + 34f
        pageRows.forEach { (item, nameLayout, rowH) ->
          canvas.save()
          canvas.translate(colNameX + 6f, y)
          nameLayout.draw(canvas)
          canvas.restore()

          drawRtl(canvas, item.quantity.toString(), colQtyX + 4f, y, qtyW - 8f, bodyPaint, Layout.Alignment.ALIGN_CENTER)
          drawRtl(canvas, formatMoney(item.unitPrice), colPriceX + 4f, y, priceW - 8f, bodyPaint, Layout.Alignment.ALIGN_CENTER)
          drawRtl(canvas, formatMoney(item.totalPrice), colTotalX + 6f, y, totalW - 12f, totalPaint, Layout.Alignment.ALIGN_OPPOSITE)
          y += rowH - 5f
          canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, linePaint)
          y += 5f
        }

        val isLastItemPage = idx == pagesItems.lastIndex
        if (isLastItemPage) {
          if (y + summaryHeight > BOTTOM_LIMIT) {
            drawRtl(canvas, "صفحة $pageNumber / $totalPagesCount", MARGIN, PAGE_HEIGHT - 28f, CONTENT_WIDTH, smallPaint, Layout.Alignment.ALIGN_CENTER)
            document.finishPage(page)

            pageNumber++
            pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
            page = document.startPage(pageInfo)
            canvas = page.canvas
            y = MARGIN
            y += drawRtl(canvas, "${settings.storeName} - ملخص فاتورة ${invoice.invoiceNumber}", MARGIN, y, CONTENT_WIDTH, totalPaint, Layout.Alignment.ALIGN_NORMAL) + 12f
          }

          y += 10f
          canvas.drawRoundRect(MARGIN, y, PAGE_WIDTH - MARGIN, y + summaryHeight - 10f, 6f, 6f, summaryFill)
          var sy = y + 12f
          sy += drawRtl(canvas, "إجمالي الفاتورة: ${formatMoney(invoice.totalAmount)} $currency", MARGIN + 12f, sy, CONTENT_WIDTH - 24f, accentTotalPaint, Layout.Alignment.ALIGN_NORMAL) + 6f
          sy += drawRtl(canvas, "المدفوع: ${formatMoney(invoice.paidAmount)} $currency", MARGIN + 12f, sy, CONTENT_WIDTH - 24f, bodyPaint, Layout.Alignment.ALIGN_NORMAL) + 6f
          sy += drawRtl(canvas, "المتبقي من الفاتورة: ${formatMoney(invoice.remainingAmount)} $currency", MARGIN + 12f, sy, CONTENT_WIDTH - 24f, totalPaint, Layout.Alignment.ALIGN_NORMAL) + 8f

          if (partyBalanceAfter != null && invoice.partyId > 0) {
            val prevBalance = partyBalanceAfter - invoice.remainingAmount
            sy += drawRtl(canvas, "الرصيد السابق للحساب: ${formatMoney(prevBalance)} $currency", MARGIN + 12f, sy, CONTENT_WIDTH - 24f, subPaint, Layout.Alignment.ALIGN_NORMAL) + 5f
            val balLabel = if (partyBalanceAfter > 0) "عليه ${formatMoney(partyBalanceAfter)}" else if (partyBalanceAfter < 0) "له ${formatMoney(-partyBalanceAfter)}" else "مسدد بالكامل (0)"
            drawRtl(canvas, "الرصيد التراكمي النهائي للحساب: $balLabel $currency", MARGIN + 12f, sy, CONTENT_WIDTH - 24f, totalPaint, Layout.Alignment.ALIGN_NORMAL)
          }
        }

        drawRtl(canvas, "صفحة $pageNumber / $totalPagesCount", MARGIN, PAGE_HEIGHT - 28f, CONTENT_WIDTH, smallPaint, Layout.Alignment.ALIGN_CENTER)
        document.finishPage(page)
      }

      FileOutputStream(output).use { stream -> document.writeTo(stream) }
      output
    } catch (error: Exception) {
      error.printStackTrace()
      null
    } finally {
      try {
        document.close()
      } catch (_: Exception) {
      }
    }
  }

  internal data class MeasuredItem(val item: InvoiceItemEntity, val nameLayout: StaticLayout, val rowH: Float)

  internal fun planPages(measured: List<MeasuredItem>): List<List<MeasuredItem>> {
    val pagesItems = mutableListOf<List<MeasuredItem>>()
    var currentList = mutableListOf<MeasuredItem>()
    var currentPageY = 185f + 34f
    for (entry in measured) {
      if (currentList.isNotEmpty() && currentPageY + entry.rowH > BOTTOM_LIMIT) {
        pagesItems.add(currentList)
        currentList = mutableListOf()
        currentPageY = 108f + 34f
      }
      currentList.add(entry)
      currentPageY += entry.rowH
    }
    if (currentList.isNotEmpty() || pagesItems.isEmpty()) {
      pagesItems.add(currentList)
    }
    return pagesItems
  }

  internal fun measureItemsForTest(items: List<InvoiceItemEntity>): List<MeasuredItem> {
    val bodyPaint = textPaint(11f, Color.BLACK)
    val nameW = CONTENT_WIDTH - 110f - 95f - 65f
    return items.map { item ->
      val layout = buildRtlLayout(item.itemName, bodyPaint, nameW.toInt() - 12, Layout.Alignment.ALIGN_NORMAL, maxLines = 4)
      val rowH = maxOf(28f, layout.height + 14f)
      MeasuredItem(item, layout, rowH)
    }
  }

  fun getSavedPdfFile(context: Context, invoiceNumber: String): File? {
    val safeNumber = invoiceNumber.replace(Regex("[^A-Za-z0-9_-]"), "_").take(48)
    val file = File(File(context.filesDir, "invoices"), "invoice_${safeNumber}.pdf")
    return file.takeIf { it.exists() && it.length() > 0 }
  }

  private fun textPaint(size: Float, color: Int, bold: Boolean = false) =
    TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      textSize = size
      this.color = color
      typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
    }

  private fun buildRtlLayout(
    text: String,
    paint: TextPaint,
    width: Int,
    alignment: Layout.Alignment,
    maxLines: Int = 2
  ): StaticLayout {
    return StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
      .setAlignment(alignment)
      .setTextDirection(TextDirectionHeuristics.RTL)
      .setIncludePad(false)
      .setMaxLines(maxLines)
      .build()
  }

  private fun drawRtl(
    canvas: Canvas,
    text: String,
    x: Float,
    y: Float,
    width: Float,
    paint: TextPaint,
    alignment: Layout.Alignment
  ): Int {
    val layout = buildRtlLayout(text, paint, width.toInt(), alignment)
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
