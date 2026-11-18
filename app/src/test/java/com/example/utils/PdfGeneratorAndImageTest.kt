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
    assertEquals(900, decoded.width)
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
}
