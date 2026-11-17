package com.example.utils

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.data.InvoiceEntity
import com.example.data.InvoiceItemEntity
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PdfGenerator {

  fun generateInvoicePdf(context: Context, invoice: InvoiceEntity, items: List<InvoiceItemEntity>): File? {
    try {
      val pdfDocument = PdfDocument()
      val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4 size
      val page = pdfDocument.startPage(pageInfo)
      val canvas: Canvas = page.canvas

      val paint = Paint().apply {
        color = Color.BLACK
        textSize = 14f
        isAntiAlias = true
      }

      val titlePaint = Paint().apply {
        color = Color.rgb(15, 76, 156) // Dark blue header
        textSize = 22f
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
      }

      val boldPaint = Paint().apply {
        color = Color.BLACK
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
      }

      val grayPaint = Paint().apply {
        color = Color.GRAY
        textSize = 12f
        isAntiAlias = true
      }

      var y = 50f
      val leftMargin = 50f
      val rightMargin = 545f

      // Header
      canvas.drawText("بقالة العزي للمواد الغذائية", leftMargin, y, titlePaint)
      y += 25f
      canvas.drawText("تلفون: 771234567 - صنعاء، اليمن", leftMargin, y, grayPaint)
      y += 35f

      // Invoice info
      val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
      val dateStr = dateFormat.format(Date(invoice.date))

      canvas.drawText("رقم الفاتورة: ${invoice.invoiceNumber}", leftMargin, y, boldPaint)
      canvas.drawText("التاريخ: $dateStr", rightMargin - 180f, y, boldPaint)
      y += 22f
      canvas.drawText("نوع الفاتورة: ${invoice.type}", leftMargin, y, boldPaint)
      canvas.drawText("العميل / المورد: ${invoice.partyName}", rightMargin - 180f, y, boldPaint)
      y += 35f

      // Table Header
      val tableTop = y
      canvas.drawRect(leftMargin, tableTop, rightMargin, tableTop + 30f, Paint().apply { color = Color.rgb(30, 111, 235) })
      
      val headerPaint = Paint().apply {
        color = Color.WHITE
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
      }
      canvas.drawText("الصنف", leftMargin + 10f, tableTop + 20f, headerPaint)
      canvas.drawText("الكمية", leftMargin + 250f, tableTop + 20f, headerPaint)
      canvas.drawText("السعر", leftMargin + 350f, tableTop + 20f, headerPaint)
      canvas.drawText("الإجمالي", rightMargin - 80f, tableTop + 20f, headerPaint)

      y += 45f

      // Table Items
      for (item in items) {
        canvas.drawText(item.itemName, leftMargin + 10f, y, paint)
        canvas.drawText(item.quantity.toString(), leftMargin + 250f, y, paint)
        canvas.drawText(item.unitPrice.toString(), leftMargin + 350f, y, paint)
        canvas.drawText(item.totalPrice.toString(), rightMargin - 80f, y, boldPaint)
        y += 28f
      }

      y += 20f
      // Divider
      canvas.drawLine(leftMargin, y, rightMargin, y, Paint().apply { color = Color.GRAY; strokeWidth = 1f })
      y += 30f

      // Totals
      canvas.drawText("إجمالي الفاتورة: ${invoice.totalAmount} ر.ي", rightMargin - 220f, y, boldPaint)
      y += 25f
      canvas.drawText("المدفوع: ${invoice.paidAmount} ر.ي", rightMargin - 220f, y, paint)
      y += 25f
      canvas.drawText("المتبقي: ${invoice.remainingAmount} ر.ي", rightMargin - 220f, y, boldPaint)

      pdfDocument.finishPage(page)

      val file = File(context.cacheDir, "invoice_${invoice.invoiceNumber}.pdf")
      pdfDocument.writeTo(FileOutputStream(file))
      pdfDocument.close()
      return file
    } catch (e: Exception) {
      e.printStackTrace()
      return null
    }
  }

  fun getUriForFile(context: Context, file: File): Uri {
    return FileProvider.getUriForFile(
      context,
      "${context.packageName}.fileprovider",
      file
    )
  }
}
