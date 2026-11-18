package com.example.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
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

object InvoiceImageGenerator {

  private const val IMG_WIDTH = 900
  private const val MARGIN = 36f

  fun invoiceTypeArabic(type: String): String = when (type.uppercase()) {
    "CASH" -> "بيع نقدي"
    "CREDIT" -> "بيع آجل"
    "SALE" -> "فاتورة مبيعات"
    "PURCHASE" -> "فاتورة مشتريات"
    else -> type
  }

  fun generateInvoiceImage(
    context: Context,
    invoice: InvoiceEntity,
    items: List<InvoiceItemEntity>,
    partyBalanceAfter: Double? = null
  ): File? {
    return try {
      val settings = StorePreferences.getSettings(context)
      val currency = settings.currency
      val dateStr = SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale.US).format(Date(invoice.date))

      val titlePaint = textPaint(34f, Color.WHITE, bold = true)
      val subHeaderPaint = textPaint(20f, Color.rgb(225, 236, 255))
      val metaBoldPaint = textPaint(22f, Color.rgb(33, 37, 41), bold = true)
      val metaPaint = textPaint(20f, Color.rgb(73, 80, 87))
      val thPaint = textPaint(20f, Color.WHITE, bold = true)
      val tdPaint = textPaint(20f, Color.rgb(33, 37, 41))
      val tdBoldPaint = textPaint(20f, Color.rgb(15, 76, 156), bold = true)
      val totalPaint = textPaint(24f, Color.rgb(15, 76, 156), bold = true)
      val greenPaint = textPaint(22f, Color.rgb(40, 167, 69), bold = true)
      val redPaint = textPaint(22f, Color.rgb(220, 53, 69), bold = true)
      val footerPaint = textPaint(18f, Color.rgb(108, 117, 125))

      val contentW = IMG_WIDTH - MARGIN * 2
      val nameW = 360f
      val qtyW = 100f
      val priceW = 160f
      val totalW = contentW - nameW - qtyW - priceW

      // Pre-measure dynamic row heights so no text ever clips or overlaps
      val itemLayouts = items.map { item ->
        val nameLayout = buildRtlLayout(item.itemName, tdPaint, nameW.toInt() - 12, Layout.Alignment.ALIGN_NORMAL, maxLines = 3)
        val rowH = maxOf(52, nameLayout.height + 20)
        Triple(item, nameLayout, rowH)
      }

      val headerHeight = 128f
      val infoHeight = 148f
      val tableHeaderH = 48f
      val rowsHeight = itemLayouts.sumOf { it.third }.toFloat()
      val balanceHeight = if (partyBalanceAfter != null && invoice.partyId > 0) 86f else 0f
      val totalsHeight = 140f + balanceHeight
      val footerHeight = 68f
      val totalHeight = (headerHeight + infoHeight + tableHeaderH + rowsHeight + totalsHeight + footerHeight).toInt().coerceAtLeast(520)

      val bitmap = Bitmap.createBitmap(IMG_WIDTH, totalHeight, Bitmap.Config.ARGB_8888)
      val canvas = Canvas(bitmap)
      canvas.drawColor(Color.WHITE)

      // Top dark blue banner
      val headerPaintBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(15, 76, 156) }
      canvas.drawRect(0f, 0f, IMG_WIDTH.toFloat(), headerHeight, headerPaintBg)

      var y = 24f
      y += drawLayout(canvas, settings.storeName, MARGIN, y, contentW, titlePaint, Layout.Alignment.ALIGN_CENTER) + 8f
      val contactInfo = listOfNotNull(
        settings.storePhone.takeIf { it.isNotBlank() }?.let { "تلفون: $it" },
        settings.storeAddress.takeIf { it.isNotBlank() }
      ).joinToString("  |  ").ifBlank { "نظام الفواتير والحسابات" }
      drawLayout(canvas, contactInfo, MARGIN, y, contentW, subHeaderPaint, Layout.Alignment.ALIGN_CENTER)

      // Invoice metadata
      y = headerHeight + 22f
      y += drawLayout(canvas, "${invoiceTypeArabic(invoice.type)}   -   رقم: ${invoice.invoiceNumber}", MARGIN, y, contentW, metaBoldPaint, Layout.Alignment.ALIGN_NORMAL) + 8f
      y += drawLayout(canvas, "التاريخ: $dateStr", MARGIN, y, contentW, metaPaint, Layout.Alignment.ALIGN_NORMAL) + 8f
      val partyLabel = if (invoice.type.equals("PURCHASE", true)) "المورد" else "العميل"
      drawLayout(canvas, "$partyLabel: ${invoice.partyName}", MARGIN, y, contentW, metaBoldPaint, Layout.Alignment.ALIGN_NORMAL)

