package com.example.utils

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.example.data.InvoiceEntity
import com.example.data.InvoiceItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PdfGeneratorAndImageTest {

  private fun sampleInvoice() = InvoiceEntity(
    id = 7L,
    invoiceNumber = "AZ-2026-007",
    type = "CREDIT",
    partyId = 3L,
    partyName = "فايز مثنى العزي",
    date = 1763312400000L,
    totalAmount = 22500.0,
    paidAmount = 5000.0,
    remainingAmount = 17500.0,
    status = "PARTIAL"
  )

  private fun sampleItems(count: Int = 3): List<InvoiceItemEntity> =
    (1..count).map { idx ->
      InvoiceItemEntity(
        id = idx.toLong(),
        invoiceId = 7L,
        itemId = idx.toLong(),
        itemName = "قطمة سكر 10 كيلو صنف رقم $idx",
        quantity = 2,
        unitPrice = 3750.0,
        totalPrice = 7500.0
      )
    }

  @Test
  fun invoiceTypesHaveUnambiguousSaleAndPurchaseLabels() {
    assertEquals("فاتورة بيع نقدي", InvoiceImageGenerator.invoiceTypeArabic("CASH"))
    assertEquals("فاتورة بيع آجل", InvoiceImageGenerator.invoiceTypeArabic("CREDIT"))
    assertEquals("فاتورة شراء", InvoiceImageGenerator.invoiceTypeArabic("PURCHASE"))
  }

  @Test
  fun plansSingleAndMultiPagePdfPaginationAndResolvesSavedFiles() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val shortMeasured = PdfGenerator.measureItemsForTest(sampleItems(3))
    val shortPages = PdfGenerator.planPages(shortMeasured)
    assertEquals(1, shortPages.size)
    assertEquals(3, shortPages.flatten().size)

    val longMeasured = PdfGenerator.measureItemsForTest(sampleItems(32))
    val longPages = PdfGenerator.planPages(longMeasured)
    assertTrue(longPages.size >= 2)
    assertEquals(32, longPages.flatten().size)

    // Verify saved-PDF lookup behavior under files/invoices/
    val pdfDir = java.io.File(context.filesDir, "invoices").apply { mkdirs() }
    val sampleFile = java.io.File(pdfDir, "invoice_AZ-2026-007.pdf").apply { writeText("%PDF-1.4\n") }
    val resolved = PdfGenerator.getSavedPdfFile(context, "AZ-2026-007")
    assertNotNull(resolved)
    assertEquals(sampleFile.absolutePath, resolved!!.absolutePath)

    // Attempt full native PdfDocument generation; on real Android devices this produces %PDF-,
    // while Robolectric JVM lacks native Skia PDF and returns null safely without crashing.
    val generated = PdfGenerator.generateInvoicePdf(
      context = context,
      invoice = sampleInvoice(),
      items = sampleItems(5),
      partyBalanceAfter = 27500.0
    )
    if (generated != null) {
      val header = generated.inputStream().use { stream ->
        ByteArray(5).also { stream.read(it) }.toString(Charsets.US_ASCII)
      }
      assertEquals("%PDF-", header)
    }
  }

  @Test
  fun generatesPngInvoiceImageAndThermal58And80Bitmaps() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val invoice = sampleInvoice()
    val items = sampleItems(4)

    val imgFile = InvoiceImageGenerator.generateInvoiceImage(context, invoice, items, 27500.0)
    assertNotNull(imgFile)
    assertTrue(imgFile!!.exists())
    val decoded = BitmapFactory.decodeFile(imgFile.absolutePath)
    assertNotNull(decoded)
    assertTrue(decoded.width == 384 || decoded.width == 576)
    decoded.recycle()

    val bmp58 = BluetoothPrinterManager.buildInvoiceReceiptBitmap(context, invoice, items, 27500.0, paperWidthMm = 58)
    assertEquals(384, bmp58.width)
    assertTrue(bmp58.height in 250..1200)
    val escPosBytes = BluetoothPrinterManager.convertBitmapToEscPos(bmp58)
    assertTrue(escPosBytes.size > 500)
    bmp58.recycle()

    val bmp80 = BluetoothPrinterManager.buildInvoiceReceiptBitmap(context, invoice, items, 27500.0, paperWidthMm = 80)
    assertEquals(576, bmp80.width)
    assertTrue(bmp80.height in 250..1400)
    bmp80.recycle()

    val previewB64 = BluetoothPrinterManager.renderInvoicePreviewBase64(context, invoice, items, 27500.0, paperWidthMm = 58)
    val rawPng = Base64.decode(previewB64, Base64.DEFAULT)
    assertTrue(rawPng.size > 100)
  }

  @Test
  fun loadsAmiriFontAndRendersCompactThermalPreview() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val regular = ArabicFontHelper.getRegular(context)
    val bold = ArabicFontHelper.getBold(context)
    assertNotNull(regular)
    assertNotNull(bold)

    val preview80 = BluetoothPrinterManager.renderInvoicePreviewBase64(
      context = context,
      invoice = sampleInvoice(),
      items = sampleItems(2),
      partyBalanceAfter = 27500.0,
      paperWidthMm = 80
    )
    val decoded = Base64.decode(preview80, Base64.DEFAULT)
    assertTrue(decoded.size > 200)
  }

  @Test
  fun scalesInvoicePaginationAndImageGenerationAcross1To100Items() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val counts = listOf(1, 5, 20, 50, 100)
    var previousImageHeight = 0
    for (count in counts) {
      val items = sampleItems(count)
      val measured = PdfGenerator.measureItemsForTest(items, context)
      val pages = PdfGenerator.planPages(measured)
      assertEquals(count, pages.flatten().size)
      if (count >= 50) {
        assertTrue("Expected multi-page pagination for $count items", pages.size >= 3)
      }

      val totalSum = items.sumOf { it.totalPrice }
      val invoice = sampleInvoice().copy(totalAmount = totalSum, paidAmount = 5000.0, remainingAmount = totalSum - 5000.0)
      val img = InvoiceImageGenerator.generateInvoiceImage(context, invoice, items, totalSum)
      assertNotNull(img)
      assertTrue(img!!.exists() && img.length() > 100)
      val bmp = BitmapFactory.decodeFile(img.absolutePath)
      assertNotNull(bmp)
      assertTrue(bmp.width == 384 || bmp.width == 576)
      assertTrue("Image height should grow monotonically with $count items", bmp.height > previousImageHeight)
      previousImageHeight = bmp.height
      bmp.recycle()
    }
  }

  @Test
  fun plansMultiPageStatementsAndInventoryPdfs() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val entries = (1..45).map { idx ->
      com.example.data.LedgerEntryWithBalance(
        transaction = com.example.data.TransactionEntity(
          id = idx.toLong(),
          partyType = "CUSTOMER",
          partyId = 3L,
          partyName = "فايز العزي",
          type = if (idx % 2 == 0) "CREDIT (له)" else "DEBIT (عليه)",
          amount = 1500.0 * idx,
          description = "حركة حساب تفصيلية رقم $idx بضاعة متنوعة",
          date = 1763312400000L + idx * 60000L
        ),
        runningBalance = 50000.0 + idx * 500.0
      )
    }

    val singleStmt = PdfGenerator.planStatementPages(entries.take(1), context)
    assertEquals(1, singleStmt.size)

    val multiStmt = PdfGenerator.planStatementPages(entries, context)
    assertTrue("Expected 45 ledger entries to span >= 2 pages", multiStmt.size >= 2)
    assertEquals(45, multiStmt.flatten().size)

    val invItems = (1..50).map { idx ->
      com.example.data.InventoryItemEntity(
        id = idx.toLong(),
        name = "صنف مخزني رقم $idx كرتون",
        category = "مواد غذائية",
        quantity = idx * 3,
        purchasePrice = 2000.0,
        sellingPrice = 2500.0,
        unit = "كرتون"
      )
    }
    assertEquals(1, PdfGenerator.planInventoryPages(invItems.take(1), context).size)
    val multiInv = PdfGenerator.planInventoryPages(invItems, context)
    assertTrue("Expected 50 inventory items to span >= 2 pages", multiInv.size >= 2)
    assertEquals(50, multiInv.flatten().size)

    // Ensure high-level PDF generators do not crash under Robolectric JVM
    PdfGenerator.generateStatementPdf(context, "CUSTOMER", "فايز العزي", "777437514", "صنعاء", 72500.0, entries)?.let { (f, _) ->
      assertTrue(f.exists())
    }
    PdfGenerator.generateInventoryPdf(context, invItems)?.let { (f, _) ->
      assertTrue(f.exists())
    }
  }

  @Test
  fun rendersThermal58And80BitmapsForTransactionStatementAndReport() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val txSnapshot = com.example.data.TransactionReceiptSnapshot(
      transaction = com.example.data.TransactionEntity(
        id = 12L,
        partyType = "CUSTOMER",
        partyId = 3L,
        partyName = "فايز مثنى العزي",
        type = "CREDIT (له)",
        amount = 10000.0,
        description = "سداد نقدي دفعة",
        date = 1763312400000L
      ),
      partyPhone = "777437514",
      currentBalance = 12500.0,
      previousBalance = 22500.0
    )

    val ledger = listOf(
      com.example.data.LedgerEntryWithBalance(txSnapshot.transaction, 12500.0)
    )

    val report = com.example.data.FinancialReportSnapshot(
      todaySales = 45000.0,
      todayPurchases = 20000.0,
      periodSales = 125000.0,
      periodPurchases = 60000.0,
      cashSales = 75000.0,
      creditSales = 50000.0,
      cogs = 90000.0,
      grossProfit = 35000.0,
      totalDebts = 150000.0,
      totalPayables = 40000.0,
      inventoryCostValue = 320000.0,
      inventoryRetailValue = 410000.0,
      lowStockCount = 3,
      invoiceCount = 8,
      topItems = listOf(com.example.data.TopSoldItemReport("سكر 10ك", 20, 90000.0)),
      topDebtors = listOf(com.example.data.CustomerEntity(1L, "أحمد العزي", "771234567", "صنعاء", 56440.0)),
      topPurchases = emptyList()
    )

    for ((widthMm, expectedPx) in listOf(58 to 384, 80 to 576)) {
      val txBmp = BluetoothPrinterManager.buildTransactionReceiptBitmap(context, txSnapshot, widthMm)
      assertEquals(expectedPx, txBmp.width)
      assertTrue(txBmp.height in 140..900)
      assertTrue(BluetoothPrinterManager.convertBitmapToEscPos(txBmp).size > 200)
      assertTrue(BluetoothPrinterManager.renderBitmapToBase64(txBmp).length > 100)

      val stmtBmp = BluetoothPrinterManager.buildStatementReceiptBitmap(context, "CUSTOMER", "فايز العزي", "777437514", 12500.0, ledger, widthMm)
      assertEquals(expectedPx, stmtBmp.width)
      assertTrue(stmtBmp.height in 140..1200)
      assertTrue(BluetoothPrinterManager.renderBitmapToBase64(stmtBmp).length > 100)

      val repBmp = BluetoothPrinterManager.buildReportReceiptBitmap(context, report, "هذا الشهر", widthMm)
      assertEquals(expectedPx, repBmp.width)
      assertTrue(repBmp.height in 140..1400)
      assertTrue(BluetoothPrinterManager.renderBitmapToBase64(repBmp).length > 100)

      val sampleNote = com.example.data.NoteEntity(
        id = 7L,
        createdAt = 1763312400000L,
        updatedAt = 1763312400000L,
        rightRowsJson = """[{"qty":"12","detail":"سكر 10 كيلو"},{"qty":"4","detail":"زيت دوار الشمس"}]""",
        leftRowsJson = """[{"qty":"3","detail":"دقيق أبيض"}]"""
      )
      val noteBmp = BluetoothPrinterManager.buildNoteReceiptBitmap(context, sampleNote, widthMm)
      assertEquals(expectedPx, noteBmp.width)
      assertTrue(noteBmp.height in 140..1200)
      assertTrue(BluetoothPrinterManager.convertBitmapToEscPos(noteBmp).size > 200)
      assertTrue(BluetoothPrinterManager.renderBitmapToBase64(noteBmp).length > 100)
    }
  }

  @Test
  fun normalizesYemeniAndArabicIndicPhoneNumbersForWhatsApp() {
    assertEquals("+967771234567", WhatsAppHelper.normalizeYemeniPhone("771234567"))
    assertEquals("+967771234567", WhatsAppHelper.normalizeYemeniPhone("0771234567"))
    assertEquals("+967771234567", WhatsAppHelper.normalizeYemeniPhone("+967771234567"))
    assertEquals("+967771234567", WhatsAppHelper.normalizeYemeniPhone("00967771234567"))
    assertEquals("+967771234567", WhatsAppHelper.normalizeYemeniPhone("٧٧١٢٣٤٥٦٧"))
    assertEquals("+967771234567", WhatsAppHelper.normalizeYemeniPhone("٠٧٧١٢٣٤٥٦٧"))
    assertEquals("+967771234567", WhatsAppHelper.normalizeYemeniPhone("  +967 771-234-567 "))
    assertEquals("", WhatsAppHelper.normalizeYemeniPhone("   "))
  }
}
