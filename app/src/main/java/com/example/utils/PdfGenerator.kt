package com.example.utils

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.example.data.FinancialReportSnapshot
import com.example.data.InventoryItemEntity
import com.example.data.InvoiceEntity
import com.example.data.InvoiceItemEntity
import com.example.data.LedgerEntryWithBalance
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

      val invoiceAccent = if (invoice.type.equals("PURCHASE", true)) Color.rgb(0, 112, 105) else Color.rgb(15, 76, 156)
      val titlePaint = textPaint(context, 19f, invoiceAccent, true)
      val subPaint = textPaint(context, 10f, Color.rgb(90, 98, 106))
      val bodyPaint = textPaint(context, 11f, Color.BLACK)
      val smallPaint = textPaint(context, 9f, Color.DKGRAY)
      val headerPaint = textPaint(context, 10f, Color.WHITE, true)
      val totalPaint = textPaint(context, 11.5f, Color.BLACK, true)
      val accentTotalPaint = textPaint(context, 12.5f, invoiceAccent, true)
      val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(218, 222, 226)
        strokeWidth = 0.8f
      }
      val headerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = invoiceAccent }
      val summaryFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(241, 245, 252) }

      val totalW = 110f
      val priceW = 95f
      val qtyW = 65f
      val nameW = CONTENT_WIDTH - totalW - priceW - qtyW

      val colTotalX = MARGIN
      val colPriceX = colTotalX + totalW
      val colQtyX = colPriceX + priceW
      val colNameX = colQtyX + qtyW

      val measured = measureItemsForTest(items, context)
      val pagesItems = planPages(measured)
      val lastPageItemsH = pagesItems.last().sumOf { it.rowH.toDouble() }.toFloat()
      val lastPageStartY = if (pagesItems.size == 1) 202f + 34f else 108f + 34f
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
          y += drawRtl(canvas, "$partyLabel: ${invoice.partyName}", MARGIN, y, CONTENT_WIDTH, totalPaint, Layout.Alignment.ALIGN_NORMAL) + 4f
          val statusLabel = when (invoice.status.uppercase(Locale.US)) {
            "PAID" -> "مدفوعة بالكامل"
            "PARTIAL" -> "مدفوعة جزئياً"
            else -> if (invoice.type.equals("PURCHASE", true)) "متبقي للمورد" else "متبقي على العميل"
          }
          y += drawRtl(canvas, "حالة الفاتورة: $statusLabel", MARGIN, y, CONTENT_WIDTH, bodyPaint, Layout.Alignment.ALIGN_NORMAL) + 8f
        } else {
          y += drawRtl(canvas, "${settings.storeName} - تابع فاتورة ${invoice.invoiceNumber}", MARGIN, y, CONTENT_WIDTH, totalPaint, Layout.Alignment.ALIGN_NORMAL) + 12f
        }

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
      runCatching {
        AppDownloadsFolderManager.saveFile(
          context = context,
          subfolder = AppDownloadsFolderManager.SUB_PDFS,
          sourceFile = output,
          displayName = output.name,
          mimeType = "application/pdf",
          overwriteExisting = true
        )
      }
      output
    } catch (error: Exception) {
      error.printStackTrace()
      null
    } finally {
      try { document.close() } catch (_: Exception) {}
    }
  }

  internal data class MeasuredItem(val item: InvoiceItemEntity, val nameLayout: StaticLayout, val rowH: Float)

  internal fun planPages(measured: List<MeasuredItem>): List<List<MeasuredItem>> {
    val pagesItems = mutableListOf<List<MeasuredItem>>()
    var currentList = mutableListOf<MeasuredItem>()
    var currentPageY = 202f + 34f
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

  internal fun measureItemsForTest(items: List<InvoiceItemEntity>, context: Context? = null): List<MeasuredItem> {
    val bodyPaint = textPaint(context, 11f, Color.BLACK)
    val nameW = CONTENT_WIDTH - 110f - 95f - 65f
    return items.map { item ->
      val layout = buildRtlLayout(item.itemName + item.unitLabel.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty(), bodyPaint, nameW.toInt() - 12, Layout.Alignment.ALIGN_NORMAL, maxLines = 20)
      val rowH = maxOf(28f, layout.height + 14f)
      MeasuredItem(item, layout, rowH)
    }
  }

  internal data class MeasuredStatementRow(
    val entry: LedgerEntryWithBalance,
    val descLayout: StaticLayout,
    val rowH: Float
  )

  internal fun planStatementPages(
    entries: List<LedgerEntryWithBalance>,
    context: Context? = null
  ): List<List<MeasuredStatementRow>> {
    val bodyPaint = textPaint(context, 10.5f, Color.BLACK)
    val descW = CONTENT_WIDTH - 92f - 86f - 68f - 84f
    val measured = entries.map { entry ->
      val text = entry.transaction.description.ifBlank { "-" }
      val layout = buildRtlLayout(text, bodyPaint, (descW - 10f).toInt(), Layout.Alignment.ALIGN_NORMAL, maxLines = 20)
      val rowH = maxOf(26f, layout.height + 12f)
      MeasuredStatementRow(entry, layout, rowH)
    }
    val pages = mutableListOf<List<MeasuredStatementRow>>()
    var current = mutableListOf<MeasuredStatementRow>()
    var curY = 178f + 32f
    for (row in measured) {
      if (current.isNotEmpty() && curY + row.rowH > BOTTOM_LIMIT - 60f) {
        pages.add(current)
        current = mutableListOf()
        curY = 92f + 32f
      }
      current.add(row)
      curY += row.rowH
    }
    if (current.isNotEmpty() || pages.isEmpty()) pages.add(current)
    return pages
  }

  /**
   * Generates a multi-page A4 account statement PDF for a customer or supplier.
   */
  fun generateStatementPdf(
    context: Context,
    partyType: String,
    partyName: String,
    partyPhone: String,
    extraSubtitle: String,
    currentBalance: Double,
    entries: List<LedgerEntryWithBalance>
  ): Pair<File, SavedDownloadResult>? {
    val document = PdfDocument()
    return try {
      val settings = StorePreferences.getSettings(context)
      val currency = settings.currency
      val isCustomer = partyType.equals("CUSTOMER", true)
      val reportDir = File(context.filesDir, "reports").apply { mkdirs() }
      val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
      val output = File(reportDir, "statement_${partyType.lowercase(Locale.US)}_$stamp.pdf")
      val nowStr = SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale.US).format(Date())

      val titlePaint = textPaint(context, 18f, Color.rgb(15, 76, 156), true)
      val subPaint = textPaint(context, 10f, Color.rgb(90, 98, 106))
      val bodyPaint = textPaint(context, 10.5f, Color.BLACK)
      val boldPaint = textPaint(context, 11f, Color.BLACK, true)
      val headerPaint = textPaint(context, 10f, Color.WHITE, true)
      val smallPaint = textPaint(context, 9f, Color.DKGRAY)
      val headerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(15, 76, 156) }
      val boxFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(241, 245, 252) }
      val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(218, 222, 226); strokeWidth = 0.8f }

      // RTL columns: البيان | التاريخ | النوع | المبلغ | الرصيد
      val balW = 84f
      val amtW = 86f
      val typeW = 68f
      val dateW = 92f
      val descW = CONTENT_WIDTH - balW - amtW - typeW - dateW

      val colBalX = MARGIN
      val colAmtX = colBalX + balW
      val colTypeX = colAmtX + amtW
      val colDateX = colTypeX + typeW
      val colDescX = colDateX + dateW

      val pages = planStatementPages(entries, context)
      val totalPages = pages.size
      val rowDf = SimpleDateFormat("yy/MM/dd HH:mm", Locale.US)

      pages.forEachIndexed { idx, rows ->
        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, idx + 1).create()
        val page = document.startPage(pageInfo)
        val canvas = page.canvas
        var y = MARGIN

        if (idx == 0) {
          val kindLabel = if (isCustomer) "كشف حساب عميل" else "كشف حساب مورد"
          y += drawRtl(canvas, "${settings.storeName} - $kindLabel", MARGIN, y, CONTENT_WIDTH, titlePaint, Layout.Alignment.ALIGN_NORMAL) + 4f
          y += drawRtl(canvas, "${if (isCustomer) "العميل" else "المورد"}: $partyName   ${partyPhone.takeIf { it.isNotBlank() }?.let { "| جوال: $it" } ?: ""}", MARGIN, y, CONTENT_WIDTH, boldPaint, Layout.Alignment.ALIGN_NORMAL) + 4f
          if (extraSubtitle.isNotBlank()) {
            y += drawRtl(canvas, extraSubtitle, MARGIN, y, CONTENT_WIDTH, subPaint, Layout.Alignment.ALIGN_NORMAL) + 4f
          }
          y += drawRtl(canvas, "تاريخ الكشف: $nowStr   |   عدد الحركات: ${entries.size}", MARGIN, y, CONTENT_WIDTH, subPaint, Layout.Alignment.ALIGN_NORMAL) + 10f
        } else {
          y += drawRtl(canvas, "${settings.storeName} - تابع كشف حساب: $partyName", MARGIN, y, CONTENT_WIDTH, boldPaint, Layout.Alignment.ALIGN_NORMAL) + 10f
        }

        val tTop = y
        canvas.drawRoundRect(MARGIN, tTop, PAGE_WIDTH - MARGIN, tTop + 26f, 4f, 4f, headerFill)
        drawRtl(canvas, "البيان", colDescX + 4f, tTop + 6f, descW - 8f, headerPaint, Layout.Alignment.ALIGN_NORMAL)
        drawRtl(canvas, "التاريخ", colDateX + 3f, tTop + 6f, dateW - 6f, headerPaint, Layout.Alignment.ALIGN_CENTER)
        drawRtl(canvas, "النوع", colTypeX + 3f, tTop + 6f, typeW - 6f, headerPaint, Layout.Alignment.ALIGN_CENTER)
        drawRtl(canvas, "المبلغ", colAmtX + 3f, tTop + 6f, amtW - 6f, headerPaint, Layout.Alignment.ALIGN_CENTER)
        drawRtl(canvas, "الرصيد", colBalX + 4f, tTop + 6f, balW - 8f, headerPaint, Layout.Alignment.ALIGN_OPPOSITE)
        y = tTop + 32f

        if (rows.isEmpty()) {
          y += drawRtl(canvas, "لا توجد حركات مسجلة في كشف الحساب", MARGIN, y + 8f, CONTENT_WIDTH, subPaint, Layout.Alignment.ALIGN_CENTER) + 14f
        } else {
          rows.forEach { (entry, descLayout, rowH) ->
            val tx = entry.transaction
            canvas.save()
            canvas.translate(colDescX + 4f, y)
            descLayout.draw(canvas)
            canvas.restore()

            val typeShort = if (isCustomer) {
              if (tx.type.startsWith("DEBIT")) "عليه" else "له"
            } else {
              if (tx.type.startsWith("CREDIT")) "له" else "عليه"
            }
            drawRtl(canvas, rowDf.format(Date(tx.date)), colDateX + 3f, y, dateW - 6f, smallPaint, Layout.Alignment.ALIGN_CENTER)
            drawRtl(canvas, typeShort, colTypeX + 3f, y, typeW - 6f, boldPaint, Layout.Alignment.ALIGN_CENTER)
            drawRtl(canvas, formatMoney(tx.amount), colAmtX + 3f, y, amtW - 6f, boldPaint, Layout.Alignment.ALIGN_CENTER)
            drawRtl(canvas, formatMoney(entry.runningBalance), colBalX + 4f, y, balW - 8f, bodyPaint, Layout.Alignment.ALIGN_OPPOSITE)

            y += rowH - 5f
            canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, linePaint)
            y += 5f
          }
        }

        if (idx == pages.lastIndex) {
          y += 10f
          val balText = when {
            currentBalance > 0.01 -> (if (isCustomer) "الرصيد الحالي عليه: " else "المستحق الحالي له: ") + "${formatMoney(currentBalance)} $currency"
            currentBalance < -0.01 -> (if (isCustomer) "الرصيد الحالي له: " else "رصيد لنا عند المورد: ") + "${formatMoney(-currentBalance)} $currency"
            else -> "الحساب مسدد بالكامل (0 $currency)"
          }
          canvas.drawRoundRect(MARGIN, y, PAGE_WIDTH - MARGIN, y + 46f, 6f, 6f, boxFill)
          drawRtl(canvas, balText, MARGIN + 12f, y + 14f, CONTENT_WIDTH - 24f, titlePaint, Layout.Alignment.ALIGN_CENTER)
        }

        drawRtl(canvas, "صفحة ${idx + 1} / $totalPages", MARGIN, PAGE_HEIGHT - 28f, CONTENT_WIDTH, smallPaint, Layout.Alignment.ALIGN_CENTER)
        document.finishPage(page)
      }

      FileOutputStream(output).use { document.writeTo(it) }
      val saved = AppDownloadsFolderManager.saveFile(
        context = context,
        subfolder = AppDownloadsFolderManager.SUB_REPORTS,
        sourceFile = output,
        displayName = output.name,
        mimeType = "application/pdf",
        overwriteExisting = false
      ) ?: SavedDownloadResult(getUriForFile(context, output), output.absolutePath, output.name, false)
      output to saved
    } catch (e: Exception) {
      e.printStackTrace()
      null
    } finally {
      try { document.close() } catch (_: Exception) {}
    }
  }

  internal data class MeasuredInventoryRow(
    val item: InventoryItemEntity,
    val nameLayout: StaticLayout,
    val rowH: Float
  )

  internal fun planInventoryPages(
    items: List<InventoryItemEntity>,
    context: Context? = null
  ): List<List<MeasuredInventoryRow>> {
    val bodyPaint = textPaint(context, 10.5f, Color.BLACK)
    val nameW = CONTENT_WIDTH - 100f - 85f - 85f - 62f
    val measured = items.map { item ->
      val label = "${item.name}${if (item.category.isNotBlank()) " (${item.category})" else ""}"
      val layout = buildRtlLayout(label, bodyPaint, (nameW - 10f).toInt(), Layout.Alignment.ALIGN_NORMAL, maxLines = 20)
      val rowH = maxOf(26f, layout.height + 12f)
      MeasuredInventoryRow(item, layout, rowH)
    }
    val pages = mutableListOf<List<MeasuredInventoryRow>>()
    var current = mutableListOf<MeasuredInventoryRow>()
    var curY = 148f + 32f
    for (row in measured) {
      if (current.isNotEmpty() && curY + row.rowH > BOTTOM_LIMIT - 60f) {
        pages.add(current)
        current = mutableListOf()
        curY = 92f + 32f
      }
      current.add(row)
      curY += row.rowH
    }
    if (current.isNotEmpty() || pages.isEmpty()) pages.add(current)
    return pages
  }

  /**
   * Generates a multi-page A4 inventory valuation & stock status PDF.
   */
  fun generateInventoryPdf(
    context: Context,
    items: List<InventoryItemEntity>
  ): Pair<File, SavedDownloadResult>? {
    val document = PdfDocument()
    return try {
      val settings = StorePreferences.getSettings(context)
      val currency = settings.currency
      val reportDir = File(context.filesDir, "reports").apply { mkdirs() }
      val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
      val output = File(reportDir, "inventory_$stamp.pdf")
      val nowStr = SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale.US).format(Date())

      val titlePaint = textPaint(context, 18f, Color.rgb(15, 76, 156), true)
      val subPaint = textPaint(context, 10f, Color.rgb(90, 98, 106))
      val bodyPaint = textPaint(context, 10.5f, Color.BLACK)
      val boldPaint = textPaint(context, 11f, Color.BLACK, true)
      val headerPaint = textPaint(context, 10f, Color.WHITE, true)
      val smallPaint = textPaint(context, 9f, Color.DKGRAY)
      val headerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(15, 76, 156) }
      val boxFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(241, 245, 252) }
      val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(218, 222, 226); strokeWidth = 0.8f }

      val valW = 100f
      val sellW = 85f
      val buyW = 85f
      val qtyW = 62f
      val nameW = CONTENT_WIDTH - valW - sellW - buyW - qtyW

      val colValX = MARGIN
      val colSellX = colValX + valW
      val colBuyX = colSellX + sellW
      val colQtyX = colBuyX + buyW
      val colNameX = colQtyX + qtyW

      val pages = planInventoryPages(items, context)
      val totalPages = pages.size

      pages.forEachIndexed { idx, rows ->
        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, idx + 1).create()
        val page = document.startPage(pageInfo)
        val canvas = page.canvas
        var y = MARGIN

        if (idx == 0) {
          y += drawRtl(canvas, "${settings.storeName} - جرد المخزون والتسعير", MARGIN, y, CONTENT_WIDTH, titlePaint, Layout.Alignment.ALIGN_NORMAL) + 4f
          y += drawRtl(canvas, "تاريخ الجرد: $nowStr   |   عدد الأصناف: ${items.size}", MARGIN, y, CONTENT_WIDTH, subPaint, Layout.Alignment.ALIGN_NORMAL) + 10f
        } else {
          y += drawRtl(canvas, "${settings.storeName} - تابع جرد المخزون", MARGIN, y, CONTENT_WIDTH, boldPaint, Layout.Alignment.ALIGN_NORMAL) + 10f
        }

        val tTop = y
        canvas.drawRoundRect(MARGIN, tTop, PAGE_WIDTH - MARGIN, tTop + 26f, 4f, 4f, headerFill)
        drawRtl(canvas, "الصنف والفئة", colNameX + 4f, tTop + 6f, nameW - 8f, headerPaint, Layout.Alignment.ALIGN_NORMAL)
        drawRtl(canvas, "الكمية", colQtyX + 3f, tTop + 6f, qtyW - 6f, headerPaint, Layout.Alignment.ALIGN_CENTER)
        drawRtl(canvas, "الشراء", colBuyX + 3f, tTop + 6f, buyW - 6f, headerPaint, Layout.Alignment.ALIGN_CENTER)
        drawRtl(canvas, "البيع", colSellX + 3f, tTop + 6f, sellW - 6f, headerPaint, Layout.Alignment.ALIGN_CENTER)
        drawRtl(canvas, "قيمة التكلفة", colValX + 4f, tTop + 6f, valW - 8f, headerPaint, Layout.Alignment.ALIGN_OPPOSITE)
        y = tTop + 32f

        if (rows.isEmpty()) {
          y += drawRtl(canvas, "لا توجد أصناف في المخزن", MARGIN, y + 8f, CONTENT_WIDTH, subPaint, Layout.Alignment.ALIGN_CENTER) + 14f
        } else {
          rows.forEach { (item, nameLayout, rowH) ->
            canvas.save()
            canvas.translate(colNameX + 4f, y)
            nameLayout.draw(canvas)
            canvas.restore()

            val qtyText = if (item.quantity <= 0) "0 (نافذ)" else item.quantity.toString()
            val costVal = item.quantity.toDouble() * item.purchasePrice
            drawRtl(canvas, qtyText, colQtyX + 3f, y, qtyW - 6f, boldPaint, Layout.Alignment.ALIGN_CENTER)
            drawRtl(canvas, formatMoney(item.purchasePrice), colBuyX + 3f, y, buyW - 6f, bodyPaint, Layout.Alignment.ALIGN_CENTER)
            drawRtl(canvas, formatMoney(item.sellingPrice), colSellX + 3f, y, sellW - 6f, bodyPaint, Layout.Alignment.ALIGN_CENTER)
            drawRtl(canvas, formatMoney(costVal), colValX + 4f, y, valW - 8f, boldPaint, Layout.Alignment.ALIGN_OPPOSITE)

            y += rowH - 5f
            canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, linePaint)
            y += 5f
          }
        }

        if (idx == pages.lastIndex) {
          val totalQty = items.sumOf { it.quantity }
          val totalCost = items.sumOf { it.quantity.toDouble() * it.purchasePrice }
          val totalRetail = items.sumOf { it.quantity.toDouble() * it.sellingPrice }
          val lowCount = items.count { it.quantity <= 10 }
          y += 10f
          canvas.drawRoundRect(MARGIN, y, PAGE_WIDTH - MARGIN, y + 66f, 6f, 6f, boxFill)
          var sy = y + 10f
          sy += drawRtl(canvas, "إجمالي الكمية: $totalQty وحدة   |   أصناف منخفضة/نافذة: $lowCount", MARGIN + 12f, sy, CONTENT_WIDTH - 24f, bodyPaint, Layout.Alignment.ALIGN_NORMAL) + 6f
          drawRtl(canvas, "إجمالي قيمة المخزون بالتكلفة: ${formatMoney(totalCost)} $currency   |   بسعر البيع: ${formatMoney(totalRetail)} $currency", MARGIN + 12f, sy, CONTENT_WIDTH - 24f, titlePaint, Layout.Alignment.ALIGN_NORMAL)
        }

        drawRtl(canvas, "صفحة ${idx + 1} / $totalPages", MARGIN, PAGE_HEIGHT - 28f, CONTENT_WIDTH, smallPaint, Layout.Alignment.ALIGN_CENTER)
        document.finishPage(page)
      }

      FileOutputStream(output).use { document.writeTo(it) }
      val saved = AppDownloadsFolderManager.saveFile(
        context = context,
        subfolder = AppDownloadsFolderManager.SUB_REPORTS,
        sourceFile = output,
        displayName = output.name,
        mimeType = "application/pdf",
        overwriteExisting = false
      ) ?: SavedDownloadResult(getUriForFile(context, output), output.absolutePath, output.name, false)
      output to saved
    } catch (e: Exception) {
      e.printStackTrace()
      null
    } finally {
      try { document.close() } catch (_: Exception) {}
    }
  }

  fun generateFinancialReportPdf(
    context: Context,
    report: FinancialReportSnapshot,
    periodLabel: String
  ): Pair<File, SavedDownloadResult>? {
    val document = PdfDocument()
    return try {
      val settings = StorePreferences.getSettings(context)
      val currency = settings.currency
      val reportDir = File(context.filesDir, "reports").apply { mkdirs() }
      val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
      val output = File(reportDir, "report_${stamp}.pdf")
      val dateText = SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale.US).format(Date())

      val titlePaint = textPaint(context, 19f, Color.rgb(15, 76, 156), true)
      val subPaint = textPaint(context, 10.5f, Color.rgb(90, 98, 106))
      val sectionPaint = textPaint(context, 13f, Color.rgb(15, 76, 156), true)
      val bodyPaint = textPaint(context, 11.5f, Color.BLACK)
      val boldPaint = textPaint(context, 11.5f, Color.BLACK, true)
      val boxFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(241, 245, 252) }
      val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(210, 218, 228)
        strokeWidth = 0.8f
      }

      val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()
      val page = document.startPage(pageInfo)
      val canvas = page.canvas
      var y = MARGIN

      y += drawRtl(canvas, "${settings.storeName} - التقرير المالي", MARGIN, y, CONTENT_WIDTH, titlePaint, Layout.Alignment.ALIGN_NORMAL) + 4f
      y += drawRtl(canvas, "الفترة: $periodLabel   |   تاريخ الإصدار: $dateText", MARGIN, y, CONTENT_WIDTH, subPaint, Layout.Alignment.ALIGN_NORMAL) + 12f

      canvas.drawRoundRect(MARGIN, y, PAGE_WIDTH - MARGIN, y + 184f, 8f, 8f, boxFill)
      var sy = y + 12f
      val lines = listOf(
        "مبيعات الفترة (${report.invoiceCount} فاتورة): ${formatMoney(report.periodSales)} $currency",
        "صافي الربح الإجمالي (بعد COGS): ${formatMoney(report.grossProfit)} $currency",
        "تكلفة البضاعة المباعة: ${formatMoney(report.cogs)} $currency",
        "إجمالي المشتريات: ${formatMoney(report.periodPurchases)} $currency",
        "التحصيل النقدي: ${formatMoney(report.cashSales)} $currency   |   مبيعات آجلة: ${formatMoney(report.creditSales)} $currency",
        "ديون العملاء: ${formatMoney(report.totalDebts)} $currency   |   مستحقات الموردين: ${formatMoney(report.totalPayables)} $currency",
        "قيمة المخزون بالتكلفة: ${formatMoney(report.inventoryCostValue)} $currency   |   بسعر البيع: ${formatMoney(report.inventoryRetailValue)} $currency"
      )
      lines.forEach { text ->
        sy += drawRtl(canvas, text, MARGIN + 12f, sy, CONTENT_WIDTH - 24f, boldPaint, Layout.Alignment.ALIGN_NORMAL) + 7f
      }
      y += 198f

      if (report.topItems.isNotEmpty()) {
        y += drawRtl(canvas, "الأصناف الأكثر مبيعاً", MARGIN, y, CONTENT_WIDTH, sectionPaint, Layout.Alignment.ALIGN_NORMAL) + 6f
        report.topItems.take(8).forEach { item ->
          if (y > BOTTOM_LIMIT - 80f) return@forEach
          y += drawRtl(
            canvas,
            "${item.itemName}  —  الكمية: ${item.totalQuantity}  —  الإيراد: ${formatMoney(item.totalRevenue)} $currency",
            MARGIN,
            y,
            CONTENT_WIDTH,
            bodyPaint,
            Layout.Alignment.ALIGN_NORMAL
          ) + 4f
          canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, linePaint)
          y += 5f
        }
        y += 8f
      }

      if (report.topDebtors.isNotEmpty() && y < BOTTOM_LIMIT - 40f) {
        y += drawRtl(canvas, "أعلى العملاء مديونية", MARGIN, y, CONTENT_WIDTH, sectionPaint, Layout.Alignment.ALIGN_NORMAL) + 6f
        report.topDebtors.take(6).forEach { c ->
          if (y > BOTTOM_LIMIT) return@forEach
          y += drawRtl(
            canvas,
            "${c.name}  —  ${formatMoney(c.balance)} $currency",
            MARGIN,
            y,
            CONTENT_WIDTH,
            bodyPaint,
            Layout.Alignment.ALIGN_NORMAL
          ) + 4f
        }
      }

      document.finishPage(page)
      FileOutputStream(output).use { stream -> document.writeTo(stream) }
      val saved = AppDownloadsFolderManager.saveFile(
        context = context,
        subfolder = AppDownloadsFolderManager.SUB_REPORTS,
        sourceFile = output,
        displayName = output.name,
        mimeType = "application/pdf",
        overwriteExisting = false
      ) ?: SavedDownloadResult(getUriForFile(context, output), output.absolutePath, output.name, false)
      output to saved
    } catch (error: Exception) {
      error.printStackTrace()
      null
    } finally {
      try { document.close() } catch (_: Exception) {}
    }
  }

  fun getSavedPdfFile(context: Context, invoiceNumber: String): File? {
    val safeNumber = invoiceNumber.replace(Regex("[^A-Za-z0-9_-]"), "_").take(48)
    val file = File(File(context.filesDir, "invoices"), "invoice_${safeNumber}.pdf")
    return file.takeIf { it.exists() && it.length() > 0 }
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
