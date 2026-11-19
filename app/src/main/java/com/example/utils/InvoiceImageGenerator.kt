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
    "CASH" -> "فاتورة بيع نقدي"
    "CREDIT" -> "فاتورة بيع آجل"
    "SALE" -> "فاتورة بيع"
    "PURCHASE" -> "فاتورة شراء"
    else -> type
  }

  fun generateInvoiceImage(
    context: Context,
    invoice: InvoiceEntity,
    items: List<InvoiceItemEntity>,
    partyBalanceAfter: Double? = null
  ): File? {
    var bitmap: Bitmap? = null
    return try {
      val settings = StorePreferences.getSettings(context)
      // Share exactly the same narrow 58/80mm receipt bitmap used by Bluetooth printing.
      bitmap = BluetoothPrinterManager.buildInvoiceReceiptBitmap(
        context = context,
        invoice = invoice,
        items = items,
        partyBalanceAfter = partyBalanceAfter,
        paperWidthMm = if (settings.printerWidthMm == 80) 80 else 58
      )
      val dir = File(context.cacheDir, "shared_invoices").apply { mkdirs() }
      val safeNumber = invoice.invoiceNumber.replace(Regex("[^A-Za-z0-9_-]"), "_").take(48)
      val outFile = File(dir, "thermal_invoice_${safeNumber.ifBlank { invoice.id.toString() }}.png")
      FileOutputStream(outFile).use { stream ->
        check(bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "تعذر ترميز صورة الإيصال الحراري" }
      }
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
    } finally {
      bitmap?.recycle()
    }
  }

  fun getUriForFile(context: Context, file: File): Uri {
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
  }

  private fun textPaint(context: Context?, size: Float, color: Int, bold: Boolean = false) =
    TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      textSize = size
      this.color = color
      typeface = if (bold) ArabicFontHelper.getBold(context) else ArabicFontHelper.getRegular(context)
    }

  private fun buildRtlLayout(
    text: String,
    paint: TextPaint,
    width: Int,
    alignment: Layout.Alignment,
    maxLines: Int = 20
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
