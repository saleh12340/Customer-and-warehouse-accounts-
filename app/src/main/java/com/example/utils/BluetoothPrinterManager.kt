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

  fun printReceiptText(
    title: String,
    details: List<String>,
    footer: String,
    paperWidthMm: Int = 58,
    context: Context? = null
  ): Boolean {
    val receipt = buildString {
      appendLine("بقالة العزي للمواد الغذائية")
      appendLine(title)
      appendLine("────────────────────────────")
      details.forEach { appendLine(it) }
      appendLine("────────────────────────────")
      appendLine(footer)
      appendLine("شكراً لتعاملكم معنا")
    }
    return printReceiptBitmap(receipt, paperWidthMm, context)
  }

  fun printReceiptBitmap(receiptText: String, paperWidthMm: Int = 58, context: Context? = null): Boolean {
    val width = if (paperWidthMm == 80) 576 else 384
    val fontSize = if (paperWidthMm == 80) 24f else 20f
    val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.BLACK
      textSize = fontSize
      typeface = ArabicFontHelper.getBold(context)
    }

    val staticLayout = StaticLayout.Builder.obtain(receiptText, 0, receiptText.length, textPaint, width - 8)
      .setAlignment(Layout.Alignment.ALIGN_CENTER)
      .setTextDirection(TextDirectionHeuristics.RTL)
      .setIncludePad(false)
      .setLineSpacing(0f, 1.15f)
      .build()

    val bitmap = Bitmap.createBitmap(width, staticLayout.height + 16, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.WHITE)
    canvas.translate(4f, 6f)
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
    // Minimal horizontal margins to maximize paper utilization
    val pad = if (isWide) 4f else 2f
    val contentW = width - pad * 2
    val currency = settings.currency
    val dateStr = SimpleDateFormat("yy/MM/dd HH:mm", Locale.US).format(Date(invoice.date))

    // Amiri font sizes tuned for HD thermal crispness at 203 DPI
    val titleSize = if (isWide) 30f else 23f
    val totalBoxSize = if (isWide) 25f else 19.5f
    val boldSize = if (isWide) 21f else 16.5f
    val bodySize = if (isWide) 19.5f else 15.5f
    val metaSize = if (isWide) 18.5f else 14.5f

    val titlePaint = textPaint(context, titleSize, bold = true)
    val totalBoxPaint = textPaint(context, totalBoxSize, bold = true)
    val boldPaint = textPaint(context, boldSize, bold = true)
    val bodyPaint = textPaint(context, bodySize, bold = false)
    val metaPaint = textPaint(context, metaSize, bold = true)
    val headerTextPaint = textPaint(context, boldSize, bold = true, color = Color.WHITE)

    val heavyLinePaint = Paint().apply {
      color = Color.BLACK
      strokeWidth = 2f
      style = Paint.Style.STROKE
    }
    val rulePaint = Paint().apply {
      color = Color.BLACK
      strokeWidth = 1.4f
      style = Paint.Style.STROKE
    }
    val solidBlackPaint = Paint().apply {
      color = Color.BLACK
      style = Paint.Style.FILL
    }

    // RTL Table columns (right-to-left): الصنف | الكمية | السعر | الإجمالي
    val totalW = if (isWide) 114f else 78f
    val priceW = if (isWide) 98f else 66f
    val qtyW = if (isWide) 60f else 42f
    val nameW = contentW - totalW - priceW - qtyW

    val colTotalX = pad
    val colPriceX = colTotalX + totalW
    val colQtyX = colPriceX + priceW
    val colNameX = colQtyX + qtyW

    val measuredItems = items.map { item ->
      val nameLayout = buildRtlLayout(
        text = item.itemName,
        paint = boldPaint,
        width = (nameW - 6f).toInt(),
        alignment = Layout.Alignment.ALIGN_NORMAL,
        maxLines = 3
      )
      val rowH = maxOf(if (isWide) 32 else 25, nameLayout.height + 6)
      Triple(item, nameLayout, rowH)
    }

    val storeContact = listOfNotNull(
      settings.storePhone.takeIf { it.isNotBlank() }?.let { "ج: $it" },
      settings.storeAddress.takeIf { it.isNotBlank() }
    ).joinToString(" | ")

    val typeAr = InvoiceImageGenerator.invoiceTypeArabic(invoice.type)
    val partyLabel = if (invoice.type.equals("PURCHASE", true)) "المورد" else "العميل"

    val headerEst = (if (isWide) 136 else 112) + if (storeContact.isNotBlank()) 24 else 0
    val thH = if (isWide) 34 else 27
    val rowsH = measuredItems.sumOf { it.third }
    val balanceH = if (partyBalanceAfter != null && invoice.partyId > 0) (if (isWide) 36 else 30) else 0
    val footerEst = (if (isWide) 132 else 110) + balanceH
    val estHeight = (headerEst + thH + rowsH + footerEst).coerceAtLeast(180)

    val bitmap = Bitmap.createBitmap(width, estHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.WHITE)

    var y = 3f
    // Store Header
    y += drawRtl(canvas, settings.storeName, pad, y, contentW, titlePaint, Layout.Alignment.ALIGN_CENTER) + 1f
    if (storeContact.isNotBlank()) {
      y += drawRtl(canvas, storeContact, pad, y, contentW, metaPaint, Layout.Alignment.ALIGN_CENTER) + 2f
    }
    canvas.drawLine(pad, y, width - pad, y, heavyLinePaint)
    y += 3f

    // Compact 2-Column Metadata Row (Right: Type + Number | Left: Date) to save paper height
    val rightMetaW = contentW * 0.56f
    val leftMetaW = contentW - rightMetaW
    val hRight = drawRtl(canvas, "$typeAr #${invoice.invoiceNumber}", pad + leftMetaW, y, rightMetaW, boldPaint, Layout.Alignment.ALIGN_NORMAL)
    val hLeft = drawRtl(canvas, dateStr, pad, y, leftMetaW, metaPaint, Layout.Alignment.ALIGN_OPPOSITE)
    y += maxOf(hRight, hLeft) + 2f

    // Party Line
    y += drawRtl(canvas, "$partyLabel: ${invoice.partyName}", pad, y, contentW, boldPaint, Layout.Alignment.ALIGN_NORMAL) + 4f

    // Table Start (HD Inverted Black Header Bar + Sharp Vertical/Horizontal Grid)
    val tableTop = y
    canvas.drawRect(pad, tableTop, width - pad, tableTop + thH, solidBlackPaint)
    val thTextY = tableTop + (if (isWide) 4f else 2f)
    drawRtl(canvas, "الصنف", colNameX + 3f, thTextY, nameW - 6f, headerTextPaint, Layout.Alignment.ALIGN_NORMAL)
    drawRtl(canvas, "عدد", colQtyX + 2f, thTextY, qtyW - 4f, headerTextPaint, Layout.Alignment.ALIGN_CENTER)
    drawRtl(canvas, "سعر", colPriceX + 2f, thTextY, priceW - 4f, headerTextPaint, Layout.Alignment.ALIGN_CENTER)
    drawRtl(canvas, "إجمالي", colTotalX + 3f, thTextY, totalW - 6f, headerTextPaint, Layout.Alignment.ALIGN_CENTER)
    y = tableTop + thH

    measuredItems.forEach { (item, nameLayout, rowH) ->
      val textY = y + 3f
      canvas.save()
      canvas.translate(colNameX + 3f, textY)
      nameLayout.draw(canvas)
      canvas.restore()

      drawRtl(canvas, item.quantity.toString(), colQtyX + 2f, textY, qtyW - 4f, boldPaint, Layout.Alignment.ALIGN_CENTER)
      drawRtl(canvas, formatNum(item.unitPrice), colPriceX + 2f, textY, priceW - 4f, bodyPaint, Layout.Alignment.ALIGN_CENTER)
      drawRtl(canvas, formatNum(item.totalPrice), colTotalX + 3f, textY, totalW - 6f, boldPaint, Layout.Alignment.ALIGN_CENTER)

      y += rowH
      canvas.drawLine(pad, y, width - pad, y, rulePaint)
    }

    // Draw crisp outer box & column dividers for table body
    canvas.drawRect(pad, tableTop, width - pad, y, heavyLinePaint)
    canvas.drawLine(colNameX, tableTop, colNameX, y, rulePaint)
    canvas.drawLine(colQtyX, tableTop, colQtyX, y, rulePaint)
    canvas.drawLine(colPriceX, tableTop, colPriceX, y, rulePaint)

    y += 5f

    // Highlighted Total Box
    val totalLayoutH = drawRtl(
      canvas = canvas,
      text = "الإجمالي: ${formatNum(invoice.totalAmount)} $currency",
      x = pad + 4f,
      y = y + 3f,
      width = contentW - 8f,
      paint = totalBoxPaint,
      alignment = Layout.Alignment.ALIGN_CENTER
    )
    val boxBottom = y + totalLayoutH + 6f
    canvas.drawRect(pad, y, width - pad, boxBottom, heavyLinePaint)
    y = boxBottom + 4f

    // Compact 2-Column Paid & Remaining Row (saves vertical paper)
    val halfW = contentW / 2f
    val paidH = drawRtl(canvas, "الواصل: ${formatNum(invoice.paidAmount)}", pad + halfW, y, halfW, boldPaint, Layout.Alignment.ALIGN_NORMAL)
    val remH = drawRtl(canvas, "المتبقي: ${formatNum(invoice.remainingAmount)}", pad, y, halfW, boldPaint, Layout.Alignment.ALIGN_OPPOSITE)
    y += maxOf(paidH, remH) + 3f

    if (partyBalanceAfter != null && invoice.partyId > 0) {
      val prevBalance = partyBalanceAfter - invoice.remainingAmount
      val balLabel = if (partyBalanceAfter > 0) "عليه ${formatNum(partyBalanceAfter)}"
      else if (partyBalanceAfter < 0) "له ${formatNum(-partyBalanceAfter)}"
      else "خالص 0"
      val prevH = drawRtl(canvas, "السابق: ${formatNum(prevBalance)}", pad + halfW, y, halfW, metaPaint, Layout.Alignment.ALIGN_NORMAL)
      val curH = drawRtl(canvas, "الحساب: $balLabel", pad, y, halfW, metaPaint, Layout.Alignment.ALIGN_OPPOSITE)
      y += maxOf(prevH, curH) + 3f
    }

    canvas.drawLine(pad, y, width - pad, y, rulePaint)
    y += 3f
    y += drawRtl(canvas, "شكراً لتعاملكم معنا • ${settings.storeName}", pad, y, contentW, metaPaint, Layout.Alignment.ALIGN_CENTER)

    // Tight crop with minimal trailing whitespace to avoid wasting thermal paper
    val finalH = (y + 10f).toInt().coerceIn(140, bitmap.height)
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

  fun saveThermalReceiptImageToDownloads(
    context: Context,
    invoice: InvoiceEntity,
    items: List<InvoiceItemEntity>,
    partyBalanceAfter: Double?,
    paperWidthMm: Int
  ): SavedDownloadResult {
    val bitmap = buildInvoiceReceiptBitmap(context, invoice, items, partyBalanceAfter, paperWidthMm)
    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    bitmap.recycle()
    val safeNumber = invoice.invoiceNumber.replace(Regex("[^A-Za-z0-9_-]"), "_").take(48)
      .ifBlank { invoice.id.toString() }
    val fileName = "thermal_${safeNumber}.png"
    return AppDownloadsFolderManager.saveBytes(
      context = context,
      subfolder = AppDownloadsFolderManager.SUB_IMAGES,
      fileName = fileName,
      mimeType = "image/png",
      bytes = out.toByteArray(),
      overwriteExisting = true
    )
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
      out.write(byteArrayOf(0x1B, 0x64, 0x02)) // Minimal 2-line paper feed
      out.write(byteArrayOf(0x1D, 0x56, 0x42, 0x00)) // GS V B 0 partial cut (ignored safely if no cutter)
      out.flush()
      true to "تمت طباعة الإيصال بنجاح"
    } catch (e: Exception) {
      e.printStackTrace()
      disconnect()
      false to "انقطع الاتصال بالطابعة أثناء الطباعة: ${e.message ?: "خطأ غير معروف"}"
    }
  }

  private fun textPaint(context: Context?, size: Float, bold: Boolean = false, color: Int = Color.BLACK) =
    TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      this.color = color
      textSize = size
      typeface = if (bold) ArabicFontHelper.getBold(context) else ArabicFontHelper.getRegular(context)
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

  internal fun convertBitmapToEscPos(bitmap: Bitmap): ByteArray {
    val width = bitmap.width
    val height = bitmap.height
    val bytesWidth = (width + 7) / 8
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

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
      val rowOffset = y * width
      for (xBytes in 0 until bytesWidth) {
        var byteVal = 0
        for (bit in 0..7) {
          val x = xBytes * 8 + bit
          if (x < width) {
            val pixel = pixels[rowOffset + x]
            val red = (pixel shr 16) and 0xFF
            val green = (pixel shr 8) and 0xFF
            val blue = pixel and 0xFF
            // High-definition contrast threshold tuned for Amiri strokes on 203 DPI thermal paper
            val luminance = (299 * red + 587 * green + 114 * blue) / 1000
            if (luminance < 192) {
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
