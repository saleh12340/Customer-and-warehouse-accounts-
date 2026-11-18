package com.example.utils

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.GroceryAccountingEngine
import com.example.data.InvoiceEntity
import com.example.data.InvoiceItemEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppDownloadsFolderManagerTest {

  @Test
  fun savesListsCategorizesAndDeletesFilesUnderAppFolder() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()

    AppDownloadsFolderManager.ensureFolderInitialized(context)

    val pdfRes = AppDownloadsFolderManager.saveBytes(
      context = context,
      subfolder = AppDownloadsFolderManager.SUB_PDFS,
      fileName = "invoice_AZ-100.pdf",
      mimeType = "application/pdf",
      bytes = "%PDF-1.4\ntest".toByteArray(Charsets.US_ASCII)
    )
    assertNotNull(pdfRes.uri)
    assertTrue(pdfRes.displayPath.contains(AppDownloadsFolderManager.APP_FOLDER_NAME))

    val imgRes = AppDownloadsFolderManager.saveThermalReceiptImageToDownloadsForTest(context)
    assertNotNull(imgRes.uri)

    val reportRes = AppDownloadsFolderManager.saveBytes(
      context = context,
      subfolder = AppDownloadsFolderManager.SUB_REPORTS,
      fileName = "report_summary.txt",
      mimeType = "text/plain",
      bytes = "تقرير مالي".toByteArray(Charsets.UTF_8)
    )
    assertNotNull(reportRes.uri)

    val snapshot = AppDownloadsFolderManager.listFiles(context)
    assertEquals("Download/${AppDownloadsFolderManager.APP_FOLDER_NAME}", snapshot.folderDisplayPath)
    assertTrue(snapshot.files.any { it.displayName == "invoice_AZ-100.pdf" && it.category == "PDF" })
    assertTrue(snapshot.files.any { it.displayName == "thermal_AZ-100.png" && it.category == "IMAGE" })
    assertTrue(snapshot.files.any { it.displayName == "report_summary.txt" && it.category == "REPORT" })

    val targetReport = snapshot.files.first { it.displayName == "report_summary.txt" }
    assertTrue(AppDownloadsFolderManager.deleteFile(context, targetReport.uriString))
    val afterDelete = AppDownloadsFolderManager.listFiles(context)
    assertTrue(afterDelete.files.none { it.displayName == "report_summary.txt" })
  }

  @Test
  fun exportsBackupToAppDownloadsFolderAndRestoresDatabaseAtomically() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = AppDatabase.getDatabase(context)
    val engine = GroceryAccountingEngine(db)
    engine.resetAllDatabase()

    val custId = engine.saveCustomer(0L, "سليمان العزي", "771234567", "صنعاء", 1500.0)
    val itemId = engine.saveInventoryItem(0L, "أرز بسمتي", "مواد غذائية", 20, 4000.0, 5000.0, "كيس")
    engine.saveInvoice(
      invoiceId = 0L,
      invoiceNumber = "AZ-BKP-1",
      type = "CREDIT",
      partyId = custId,
      partyName = "سليمان العزي",
      rawItems = listOf(InvoiceItemEntity(invoiceId = 0L, itemId = itemId, itemName = "أرز بسمتي", quantity = 2, unitPrice = 5000.0, totalPrice = 10000.0)),
      paidAmount = 2000.0
    )

    val backupResult = BackupRestoreManager.exportToAppDownloadsFolder(context)
    assertNotNull(backupResult.uri)
    assertTrue(backupResult.fileName.endsWith(".json"))
    assertTrue(backupResult.displayPath.contains(AppDownloadsFolderManager.SUB_BACKUPS))

    val listed = AppDownloadsFolderManager.listFiles(context)
    val backupEntry = listed.files.firstOrNull { it.displayName == backupResult.fileName && it.category == "BACKUP" }
    assertNotNull(backupEntry)

    // Clear database, then restore directly from the listed backup URI
    engine.resetAllDatabase()
    assertEquals(0, db.customerDao().getCustomersSnapshot().size)
    assertEquals(0, db.invoiceDao().getInvoicesSnapshot().size)

    val restoreMsg = BackupRestoreManager.restoreFromUri(context, Uri.parse(backupEntry!!.uriString))
    assertTrue(restoreMsg.contains("1 عميل"))
    assertEquals(1, db.customerDao().getCustomersSnapshot().size)
    assertEquals(1, db.invoiceDao().getInvoicesSnapshot().size)
    assertEquals(9500.0, db.customerDao().getCustomerById(custId)!!.balance, 0.01)

    engine.resetAllDatabase()
  }
}

private fun AppDownloadsFolderManager.saveThermalReceiptImageToDownloadsForTest(context: Context): SavedDownloadResult {
  val inv = InvoiceEntity(
    id = 10L,
    invoiceNumber = "AZ-100",
    type = "CASH",
    partyId = 0L,
    partyName = "عميل نقدي",
    date = System.currentTimeMillis(),
    totalAmount = 5000.0,
    paidAmount = 5000.0,
    remainingAmount = 0.0,
    status = "PAID"
  )
  val items = listOf(
    InvoiceItemEntity(id = 1L, invoiceId = 10L, itemId = 1L, itemName = "سكر 5 كيلو", quantity = 2, unitPrice = 2500.0, totalPrice = 5000.0)
  )
  return BluetoothPrinterManager.saveThermalReceiptImageToDownloads(
    context = context,
    invoice = inv,
    items = items,
    partyBalanceAfter = null,
    paperWidthMm = 58
  )
}
