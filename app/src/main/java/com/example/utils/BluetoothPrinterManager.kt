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
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.util.Base64
import com.example.data.FinancialReportSnapshot
import com.example.data.InvoiceEntity
import com.example.data.InvoiceItemEntity
import com.example.data.LedgerEntryWithBalance
import com.example.data.NoteEntity
import com.example.data.TransactionReceiptSnapshot
import org.json.JSONArray
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
    paperWidthMm: Int = 58,
    renderScale: Int = 1
  ): Bitmap {
    val settings = StorePreferences.getSettings(context)
    val baseWidth = if (paperWidthMm == 80) 576 else 384
    val scale = renderScale.coerceIn(1, 4)
    val width = baseWidth
    val isWide = width >= 576
    val pad = if (isWide) 4f else 2f
    val contentW = width - pad * 2
    val currency = settings.currency
    val dateStr = SimpleDateFormat("yy/MM/dd HH:mm", Locale.US).format(Date(invoice.date))

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
        text = item.itemName + item.unitLabel.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty(),
        paint = boldPaint,
        width = (nameW - 6f).toInt(),
        alignment = Layout.Alignment.ALIGN_NORMAL,
        maxLines = 200
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

    val bitmap = Bitmap.createBitmap(width * scale, estHeight * scale, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.WHITE)
    if (scale > 1) canvas.scale(scale.toFloat(), scale.toFloat())

    var y = 3f
    y += drawRtl(canvas, settings.storeName, pad, y, contentW, titlePaint, Layout.Alignment.ALIGN_CENTER) + 1f
    if (storeContact.isNotBlank()) {
      y += drawRtl(canvas, storeContact, pad, y, contentW, metaPaint, Layout.Alignment.ALIGN_CENTER) + 2f
    }
    canvas.drawLine(pad, y, width - pad, y, heavyLinePaint)
    y += 3f

    val rightMetaW = contentW * 0.56f
    val leftMetaW = contentW - rightMetaW
    val hRight = drawRtl(canvas, "$typeAr #${invoice.invoiceNumber}", pad + leftMetaW, y, rightMetaW, boldPaint, Layout.Alignment.ALIGN_NORMAL)
    val hLeft = drawRtl(canvas, dateStr, pad, y, leftMetaW, metaPaint, Layout.Alignment.ALIGN_OPPOSITE)
    y += maxOf(hRight, hLeft) + 2f

    y += drawRtl(canvas, "$partyLabel: ${invoice.partyName}", pad, y, contentW, boldPaint, Layout.Alignment.ALIGN_NORMAL) + 4f

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

    canvas.drawRect(pad, tableTop, width - pad, y, heavyLinePaint)
    canvas.drawLine(colNameX, tableTop, colNameX, y, rulePaint)
    canvas.drawLine(colQtyX, tableTop, colQtyX, y, rulePaint)
    canvas.drawLine(colPriceX, tableTop, colPriceX, y, rulePaint)

    y += 5f

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

    val cropped = tightCropBitmap(bitmap, width * scale, y * scale)
    return cropped
  }

  /**
   * Builds a compact, high-contrast HD thermal receipt bitmap for a single customer/supplier transaction.
   */
  fun buildTransactionReceiptBitmap(
    context: Context,
    snapshot: TransactionReceiptSnapshot,
    paperWidthMm: Int = 58
  ): Bitmap {
    val settings = StorePreferences.getSettings(context)
    val width = if (paperWidthMm == 80) 576 else 384
    val isWide = width >= 576
    val pad = if (isWide) 6f else 3f
    val contentW = width - pad * 2
    val currency = settings.currency
    val tx = snapshot.transaction
    val dateStr = SimpleDateFormat("yy/MM/dd HH:mm", Locale.US).format(Date(tx.date))

    val titlePaint = textPaint(context, if (isWide) 28f else 22f, bold = true)
    val bigPaint = textPaint(context, if (isWide) 25f else 20f, bold = true)
    val boldPaint = textPaint(context, if (isWide) 21f else 16.5f, bold = true)
    val metaPaint = textPaint(context, if (isWide) 18.5f else 14.5f, bold = true)
    val headerWhite = textPaint(context, if (isWide) 21f else 16.5f, bold = true, color = Color.WHITE)

    val heavyLine = Paint().apply { color = Color.BLACK; strokeWidth = 2f; style = Paint.Style.STROKE }
    val ruleLine = Paint().apply { color = Color.BLACK; strokeWidth = 1.4f; style = Paint.Style.STROKE }
    val solidBlack = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }

    val estHeight = if (isWide) 520 else 440
    val bitmap = Bitmap.createBitmap(width, estHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.WHITE)

    var y = 4f
    y += drawRtl(canvas, settings.storeName, pad, y, contentW, titlePaint, Layout.Alignment.ALIGN_CENTER) + 2f
    val storeContact = listOfNotNull(
      settings.storePhone.takeIf { it.isNotBlank() }?.let { "ج: $it" },
      settings.storeAddress.takeIf { it.isNotBlank() }
    ).joinToString(" | ")
    if (storeContact.isNotBlank()) {
      y += drawRtl(canvas, storeContact, pad, y, contentW, metaPaint, Layout.Alignment.ALIGN_CENTER) + 2f
    }

    val bannerTop = y + 2f
    val bannerH = if (isWide) 34f else 28f
    canvas.drawRect(pad, bannerTop, width - pad, bannerTop + bannerH, solidBlack)
    val isCustomer = tx.partyType.equals("CUSTOMER", true)
    val voucherTitle = if (isCustomer) {
      if (tx.type.startsWith("DEBIT")) "سند قيد دين (عليه) #${tx.id}" else "سند قبض / سداد (له) #${tx.id}"
    } else {
      if (tx.type.startsWith("CREDIT")) "سند قيد مستحق للمورد #${tx.id}" else "سند سداد لمورد #${tx.id}"
    }
    drawRtl(canvas, voucherTitle, pad + 4f, bannerTop + 3f, contentW - 8f, headerWhite, Layout.Alignment.ALIGN_CENTER)
    y = bannerTop + bannerH + 5f

    val partyLabel = if (isCustomer) "العميل" else "المورد"
    y += drawRtl(canvas, "$partyLabel: ${tx.partyName}", pad, y, contentW, boldPaint, Layout.Alignment.ALIGN_NORMAL) + 2f
    y += drawRtl(canvas, "التاريخ: $dateStr", pad, y, contentW, metaPaint, Layout.Alignment.ALIGN_NORMAL) + 4f

    // Highlighted amount box
    val amtH = drawRtl(canvas, "المبلغ: ${formatNum(tx.amount)} $currency", pad + 4f, y + 4f, contentW - 8f, bigPaint, Layout.Alignment.ALIGN_CENTER)
    val boxBottom = y + amtH + 8f
    canvas.drawRect(pad, y, width - pad, boxBottom, heavyLine)
    y = boxBottom + 5f

    if (tx.description.isNotBlank()) {
      y += drawRtl(canvas, "البيان: ${tx.description}", pad, y, contentW, boldPaint, Layout.Alignment.ALIGN_NORMAL, maxLines = 200) + 4f
    }

    canvas.drawLine(pad, y, width - pad, y, ruleLine)
    y += 4f
    val halfW = contentW / 2f
    val hPrev = drawRtl(canvas, "السابق: ${formatNum(snapshot.previousBalance)}", pad + halfW, y, halfW, metaPaint, Layout.Alignment.ALIGN_NORMAL)
    val hCur = drawRtl(canvas, "الحالي: ${formatNum(snapshot.currentBalance)}", pad, y, halfW, boldPaint, Layout.Alignment.ALIGN_OPPOSITE)
    y += maxOf(hPrev, hCur) + 5f

    canvas.drawLine(pad, y, width - pad, y, ruleLine)
    y += 3f
    y += drawRtl(canvas, "شكراً لتعاملكم معنا • ${settings.storeName}", pad, y, contentW, metaPaint, Layout.Alignment.ALIGN_CENTER)

    return tightCropBitmap(bitmap, width, y)
  }

  /**
   * Builds a compact thermal account statement receipt (top 20 latest entries for crisp thermal length).
   */
  fun buildStatementReceiptBitmap(
    context: Context,
    partyType: String,
    partyName: String,
    partyPhone: String,
    currentBalance: Double,
    entries: List<LedgerEntryWithBalance>,
    paperWidthMm: Int = 58
  ): Bitmap {
    val settings = StorePreferences.getSettings(context)
    val width = if (paperWidthMm == 80) 576 else 384
    val isWide = width >= 576
    val pad = if (isWide) 4f else 2f
    val contentW = width - pad * 2
    val currency = settings.currency
    val visible = entries.take(20)

    val titlePaint = textPaint(context, if (isWide) 28f else 22f, bold = true)
    val bigPaint = textPaint(context, if (isWide) 23f else 18f, bold = true)
    val boldPaint = textPaint(context, if (isWide) 19f else 15f, bold = true)
    val metaPaint = textPaint(context, if (isWide) 17f else 13.5f, bold = true)
    val headerWhite = textPaint(context, if (isWide) 19f else 15f, bold = true, color = Color.WHITE)

    val heavyLine = Paint().apply { color = Color.BLACK; strokeWidth = 2f; style = Paint.Style.STROKE }
    val ruleLine = Paint().apply { color = Color.BLACK; strokeWidth = 1.2f; style = Paint.Style.STROKE }
    val solidBlack = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }

    val balW = if (isWide) 115f else 78f
    val amtW = if (isWide) 115f else 78f
    val descW = contentW - balW - amtW
    val colBalX = pad
    val colAmtX = colBalX + balW
    val colDescX = colAmtX + amtW

    val df = SimpleDateFormat("MM/dd", Locale.US)
    val measured = visible.map { entry ->
      val tx = entry.transaction
      val lineText = "${df.format(Date(tx.date))} ${tx.description}".trim()
      val layout = buildRtlLayout(lineText, boldPaint, (descW - 6f).toInt(), Layout.Alignment.ALIGN_NORMAL, maxLines = 20)
      val rowH = maxOf(if (isWide) 30 else 24, layout.height + 6)
      Triple(entry, layout, rowH)
    }

    val estH = 260 + measured.sumOf { it.third } + 100
    val bitmap = Bitmap.createBitmap(width, estH.coerceAtLeast(240), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.WHITE)

    var y = 3f
    y += drawRtl(canvas, settings.storeName, pad, y, contentW, titlePaint, Layout.Alignment.ALIGN_CENTER) + 2f
    val isCustomer = partyType.equals("CUSTOMER", true)
    val title = if (isCustomer) "كشف حساب عميل" else "كشف حساب مورد"
    y += drawRtl(canvas, "$title: $partyName", pad, y, contentW, boldPaint, Layout.Alignment.ALIGN_NORMAL) + 2f
    if (partyPhone.isNotBlank()) {
      y += drawRtl(canvas, "جوال: $partyPhone   |   تاريخ: ${SimpleDateFormat("yy/MM/dd", Locale.US).format(Date())}", pad, y, contentW, metaPaint, Layout.Alignment.ALIGN_NORMAL) + 3f
    }

    val tableTop = y
    val thH = if (isWide) 32 else 25
    canvas.drawRect(pad, tableTop, width - pad, tableTop + thH, solidBlack)
    val thTextY = tableTop + 2f
    drawRtl(canvas, "البيان", colDescX + 3f, thTextY, descW - 6f, headerWhite, Layout.Alignment.ALIGN_NORMAL)
    drawRtl(canvas, "الحركة", colAmtX + 2f, thTextY, amtW - 4f, headerWhite, Layout.Alignment.ALIGN_CENTER)
    drawRtl(canvas, "الرصيد", colBalX + 3f, thTextY, balW - 6f, headerWhite, Layout.Alignment.ALIGN_CENTER)
    y = tableTop + thH

    if (measured.isEmpty()) {
      y += drawRtl(canvas, "لا توجد حركات مسجلة", pad, y + 6f, contentW, boldPaint, Layout.Alignment.ALIGN_CENTER) + 10f
    } else {
      measured.forEach { (entry, layout, rowH) ->
        val tx = entry.transaction
        val ty = y + 3f
        canvas.save()
        canvas.translate(colDescX + 3f, ty)
        layout.draw(canvas)
        canvas.restore()

        val prefix = if (isCustomer) {
          if (tx.type.startsWith("DEBIT")) "+" else "-"
        } else {
          if (tx.type.startsWith("CREDIT")) "+" else "-"
        }
        drawRtl(canvas, "$prefix${formatNum(tx.amount)}", colAmtX + 2f, ty, amtW - 4f, boldPaint, Layout.Alignment.ALIGN_CENTER)
        drawRtl(canvas, formatNum(entry.runningBalance), colBalX + 3f, ty, balW - 6f, metaPaint, Layout.Alignment.ALIGN_CENTER)
        y += rowH
        canvas.drawLine(pad, y, width - pad, y, ruleLine)
      }
      canvas.drawRect(pad, tableTop, width - pad, y, heavyLine)
      canvas.drawLine(colDescX, tableTop, colDescX, y, ruleLine)
      canvas.drawLine(colAmtX, tableTop, colAmtX, y, ruleLine)
    }

    y += 5f
    val balText = when {
      currentBalance > 0.01 -> (if (isCustomer) "الرصيد عليه: " else "المستحق له: ") + "${formatNum(currentBalance)} $currency"
      currentBalance < -0.01 -> (if (isCustomer) "الرصيد له: " else "رصيد لنا: ") + "${formatNum(-currentBalance)} $currency"
      else -> "الحساب مسدد (0 $currency)"
    }
    val boxH = drawRtl(canvas, balText, pad + 4f, y + 4f, contentW - 8f, bigPaint, Layout.Alignment.ALIGN_CENTER)
    canvas.drawRect(pad, y, width - pad, y + boxH + 8f, heavyLine)
    y += boxH + 12f

    if (entries.size > visible.size) {
      y += drawRtl(canvas, "عرض أحدث ${visible.size} من ${entries.size} حركة (للكشف الكامل صدّر PDF)", pad, y, contentW, metaPaint, Layout.Alignment.ALIGN_CENTER) + 3f
    }
    y += drawRtl(canvas, "شكراً لتعاملكم معنا • ${settings.storeName}", pad, y, contentW, metaPaint, Layout.Alignment.ALIGN_CENTER)

    return tightCropBitmap(bitmap, width, y)
  }

  /**
   * Builds a compact thermal financial report summary receipt.
   */
  fun buildReportReceiptBitmap(
    context: Context,
    report: FinancialReportSnapshot,
    periodLabel: String,
    paperWidthMm: Int = 58
  ): Bitmap {
    val settings = StorePreferences.getSettings(context)
    val width = if (paperWidthMm == 80) 576 else 384
    val isWide = width >= 576
    val pad = if (isWide) 6f else 3f
    val contentW = width - pad * 2
    val currency = settings.currency

    val titlePaint = textPaint(context, if (isWide) 28f else 22f, bold = true)
    val boldPaint = textPaint(context, if (isWide) 20f else 16f, bold = true)
    val metaPaint = textPaint(context, if (isWide) 18f else 14f, bold = true)
    val headerWhite = textPaint(context, if (isWide) 20f else 16f, bold = true, color = Color.WHITE)
    val heavyLine = Paint().apply { color = Color.BLACK; strokeWidth = 2f; style = Paint.Style.STROKE }
    val ruleLine = Paint().apply { color = Color.BLACK; strokeWidth = 1.2f; style = Paint.Style.STROKE }
    val solidBlack = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }

    val estH = 440 + report.topItems.size * 34 + report.topDebtors.size * 34
    val bitmap = Bitmap.createBitmap(width, estH.coerceAtLeast(360), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.WHITE)

    var y = 4f
    y += drawRtl(canvas, settings.storeName, pad, y, contentW, titlePaint, Layout.Alignment.ALIGN_CENTER) + 2f
    val bannerH = if (isWide) 32f else 26f
    canvas.drawRect(pad, y, width - pad, y + bannerH, solidBlack)
    drawRtl(canvas, "ملخص التقرير المالي ($periodLabel)", pad + 4f, y + 2f, contentW - 8f, headerWhite, Layout.Alignment.ALIGN_CENTER)
    y += bannerH + 4f
    y += drawRtl(canvas, "تاريخ الطباعة: ${SimpleDateFormat("yy/MM/dd HH:mm", Locale.US).format(Date())}", pad, y, contentW, metaPaint, Layout.Alignment.ALIGN_CENTER) + 4f

    val rows = listOf(
      "المبيعات (${report.invoiceCount} فاتورة)" to "${formatNum(report.periodSales)} $currency",
      "صافي الربح (COGS)" to "${formatNum(report.grossProfit)} $currency",
      "المشتريات" to "${formatNum(report.periodPurchases)} $currency",
      "التحصيل النقدي" to "${formatNum(report.cashSales)} $currency",
      "مبيعات آجلة" to "${formatNum(report.creditSales)} $currency",
      "ديون العملاء" to "${formatNum(report.totalDebts)} $currency",
      "مستحقات الموردين" to "${formatNum(report.totalPayables)} $currency",
      "المخزون بالتكلفة" to "${formatNum(report.inventoryCostValue)} $currency",
      "المخزون بالبيع" to "${formatNum(report.inventoryRetailValue)} $currency"
    )
    val labelW = contentW * 0.54f
    val valW = contentW - labelW
    val boxTop = y
    rows.forEach { (k, v) ->
      val ry = y + 3f
      val h1 = drawRtl(canvas, k, pad + valW, ry, labelW - 4f, metaPaint, Layout.Alignment.ALIGN_NORMAL)
      val h2 = drawRtl(canvas, v, pad + 4f, ry, valW - 4f, boldPaint, Layout.Alignment.ALIGN_OPPOSITE)
      y += maxOf(h1, h2) + 5f
      canvas.drawLine(pad, y, width - pad, y, ruleLine)
    }
    canvas.drawRect(pad, boxTop, width - pad, y, heavyLine)
    y += 5f

    if (report.topItems.isNotEmpty()) {
      y += drawRtl(canvas, "الأصناف الأكثر مبيعاً:", pad, y, contentW, boldPaint, Layout.Alignment.ALIGN_NORMAL) + 2f
      report.topItems.take(5).forEach { itItem ->
        y += drawRtl(canvas, "• ${itItem.itemName} (${itItem.totalQuantity}) = ${formatNum(itItem.totalRevenue)}", pad, y, contentW, metaPaint, Layout.Alignment.ALIGN_NORMAL) + 2f
      }
      y += 4f
    }

    if (report.topDebtors.isNotEmpty()) {
      y += drawRtl(canvas, "أعلى العملاء مديونية:", pad, y, contentW, boldPaint, Layout.Alignment.ALIGN_NORMAL) + 2f
      report.topDebtors.take(5).forEach { d ->
        y += drawRtl(canvas, "• ${d.name}: ${formatNum(d.balance)} $currency", pad, y, contentW, metaPaint, Layout.Alignment.ALIGN_NORMAL) + 2f
      }
    }

    canvas.drawLine(pad, y, width - pad, y, ruleLine)
    y += 3f
    y += drawRtl(canvas, settings.storeName, pad, y, contentW, metaPaint, Layout.Alignment.ALIGN_CENTER)

    return tightCropBitmap(bitmap, width, y)
  }

  /**
   * Builds a compact, high-contrast HD thermal receipt bitmap for a two-panel note.
   */
  fun buildNoteReceiptBitmap(
    context: Context,
    note: NoteEntity,
    paperWidthMm: Int = 58
  ): Bitmap {
    val width = if (paperWidthMm == 80) 576 else 384
    val isWide = width >= 576

    // Use nearly the full paper width: two equal item panels, with no store/title/date
    // header, no panel captions, and no column-heading band.
    val pad = 1f
    val contentW = width - pad * 2
    val panelGap = if (isWide) 3f else 2f
    val panelW = (contentW - panelGap) / 2f
    val qtyW = if (isWide) 58f else 43f
    val detailW = panelW - qtyW

    val bodyPaint = textPaint(context, if (isWide) 22f else 17f, bold = true)
    val qtyPaint = textPaint(context, if (isWide) 21f else 16.5f, bold = true)
    val ruleLine = Paint().apply {
      color = Color.BLACK
      strokeWidth = if (isWide) 1.5f else 1.2f
      style = Paint.Style.STROKE
    }
    val outerLine = Paint().apply {
      color = Color.BLACK
      strokeWidth = if (isWide) 2f else 1.6f
      style = Paint.Style.STROKE
    }

    fun parseRows(json: String): List<Pair<String, String>> {
      val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
      return (0 until arr.length()).mapNotNull { i ->
        val obj = arr.optJSONObject(i) ?: return@mapNotNull null
        val qty = obj.optString("qty", "").trim()
        val detail = obj.optString("detail", "").trim()
        if (qty.isBlank() && detail.isBlank()) null else qty to detail
      }
    }

    data class NotePrintRow(val qty: String, val detail: StaticLayout, val height: Int)

    fun measureRows(source: List<Pair<String, String>>): List<NotePrintRow> =
      source.map { (qty, detail) ->
        val layout = buildRtlLayout(
          text = detail,
          paint = bodyPaint,
          width = (detailW - 6f).toInt().coerceAtLeast(24),
          alignment = Layout.Alignment.ALIGN_NORMAL,
          maxLines = 40
        )
        NotePrintRow(
          qty = qty,
          detail = layout,
          height = maxOf(if (isWide) 34 else 29, layout.height + 8)
        )
      }

    // The first section stays on the right, matching the Notes screen.
    val rightRows = measureRows(parseRows(note.rightRowsJson))
    val leftRows = measureRows(parseRows(note.leftRowsJson))
    val maxRows = maxOf(rightRows.size, leftRows.size)
    val rowsHeight = (0 until maxRows).sumOf { index ->
      maxOf(
        rightRows.getOrNull(index)?.height ?: 0,
        leftRows.getOrNull(index)?.height ?: 0,
        if (isWide) 34 else 29
      )
    }

    val emptyHeight = 140
    val bitmapHeight = (rowsHeight + 6).coerceAtLeast(emptyHeight)
    val bitmap = Bitmap.createBitmap(width, bitmapHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.WHITE)

    val leftX = pad
    val rightX = pad + panelW + panelGap
    var y = 2f

    if (maxRows == 0) {
      // Keep an intentionally blank, compact preview when there are no saved items.
      canvas.drawLine(pad, y, width - pad, y, ruleLine)
      y += 3f
    } else {
      fun drawRow(x: Float, row: NotePrintRow?, rowHeight: Float) {
        if (row != null) {
          val textY = y + 4f
          val qtyX = x + detailW
          drawRtl(
            canvas, row.qty, qtyX + 1f, textY, qtyW - 2f,
            qtyPaint, Layout.Alignment.ALIGN_CENTER
          )
          canvas.save()
          canvas.translate(x + 3f, textY)
          row.detail.draw(canvas)
          canvas.restore()
        }
        canvas.drawRect(x, y, x + panelW, y + rowHeight, ruleLine)
        canvas.drawLine(x + detailW, y, x + detailW, y + rowHeight, ruleLine)
      }

      for (index in 0 until maxRows) {
        val rowHeight = maxOf(
          rightRows.getOrNull(index)?.height ?: 0,
          leftRows.getOrNull(index)?.height ?: 0,
          if (isWide) 34 else 29
        ).toFloat()
        drawRow(rightX, rightRows.getOrNull(index), rowHeight)
        drawRow(leftX, leftRows.getOrNull(index), rowHeight)
        y += rowHeight
      }
      canvas.drawRect(rightX, 2f, rightX + panelW, y, outerLine)
      canvas.drawRect(leftX, 2f, leftX + panelW, y, outerLine)
    }

    return tightCropBitmap(bitmap, width, y + 2f)
  }

  private fun tightCropBitmap(bitmap: Bitmap, width: Int, contentEndY: Float): Bitmap {
    val finalH = (contentEndY + 10f).toInt().coerceIn(140, bitmap.height)
    return if (finalH < bitmap.height) {
      Bitmap.createBitmap(bitmap, 0, 0, width, finalH).also { bitmap.recycle() }
    } else {
      bitmap
    }
  }

  fun renderBitmapToBase64(bitmap: Bitmap): String {
    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    bitmap.recycle()
    return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
  }

  fun saveThermalBitmapToDownloads(
    context: Context,
    bitmap: Bitmap,
    fileNamePrefix: String
  ): SavedDownloadResult {
    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    bitmap.recycle()
    val safePrefix = fileNamePrefix.replace(Regex("[^A-Za-z0-9_-]"), "_").take(48).ifBlank { "receipt" }
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    return AppDownloadsFolderManager.saveBytes(
      context = context,
      subfolder = AppDownloadsFolderManager.SUB_IMAGES,
      fileName = "${safePrefix}_$stamp.png",
      mimeType = "image/png",
      bytes = out.toByteArray(),
      overwriteExisting = false
    )
  }

  fun printBitmap(bitmap: Bitmap): Pair<Boolean, String> = sendBitmapToPrinter(bitmap)

  fun renderInvoicePreviewBase64(
    context: Context,
    invoice: InvoiceEntity,
    items: List<InvoiceItemEntity>,
    partyBalanceAfter: Double?,
    paperWidthMm: Int
  ): String {
    val bitmap = buildInvoiceReceiptBitmap(context, invoice, items, partyBalanceAfter, paperWidthMm)
    return renderBitmapToBase64(bitmap)
  }

  fun saveThermalReceiptImageToDownloads(
    context: Context,
    invoice: InvoiceEntity,
    items: List<InvoiceItemEntity>,
    partyBalanceAfter: Double?,
    paperWidthMm: Int
  ): SavedDownloadResult {
    // Saved/shared PNG is rendered at 3x; Bluetooth printing still uses native 384/576 dots.
    val bitmap = buildInvoiceReceiptBitmap(context, invoice, items, partyBalanceAfter, paperWidthMm, renderScale = 3)
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
      val chunkSize = 2048
      var offset = 0
      while (offset < bytes.size) {
        val len = minOf(chunkSize, bytes.size - offset)
        out.write(bytes, offset, len)
        offset += len
      }
      out.write(byteArrayOf(0x1B, 0x64, 0x02))
      out.write(byteArrayOf(0x1D, 0x56, 0x42, 0x00))
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
    maxLines: Int = 20
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
    alignment: Layout.Alignment,
    maxLines: Int = 2
  ): Int {
    val layout = buildRtlLayout(text, paint, width.toInt(), alignment, maxLines)
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
    require(width == 384 || width == 576) {
      "عرض صورة الطباعة يجب أن يساوي دقة الطابعة الأصلية (384 أو 576 نقطة)"
    }
    val bytesWidth = (width + 7) / 8
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

    val command = ByteArrayOutputStream(8 + bytesWidth * height)
    command.write(0x1D)
    command.write(0x76)
    command.write(0x30)
    command.write(0x00)
    command.write(bytesWidth and 0xFF)
    command.write((bytesWidth shr 8) and 0xFF)
    command.write(height and 0xFF)
    command.write((height shr 8) and 0xFF)

    // Floyd–Steinberg error diffusion keeps Arabic letter edges smoother on a
    // monochrome thermal head while retaining the printer's native dot width.
    // Build packed raster bytes in a second pass using the same deterministic dither.
    val raster = ByteArray(bytesWidth * height)
    var currentError = FloatArray(width + 2)
    var nextError = FloatArray(width + 2)
    for (y in 0 until height) {
      for (x in 0 until width) {
        val pixel = pixels[y * width + x]
        val luminance = (((299 * ((pixel shr 16) and 0xFF) + 587 * ((pixel shr 8) and 0xFF) + 114 * (pixel and 0xFF)) / 1000f) + currentError[x + 1]).coerceIn(0f, 255f)
        val black = luminance < 150f
        val error = luminance - if (black) 0f else 255f
        if (black) raster[y * bytesWidth + x / 8] = (raster[y * bytesWidth + x / 8].toInt() or (1 shl (7 - x % 8))).toByte()
        currentError[x + 2] += error * (7f / 16f)
        nextError[x] += error * (3f / 16f)
        nextError[x + 1] += error * (5f / 16f)
        nextError[x + 2] += error * (1f / 16f)
      }
      currentError = nextError
      nextError = FloatArray(width + 2)
    }
    command.write(raster)
    return command.toByteArray()
  }
}
