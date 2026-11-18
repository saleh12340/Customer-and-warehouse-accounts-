package com.example.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.utils.BackupRestoreManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GroceryAccountingIntegrationTest {

  private lateinit var context: Context
  private lateinit var db: AppDatabase
  private lateinit var engine: GroceryAccountingEngine

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    engine = GroceryAccountingEngine(db)
  }

  @After
  fun tearDown() {
    db.close()
  }

  @Test
  fun invoiceCreateEditAndDeleteUpdatesStockAndLedgerAtomically() = runBlocking {
    val custId = engine.saveCustomer(0L, "أحمد العزي", "771234567", "صنعاء", 1000.0)
    val itemId = engine.saveInventoryItem(0L, "سكر 10ك", "مواد غذائية", 20, 4000.0, 4500.0, "كيس")

    // Create credit invoice: 2 x 4500 = 9000, paid 2000 => remaining 7000 => customer balance 1000 + 7000 = 8000
    val invoiceId = engine.saveInvoice(
      invoiceId = 0L,
      invoiceNumber = "INV-100",
      type = "CREDIT",
      partyId = custId,
      partyName = "أحمد العزي",
      rawItems = listOf(InvoiceItemEntity(0L, 0L, itemId, "سكر 10ك", 2, 4500.0, 9000.0)),
      paidAmount = 2000.0
    )

    assertEquals(18, db.inventoryDao().getItemById(itemId)!!.quantity)
    assertEquals(8000.0, db.customerDao().getCustomerById(custId)!!.balance, 0.01)

    // Edit same invoice: 3 x 4500 = 13500, paid 3500 => remaining 10000 => stock 20 - 3 = 17, balance 1000 + 10000 = 11000
    engine.saveInvoice(
      invoiceId = invoiceId,
      invoiceNumber = "INV-100",
      type = "CREDIT",
      partyId = custId,
      partyName = "أحمد العزي",
      rawItems = listOf(InvoiceItemEntity(0L, 0L, itemId, "سكر 10ك", 3, 4500.0, 13500.0)),
      paidAmount = 3500.0
    )

    assertEquals(17, db.inventoryDao().getItemById(itemId)!!.quantity)
    assertEquals(11000.0, db.customerDao().getCustomerById(custId)!!.balance, 0.01)

    // COGS profit check: sales 13500 - (3 * 4000 = 12000) = 1500
    val report = engine.buildReport()
    assertEquals(13500.0, report.periodSales, 0.01)
    assertEquals(12000.0, report.cogs, 0.01)
    assertEquals(1500.0, report.grossProfit, 0.01)

    // Delete invoice reverses stock back to 20 and balance back to 1000
    engine.deleteInvoice(invoiceId)
    assertEquals(20, db.inventoryDao().getItemById(itemId)!!.quantity)
    assertEquals(1000.0, db.customerDao().getCustomerById(custId)!!.balance, 0.01)
  }

  @Test
  fun customerAndSupplierTransactionsComputeRunningBalanceAndReverseOnEditDelete() = runBlocking {
    val custId = engine.saveCustomer(0L, "سليمان", "0777000111", "", 500.0)
    val tx1 = engine.addCustomerTransaction(custId, 1500.0, isDebit = true, description = "بضاعة آجل", dateMillis = 1000L)
    val tx2 = engine.addCustomerTransaction(custId, 800.0, isDebit = false, description = "دفعة نقدية", dateMillis = 2000L)

    var detail = engine.getCustomerDetail(custId)!!
    assertEquals(1200.0, detail.customer.balance, 0.01)
    // Latest first in detail.transactions: tx2 running=1200, tx1 running=2000
    assertEquals(1200.0, detail.transactions[0].runningBalance, 0.01)
    assertEquals(2000.0, detail.transactions[1].runningBalance, 0.01)

    engine.updateCustomerTransaction(tx2, 1000.0, isDebit = false, description = "دفعة معدلة")
    assertEquals(1000.0, db.customerDao().getCustomerById(custId)!!.balance, 0.01)

    engine.deleteCustomerTransaction(tx1)
    assertEquals(-500.0, db.customerDao().getCustomerById(custId)!!.balance, 0.01)

    val suppId = engine.saveSupplier(0L, "تاجر الجملة", "770000111", "شركة النور", 0.0)
    val stx1 = engine.addSupplierTransaction(suppId, 5000.0, isCredit = true, description = "بضاعة وردت", dateMillis = 1000L)
    engine.addSupplierTransaction(suppId, 2000.0, isCredit = false, description = "سداد حوالة", dateMillis = 2000L)
    assertEquals(3000.0, db.supplierDao().getSupplierById(suppId)!!.balance, 0.01)

    engine.deleteSupplierTransaction(stx1)
    assertEquals(-2000.0, db.supplierDao().getSupplierById(suppId)!!.balance, 0.01)
  }

  @Test
  fun preventsDuplicateNamesAndUnsafeDeletions() = runBlocking {
    val custId = engine.saveCustomer(0L, "مقبل حمدي", "771111111", "", 500.0)
    try {
      engine.saveCustomer(0L, " مقبل حمدي ", "", "", 0.0)
      fail("Expected duplicate customer name error")
    } catch (expected: IllegalStateException) {
      assertTrue(expected.message!!.contains("مسبقاً"))
    }

    try {
      engine.deleteCustomer(custId)
      fail("Expected non-zero balance delete block")
    } catch (expected: IllegalStateException) {
      assertTrue(expected.message!!.contains("رصيد"))
    }
  }

  @Test
  fun backupExportAndTamperedRestoreValidation() = runBlocking {
    // Use singleton AppDatabase because BackupRestoreManager resolves AppDatabase.getDatabase(context)
    val appDb = AppDatabase.getDatabase(context)
    val appEngine = GroceryAccountingEngine(appDb)
    appEngine.resetAllDatabase()
    val cid = appEngine.saveCustomer(0L, "رماح مصلح", "772223334", "ذمار", 250.0)
    assertTrue(cid > 0)

    val backupFile = File(context.cacheDir, "test_backup.json")
    val uri = Uri.fromFile(backupFile)
    val exportMsg = BackupRestoreManager.exportToUri(context, uri)
    assertTrue(exportMsg.contains("بنجاح"))

    appEngine.resetAllDatabase()
    assertEquals(0, appDb.customerDao().getCustomersSnapshot().size)

    val restoreMsg = BackupRestoreManager.restoreFromUri(context, uri)
    assertTrue(restoreMsg.contains("1 عميل"))
    assertEquals(1, appDb.customerDao().getCustomersSnapshot().size)
    assertEquals(250.0, appDb.customerDao().getCustomerById(cid)!!.balance, 0.01)

    // Tampered SHA-256 must fail cleanly
    val tampered = File(context.cacheDir, "tampered_backup.json")
    tampered.writeText(backupFile.readText().replace("رماح مصلح", "اسم مزور"))
    try {
      BackupRestoreManager.restoreFromUri(context, Uri.fromFile(tampered))
      fail("Tampered backup should be rejected")
    } catch (expected: IllegalArgumentException) {
      assertTrue(expected.message!!.contains("سلامة الملف"))
    }
    appEngine.resetAllDatabase()
  }
}
