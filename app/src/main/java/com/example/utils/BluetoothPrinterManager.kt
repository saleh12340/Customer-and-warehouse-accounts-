package com.example.utils

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.util.Base64
import com.example.data.InvoiceEntity
import com.example.data.InvoiceItemEntity
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object BluetoothPrinterManager {

  private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
  private var bluetoothSocket: BluetoothSocket? = null
  private var outputStream: OutputStream? = null
  private var connectedDeviceName: String = ""
  private var connectedDeviceAddress: String = ""

  fun isBluetoothAvailable(): Boolean = BluetoothAdapter.getDefaultAdapter() != null

  fun isBluetoothEnabled(): Boolean = try {
    BluetoothAdapter.getDefaultAdapter()?.isEnabled == true
  } catch (_: SecurityException) {
    false
  }

  @SuppressLint("MissingPermission")
  fun getPairedDevices(): Set<BluetoothDevice> {
    return try {
      val adapter = BluetoothAdapter.getDefaultAdapter() ?: return emptySet()
      if (!adapter.isEnabled) return emptySet()
      adapter.bondedDevices ?: emptySet()
    } catch (_: SecurityException) {
      emptySet()
    }
  }

  @SuppressLint("MissingPermission")
  fun connect(device: BluetoothDevice): Boolean {
    return connectWithResult(device).first
  }

  @SuppressLint("MissingPermission")
  fun connectWithResult(device: BluetoothDevice): Pair<Boolean, String> {
    return try {
      disconnect()
      val adapter = BluetoothAdapter.getDefaultAdapter()
      if (adapter == null) return false to "البلوتوث غير مدعوم على هذا الجهاز"
      if (!adapter.isEnabled) return false to "الرجاء تشغيل البلوتوث أولاً"
      try { adapter.cancelDiscovery() } catch (_: SecurityException) {}

      val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
      socket.connect()
      bluetoothSocket = socket
      outputStream = socket.outputStream
      connectedDeviceName = try { device.name ?: "طابعة بلوتوث" } catch (_: SecurityException) { "طابعة بلوتوث" }
      connectedDeviceAddress = device.address
      true to "تم الاتصال بالطابعة: $connectedDeviceName"
    } catch (_: SecurityException) {
      disconnect()
      false to "أذونات البلوتوث مطلوبة للاتصال بالطابعة"
    } catch (e: Exception) {
      e.printStackTrace()
      disconnect()
      false to "تعذر الاتصال بالطابعة: ${e.message ?: "تأكد من تشغيل الطابعة"}"
    }
  }

  fun disconnect() {
    try { outputStream?.close() } catch (_: Exception) {}
    try { bluetoothSocket?.close() } catch (_: Exception) {}
    outputStream = null
    bluetoothSocket = null
    connectedDeviceName = ""
    connectedDeviceAddress = ""
  }

  fun isConnected(): Boolean = bluetoothSocket?.isConnected == true && outputStream != null

  fun getConnectionInfo(): Pair<String, String> =
    if (isConnected()) connectedDeviceName to connectedDeviceAddress else "" to ""

  fun printReceiptText(title: String, details: List<String>, footer: String, paperWidthMm: Int = 58): Boolean {
    val receipt = buildString {
      appendLine("بقالة العزي للمواد الغذائية")
      appendLine(title)
      appendLine("================================")
      details.forEach { appendLine(it) }
      appendLine("--------------------------------")
      appendLine(footer)
      appendLine("شكراً لزيارتكم")
    }
    return printReceiptBitmap(receipt, paperWidthMm)
  }

  fun printReceiptBitmap(receiptText: String, paperWidthMm: Int = 58): Boolean {
    val width = if (paperWidthMm == 80) 576 else 384
    val fontSize = if (paperWidthMm == 80) 24f else 20f
    val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.BLACK
      textSize = fontSize
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    val staticLayout = StaticLayout.Builder.obtain(receiptText, 0, receiptText.length, textPaint, width - 16)
      .setAlignment(Layout.Alignment.ALIGN_CENTER)
      .setTextDirection(TextDirectionHeuristics.RTL)
      .setIncludePad(false)
      .setLineSpacing(0f, 1.25f)
      .build()

    val bitmap = Bitmap.createBitmap(width, staticLayout.height + 36, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.WHITE)
    canvas.translate(8f, 16f)
    staticLayout.draw(canvas)

    return sendBitmapToPrinter(bitmap).first
  }

  fun buildInvoiceReceiptBitmap(
    context: Context,
    invoice: InvoiceEntity,
    items: List<InvoiceItemEntity>,
    partyBalanceAfter: Double? = null,
    paperWidthMm: Int = 58
  ): Bitmap {
    val settings = StorePreferences.getSettings(context)
    val width = if (paperWidthMm == 80) 576 else 384
    val isWide = width >= 576
    val pad = 8f
    val contentW = width - pad * 2
    val currency = settings.currency
    val dateStr = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(Date(invoice.date))

    val titleSize = if (isWide) 28f else 22f
    val bodySize = if (isWide) 22f else 17f
    val smallSize = if (isWide) 19f else 15f

    val titlePaint = textPaint(titleSize, bold = true)
    val boldPaint = textPaint(bodySize, bold = true)
    val bodyPaint = textPaint(smallSize, bold = false)
    val linePaint = Paint().apply {
      color = Color.BLACK
      strokeWidth = 2f
    }

    // Column proportions (RTL: right-to-left => الصنف | الكمية | السعر | الإجمالي)
    val totalW = if (isWide) 115f else 82f
    val priceW = if (isWide) 105f else 74f
    val qtyW = if (isWide) 75f else 54f
    val nameW = contentW - totalW - priceW - qtyW

    val measuredItems = items.map { item ->
      val nameLayout = buildRtlLayout(item.itemName, bodyPaint, nameW.toInt() - 4, Layout.Alignment.ALIGN_NORMAL, maxLines = 3)
      val rowH = maxOf(if (isWide) 36 else 30, nameLayout.height + 10)
      Triple(item, nameLayout, rowH)
    }

    val headerEst = if (isWide) 165 else 140
    val thH = if (isWide) 36 else 30
    val rowsH = measuredItems.sumOf { it.third }
    val balanceH = if (partyBalanceAfter != null && invoice.partyId > 0) (if (isWide) 72 else 60) else 0
    val footerEst = (if (isWide) 170 else 145) + balanceH
    val totalHeight = headerEst + thH + rowsH + footerEst

    val bitmap = Bitmap.createBitmap(width, totalHeight.coerceAtLeast(240), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.WHITE)

    var y = 10f
    y += drawRtl(canvas, settings.storeName, pad, y, contentW, titlePaint, Layout.Alignment.ALIGN_CENTER) + 4f
    if (settings.storePhone.isNotBlank()) {
      y += drawRtl(canvas, "جوال: ${settings.storePhone}", pad, y, contentW, bodyPaint, Layout.Alignment.ALIGN_CENTER) + 4f
    }
    canvas.drawLine(pad, y, width - pad, y, linePaint)
    y += 8f

    val typeAr = InvoiceImageGenerator.invoiceTypeArabic(invoice.type)
    y += drawRtl(canvas, "$typeAr  -  رقم: ${invoice.invoiceNumber}", pad, y, contentW, boldPaint, Layout.Alignment.ALIGN_NORMAL) + 4f
    y += drawRtl(canvas, "التاريخ: $dateStr", pad, y, contentW, bodyPaint, Layout.Alignment.ALIGN_NORMAL) + 4f
    val partyLabel = if (invoice.type.equals("PURCHASE", true)) "المورد" else "العميل"
    y += drawRtl(canvas, "$partyLabel: ${invoice.partyName}", pad, y, contentW, boldPaint, Layout.Alignment.ALIGN_NORMAL) + 8f

    canvas.drawLine(pad, y, width - pad, y, linePaint)
    y += 6f

    // RTL Column positions
    val colTotalX = pad
    val colPriceX = colTotalX + totalW
    val colQtyX = colPriceX + priceW
    val colNameX = colQtyX + qtyW

    drawRtl(canvas, "الصنف", colNameX, y, nameW, boldPaint, Layout.Alignment.ALIGN_NORMAL)
    drawRtl(canvas, "الكمية", colQtyX, y, qtyW, boldPaint, Layout.Alignment.ALIGN_CENTER)
    drawRtl(canvas, "السعر", colPriceX, y, priceW, boldPaint, Layout.Alignment.ALIGN_CENTER)
    drawRtl(canvas, "الإجمالي", colTotalX, y, totalW, boldPaint, Layout.Alignment.ALIGN_OPPOSITE)
    y += thH
    canvas.drawLine(pad, y, width - pad, y, linePaint)
    y += 6f

    measuredItems.forEach { (item, nameLayout, rowH) ->
      canvas.save()
      canvas.translate(colNameX, y)
      nameLayout.draw(canvas)
      canvas.restore()

      drawRtl(canvas, item.quantity.toString(), colQtyX, y, qtyW, bodyPaint, Layout.Alignment.ALIGN_CENTER)
      drawRtl(canvas, formatNum(item.unitPrice), colPriceX, y, priceW, bodyPaint, Layout.Alignment.ALIGN_CENTER)
      drawRtl(canvas, formatNum(item.totalPrice), colTotalX, y, totalW, boldPaint, Layout.Alignment.ALIGN_OPPOSITE)
      y += rowH
    }

    canvas.drawLine(pad, y, width - pad, y, linePaint)
    y += 8f

    y += drawRtl(canvas, "الإجمالي: ${formatNum(invoice.totalAmount)} $currency", pad, y, contentW, boldPaint, Layout.Alignment.ALIGN_NORMAL) + 4f
    y += drawRtl(canvas, "المدفوع: ${formatNum(invoice.paidAmount)} $currency", pad, y, contentW, bodyPaint, Layout.Alignment.ALIGN_NORMAL) + 4f
    y += drawRtl(canvas, "المتبقي من الفاتورة: ${formatNum(invoice.remainingAmount)} $currency", pad, y, contentW, boldPaint, Layout.Alignment.ALIGN_NORMAL) + 6f

    if (partyBalanceAfter != null && invoice.partyId > 0) {
      val prevBalance = partyBalanceAfter - invoice.remainingAmount
      y += drawRtl(canvas, "الرصيد السابق: ${formatNum(prevBalance)} $currency", pad, y, contentW, bodyPaint, Layout.Alignment.ALIGN_NORMAL) + 4f
      val balLabel = if (partyBalanceAfter > 0) "عليه ${formatNum(partyBalanceAfter)}" else if (partyBalanceAfter < 0) "له ${formatNum(-partyBalanceAfter)}" else "0"
      y += drawRtl(canvas, "رصيد الحساب النهائي: $balLabel $currency", pad, y, contentW, boldPaint, Layout.Alignment.ALIGN_NORMAL) + 6f
    }

    canvas.drawLine(pad, y, width - pad, y, linePaint)
    y += 8f
    drawRtl(canvas, "شكراً لتعاملكم معنا", pad, y, contentW, bodyPaint, Layout.Alignment.ALIGN_CENTER)

    // Crop unused bottom space cleanly to avoid excessive blank paper feed
    val finalH = (y + 34).toInt().coerceIn(200, bitmap.height)
    return if (finalH < bitmap.height) {
      Bitmap.createBitmap(bitmap, 0, 0, width, finalH).also { bitmap.recycle() }
    } else {
      bitmap
    }
  }

  fun renderInvoicePreviewBase64(
    context: Context,
    invoice: InvoiceEntity,
    items: List<InvoiceItemEntity>,
    partyBalanceAfter: Double?,
    paperWidthMm: Int
  ): String {
    val bitmap = buildInvoiceReceiptBitmap(context, invoice, items, partyBalanceAfter, paperWidthMm)
    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    bitmap.recycle()
    return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
  }

  fun printInvoiceReceipt(
    context: Context,
    invoice: InvoiceEntity,
    items: List<InvoiceItemEntity>,
    partyBalanceAfter: Double?,
    paperWidthMm: Int
  ): Pair<Boolean, String> {
    val bitmap = buildInvoiceReceiptBitmap(context, invoice, items, partyBalanceAfter, paperWidthMm)
    return sendBitmapToPrinter(bitmap)
  }

  private fun sendBitmapToPrinter(bitmap: Bitmap): Pair<Boolean, String> {
    val out = outputStream
    if (out == null || !isConnected()) {
      bitmap.recycle()
      return false to "الطابعة غير متصلة. الرجاء الاتصال بالطابعة من الإعدادات"
    }
    return try {
      val bytes = convertBitmapToEscPos(bitmap)
      bitmap.recycle()
      out.write(byteArrayOf(0x1B, 0x40)) // ESC @ Initialize
      // Send in chunks to avoid Bluetooth SPP buffer overflow on portable printers
      val chunkSize = 2048
      var offset = 0
      while (offset < bytes.size) {
        val len = minOf(chunkSize, bytes.size - offset)
        out.write(bytes, offset, len)
        offset += len
      }
      out.write(byteArrayOf(0x1B, 0x64, 0x03)) // ESC d 3 feed lines
      out.write(byteArrayOf(0x1D, 0x56, 0x42, 0x00)) // GS V B 0 partial cut (ignored safely if no cutter)
      out.flush()
      true to "تمت طباعة الإيصال بنجاح"
    } catch (e: Exception) {
      e.printStackTrace()
      disconnect()
      false to "انقطع الاتصال بالطابعة أثناء الطباعة: ${e.message ?: "خطأ غير معروف"}"
    }
  }

  private fun textPaint(size: Float, bold: Boolean = false) =
    TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.BLACK
      textSize = size
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

  private fun formatNum(value: Double): String {
    return if (value % 1.0 == 0.0) {
      String.format(Locale.US, "%,.0f", value)
    } else {
      String.format(Locale.US, "%,.2f", value)
    }
  }

  private fun convertBitmapToEscPos(bitmap: Bitmap): ByteArray {
    val width = bitmap.width
    val height = bitmap.height
    val bytesWidth = (width + 7) / 8
    val command = ByteArrayOutputStream(8 + bytesWidth * height)

    // GS v 0 raster bit image
    command.write(0x1D)
    command.write(0x76)
    command.write(0x30)
    command.write(0x00)
    command.write(bytesWidth and 0xFF)
    command.write((bytesWidth shr 8) and 0xFF)
    command.write(height and 0xFF)
    command.write((height shr 8) and 0xFF)

    for (y in 0 until height) {
      for (xBytes in 0 until bytesWidth) {
        var byteVal = 0
        for (bit in 0..7) {
          val x = xBytes * 8 + bit
          if (x < width) {
            val pixel = bitmap.getPixel(x, y)
            val red = Color.red(pixel)
            val green = Color.green(pixel)
            val blue = Color.blue(pixel)
            val luminance = (0.299 * red + 0.587 * green + 0.114 * blue).toInt()
            if (luminance < 160) {
              byteVal = byteVal or (1 shl (7 - bit))
            }
          }
        }
        command.write(byteVal)
      }
    }
    return command.toByteArray()
  }
}