      // Table Header (RTL: right-to-left => الصنف | الكمية | السعر | الإجمالي)
      y = headerHeight + infoHeight
      val thBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 111, 235) }
      canvas.drawRoundRect(MARGIN, y, IMG_WIDTH - MARGIN, y + tableHeaderH, 8f, 8f, thBg)

      val colTotalX = MARGIN
      val colPriceX = colTotalX + totalW
      val colQtyX = colPriceX + priceW
      val colNameX = colQtyX + qtyW

      val thTextY = y + 12f
      drawLayout(canvas, "الصنف", colNameX + 6f, thTextY, nameW - 12f, thPaint, Layout.Alignment.ALIGN_NORMAL)
      drawLayout(canvas, "الكمية", colQtyX + 4f, thTextY, qtyW - 8f, thPaint, Layout.Alignment.ALIGN_CENTER)
      drawLayout(canvas, "السعر", colPriceX + 4f, thTextY, priceW - 8f, thPaint, Layout.Alignment.ALIGN_CENTER)
      drawLayout(canvas, "الإجمالي", colTotalX + 6f, thTextY, totalW - 12f, thPaint, Layout.Alignment.ALIGN_OPPOSITE)

      y += tableHeaderH
      val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(222, 226, 230)
        strokeWidth = 1.5f
      }
      val altRowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(248, 249, 250) }

      itemLayouts.forEachIndexed { idx, (item, nameLayout, rowH) ->
        if (idx % 2 == 1) {
          canvas.drawRect(MARGIN, y, IMG_WIDTH - MARGIN, y + rowH, altRowPaint)
        }
        val textY = y + 10f
        canvas.save()
        canvas.translate(colNameX + 6f, textY)
        nameLayout.draw(canvas)
        canvas.restore()

        drawLayout(canvas, item.quantity.toString(), colQtyX + 4f, textY, qtyW - 8f, tdPaint, Layout.Alignment.ALIGN_CENTER)
        drawLayout(canvas, formatMoney(item.unitPrice), colPriceX + 4f, textY, priceW - 8f, tdPaint, Layout.Alignment.ALIGN_CENTER)
        drawLayout(canvas, formatMoney(item.totalPrice), colTotalX + 6f, textY, totalW - 12f, tdBoldPaint, Layout.Alignment.ALIGN_OPPOSITE)

        y += rowH
        canvas.drawLine(MARGIN, y, IMG_WIDTH - MARGIN, y, dividerPaint)
      }

      // Summary card
      y += 16f
      val summaryBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(241, 245, 252) }
      canvas.drawRoundRect(MARGIN, y, IMG_WIDTH - MARGIN, y + totalsHeight - 28f, 10f, 10f, summaryBg)

      var sy = y + 16f
      sy += drawLayout(canvas, "إجمالي الفاتورة: ${formatMoney(invoice.totalAmount)} $currency", MARGIN + 16f, sy, contentW - 32f, totalPaint, Layout.Alignment.ALIGN_NORMAL) + 8f
      sy += drawLayout(canvas, "المبلغ المدفوع: ${formatMoney(invoice.paidAmount)} $currency", MARGIN + 16f, sy, contentW - 32f, greenPaint, Layout.Alignment.ALIGN_NORMAL) + 8f
      val remPaint = if (invoice.remainingAmount > 0.000001) redPaint else greenPaint
      sy += drawLayout(canvas, "المتبقي من الفاتورة: ${formatMoney(invoice.remainingAmount)} $currency", MARGIN + 16f, sy, contentW - 32f, remPaint, Layout.Alignment.ALIGN_NORMAL) + 10f

      if (partyBalanceAfter != null && invoice.partyId > 0) {
        val prevBalance = partyBalanceAfter - invoice.remainingAmount
        val balText = if (partyBalanceAfter > 0) "عليه ${formatMoney(partyBalanceAfter)} $currency" else if (partyBalanceAfter < 0) "له ${formatMoney(-partyBalanceAfter)} $currency" else "مسدد بالكامل (0)"
        sy += drawLayout(canvas, "الرصيد السابق للحساب: ${formatMoney(prevBalance)} $currency", MARGIN + 16f, sy, contentW - 32f, metaPaint, Layout.Alignment.ALIGN_NORMAL) + 6f
        drawLayout(canvas, "الرصيد التراكمي النهائي للحساب: $balText", MARGIN + 16f, sy, contentW - 32f, metaBoldPaint, Layout.Alignment.ALIGN_NORMAL)
      }

      // Footer
      val fy = totalHeight - 44f
      drawLayout(canvas, "شكراً لتعاملكم معنا - ${settings.storeName}", MARGIN, fy, contentW, footerPaint, Layout.Alignment.ALIGN_CENTER)

      val dir = File(context.cacheDir, "shared_invoices").apply { mkdirs() }
      val safeNumber = invoice.invoiceNumber.replace(Regex("[^A-Za-z0-9_-]"), "_").take(48)
      val outFile = File(dir, "invoice_${safeNumber.ifBlank { System.currentTimeMillis().toString() }}.png")
      FileOutputStream(outFile).use { stream ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
      }
      bitmap.recycle()
      runCatching {
        AppDownloadsFolderManager.saveFile(
          context = context,
          subfolder = AppDownloadsFolderManager.SUB_IMAGES,
          sourceFile = outFile,
          displayName = outFile.name,
          mimeType = "image/png",
          overwriteExisting = true
        )
      }
      outFile
    } catch (e: Exception) {
      e.printStackTrace()
      null
    }
  }

  fun getUriForFile(context: Context, file: File): Uri {
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
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

  private fun drawLayout(
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

  private fun formatMoney(value: Double): String {
    return if (value % 1.0 == 0.0) {
      String.format(Locale.US, "%,.0f", value)
    } else {
      String.format(Locale.US, "%,.2f", value)
    }
  }
}
