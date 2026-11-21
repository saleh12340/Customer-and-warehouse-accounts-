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
  fun purchaseInvoiceAutomaticallyCreatesAndLinksNewInventoryItemsWithoutDuplicateExport() = runBlocking {
    val supplierId = engine.saveSupplier(0L, "مورد الجملة", "771234567", "", 0.0)
    val existingItemId = engine.saveInventoryItem(0L, "سكر", "مواد غذائية", 5, 100.0, 130.0, "كيس")

    val invoiceId = engine.saveInvoice(
      invoiceId = 0L,
      invoiceNumber = "PUR-BULK-001",
      type = "PURCHASE",
      partyId = supplierId,
      partyName = "مورد الجملة",
      rawItems = listOf(
        InvoiceItemEntity(0L, 0L, existingItemId, "سكر", 2, 110.0, 220.0),
        InvoiceItemEntity(0L, 0L, 0L, "أرز", 4, 200.0, 800.0),
        InvoiceItemEntity(0L, 0L, 0L, "سكر", 3, 115.0, 345.0),
        InvoiceItemEntity(0L, 0L, 0L, "دقيق", 3, 150.0, 450.0, 3, "كيس", 1),
        InvoiceItemEntity(0L, 0L, 0L, "مياه", 2, 7200.0, 14400.0, 288, "كرتون", 144)
      ),
      paidAmount = 1365.0
    )

    // Existing and free-typed lines are applied immediately and linked to stock.
    assertEquals(10, db.inventoryDao().getItemById(existingItemId)!!.quantity)
    val rice = db.inventoryDao().getItemByName("أرز")!!
    assertEquals(4, rice.quantity)
    assertEquals(200.0, rice.purchasePrice, 0.01)
    val flour = db.inventoryDao().getItemByName("دقيق")!!
    assertEquals(3, flour.quantity)
    assertEquals("كيس", org.json.JSONObject(flour.unit).getString("base"))
    val water = db.inventoryDao().getItemByName("مياه")!!
    assertEquals(288, water.quantity)
    assertEquals(50.0, water.purchasePrice, 0.01)
    val waterUnitConfig = org.json.JSONObject(water.unit)
    assertEquals("كرتون", waterUnitConfig.getString("packName"))
    assertEquals(144, waterUnitConfig.getInt("packSize"))

    val savedLines = db.invoiceDao().getInvoiceWithItems(invoiceId)!!.items
    assertTrue(savedLines.all { it.itemId > 0L })

    // The legacy bulk-export action is now a safe no-op for newly saved invoices.
    val exportResult = engine.exportUnlinkedPurchaseItemsToInventory()
    assertEquals(0, exportResult.first)
    assertEquals(0, exportResult.second)
    assertEquals(10, db.inventoryDao().getItemById(existingItemId)!!.quantity)
    assertEquals(4, db.inventoryDao().getItemById(rice.id)!!.quantity)
    assertEquals(3, db.inventoryDao().getItemById(flour.id)!!.quantity)
    assertEquals(288, db.inventoryDao().getItemById(water.id)!!.quantity)

    // Deleting the invoice reverses every linked stock movement exactly.
    engine.deleteInvoice(invoiceId)
    assertEquals(5, db.inventoryDao().getItemById(existingItemId)!!.quantity)
    assertEquals(0, db.inventoryDao().getItemById(rice.id)!!.quantity)
    assertEquals(0, db.inventoryDao().getItemById(flour.id)!!.quantity)
    assertEquals(0, db.inventoryDao().getItemById(water.id)!!.quantity)
  }

  @Test
  fun editingFreeTypedPurchaseInvoiceReversesAndReappliesStockExactly() = runBlocking {
    val supplierId = engine.saveSupplier(0L, "مورد التعديل", "770000001", "", 0.0)
    val invoiceId = engine.saveInvoice(
      0L, "PUR-EDIT-001", "PURCHASE", supplierId, "مورد التعديل",
      listOf(InvoiceItemEntity(0L, 0L, 0L, "قطم سكر 10 كيلو", 2, 5000.0, 10000.0)),
      10000.0
    )
    val sugar = db.inventoryDao().getItemByName("قطم سكر 10 كيلو")!!
    assertEquals(2, sugar.quantity)
    assertEquals(5000.0, sugar.purchasePrice, 0.01)

    engine.saveInvoice(
      invoiceId, "PUR-EDIT-001", "PURCHASE", supplierId, "مورد التعديل",
      listOf(InvoiceItemEntity(0L, 0L, 0L, "قطم سكر 10 كيلو", 3, 5200.0, 15600.0)),
      15600.0
    )
    assertEquals(3, db.inventoryDao().getItemById(sugar.id)!!.quantity)
    assertEquals(5200.0, db.inventoryDao().getItemById(sugar.id)!!.purchasePrice, 0.01)
    assertEquals(sugar.id, db.invoiceDao().getInvoiceWithItems(invoiceId)!!.items.single().itemId)
  }

  @Test
  fun purchaseCartonsConvertToBaseStockAndReverseExactly() = runBlocking {
    val supplierId = engine.saveSupplier(0L, "مورد العبوات", "770000123", "الجملة", 0.0)
    val itemId = engine.saveInventoryItem(
      0L, "مشروب", "مواد", 10, 50.0, 65.0,
      """{"base":"حبة","packName":"باكت","packSize":24,"cartonName":"كرتون","cartonPacks":6}"""
    )
    val invoiceId = engine.saveInvoice(
      invoiceId = 0L,
      invoiceNumber = "PACK-144",
      type = "PURCHASE",
      partyId = supplierId,
      partyName = "مورد العبوات",
      rawItems = listOf(
        InvoiceItemEntity(
          id = 0L, invoiceId = 0L, itemId = itemId, itemName = "مشروب",
          quantity = 2, unitPrice = 7200.0, totalPrice = 14400.0,
          stockQuantity = 288, unitLabel = "كرتون", unitFactor = 144
        )
      ),
      paidAmount = 14400.0
    )
    assertEquals(298, db.inventoryDao().getItemById(itemId)!!.quantity)
    assertEquals(50.0, db.inventoryDao().getItemById(itemId)!!.purchasePrice, 0.001)
    val savedLine = db.invoiceDao().getInvoiceWithItems(invoiceId)!!.items.single()
    assertEquals(288, savedLine.stockQuantity)
    assertEquals("كرتون", savedLine.unitLabel)
    assertEquals(144, savedLine.unitFactor)

    val history = engine.getInventoryMovementHistory(itemId)!!
    assertEquals(288, history.totalPurchased)
    assertEquals(288, history.movements.single().deltaQty)

    engine.deleteInvoice(invoiceId)
    assertEquals(10, db.inventoryDao().getItemById(itemId)!!.quantity)
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
    assertTrue(restoreMsg.contains("ملخص المطابقة"))
    assertTrue(restoreMsg.contains("لا يتم تعديل الأرصدة تلقائياً"))
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

  @Test
  fun multiItemInvoiceWithCustomAndInventoryItemsSupportsEditingAndBackupRestore() = runBlocking {
    val appDb = AppDatabase.getDatabase(context)
    val appEngine = GroceryAccountingEngine(appDb)
    appEngine.resetAllDatabase()

    val custId = appEngine.saveCustomer(0L, "سليمان البرعسي", "777000222", "ذمار", 0.0)
    val stockId = appEngine.saveInventoryItem(0L, "دقيق ابيض", "حبوب", 15, 3000.0, 3500.0, "كيس")

    val customDate = 1763312400000L
    val invoiceId = appEngine.saveInvoice(
      invoiceId = 0L,
      invoiceNumber = "INV-MULTI-1",
      type = "CREDIT",
      partyId = custId,
      partyName = "سليمان البرعسي",
      rawItems = listOf(
        InvoiceItemEntity(0L, 0L, stockId, "دقيق ابيض", 2, 3500.0, 7000.0),
        InvoiceItemEntity(0L, 0L, 0L, "بضاعة خارجية سريعة", 3, 500.0, 1500.0)
      ),
      paidAmount = 1000.0,
      invoiceDateMillis = customDate
    )

    assertEquals(13, appDb.inventoryDao().getItemById(stockId)!!.quantity)
    assertEquals(7500.0, appDb.customerDao().getCustomerById(custId)!!.balance, 0.01)
    assertEquals(customDate, appDb.invoiceDao().getInvoiceWithItems(invoiceId)!!.invoice.date)

    // Edit multi-item invoice: change date, update inventory qty to 1, add another custom item
    val editedDate = 1763398800000L
    appEngine.saveInvoice(
      invoiceId = invoiceId,
      invoiceNumber = "INV-MULTI-1",
      type = "CREDIT",
      partyId = custId,
      partyName = "سليمان البرعسي",
      rawItems = listOf(
        InvoiceItemEntity(0L, 0L, stockId, "دقيق ابيض", 1, 3500.0, 3500.0),
        InvoiceItemEntity(0L, 0L, 0L, "بضاعة خارجية سريعة", 3, 500.0, 1500.0),
        InvoiceItemEntity(0L, 0L, 0L, "خدمة نقل وتوصيل", 1, 1000.0, 1000.0)
      ),
      paidAmount = 1500.0,
      invoiceDateMillis = editedDate
    )

    assertEquals(14, appDb.inventoryDao().getItemById(stockId)!!.quantity)
    assertEquals(4500.0, appDb.customerDao().getCustomerById(custId)!!.balance, 0.01)
    val editedWithItems = appDb.invoiceDao().getInvoiceWithItems(invoiceId)!!
    assertEquals(editedDate, editedWithItems.invoice.date)
    assertEquals(3, editedWithItems.items.size)

    // Backup & Restore must succeed even when invoice contains itemId == 0L lines
    val backupFile = File(context.cacheDir, "multi_custom_backup.json")
    val uri = Uri.fromFile(backupFile)
    val exportMsg = BackupRestoreManager.exportToUri(context, uri)
    assertTrue(exportMsg.contains("بنجاح"))

    appEngine.resetAllDatabase()
    val restoreMsg = BackupRestoreManager.restoreFromUri(context, uri)
    assertTrue(restoreMsg.contains("1 فاتورة"))
    val restoredItems = appDb.invoiceDao().getInvoiceWithItems(invoiceId)!!.items
    assertEquals(3, restoredItems.size)
    assertTrue(restoredItems.any { it.itemId == 0L && it.itemName == "خدمة نقل وتوصيل" })
    appEngine.resetAllDatabase()
  }

  @Test
  fun allowsEditingCustomerAndSupplierBalancesAndTransactionDates() = runBlocking {
    // 1. Editing balance before any transactions updates opening balance cleanly
    val custId = engine.saveCustomer(0L, "عميل اختبار", "771234000", "صنعاء", 1000.0)
    engine.saveCustomer(custId, "عميل اختبار", "771234000", "صنعاء", 1500.0)
    assertEquals(1500.0, db.customerDao().getCustomerById(custId)!!.balance, 0.01)

    // 2. Editing balance when transactions exist appends a manual balance adjustment entry
    val txDate = 1763312400000L
    val txId = engine.addCustomerTransaction(custId, 500.0, isDebit = true, description = "أغراض", dateMillis = txDate)
    assertEquals(2000.0, db.customerDao().getCustomerById(custId)!!.balance, 0.01)

    engine.saveCustomer(custId, "عميل اختبار", "771234000", "صنعاء", 2600.0)
    val detailAfterBalEdit = engine.getCustomerDetail(custId)!!
    assertEquals(2600.0, detailAfterBalEdit.customer.balance, 0.01)
    assertTrue(detailAfterBalEdit.transactions.any { it.transaction.description == "تعديل رصيد يدوي" && it.transaction.amount == 600.0 })

    // 3. Edit customer transaction date + amount
    val newTxDate = 1763399900000L
    engine.updateCustomerTransaction(txId, 700.0, isDebit = true, description = "أغراض معدلة", dateMillis = newTxDate)
    assertEquals(newTxDate, db.transactionDao().getTransactionById(txId)!!.date)
    assertEquals(2800.0, db.customerDao().getCustomerById(custId)!!.balance, 0.01)

    // 4. Supplier balance editing + transaction date editing
    val suppId = engine.saveSupplier(0L, "مورد اختبار", "770009999", "الشركة", 2000.0)
    val stxId = engine.addSupplierTransaction(suppId, 1000.0, isCredit = true, description = "دفعة بضاعة", dateMillis = txDate)
    engine.saveSupplier(suppId, "مورد اختبار", "770009999", "الشركة", 3500.0)
    assertEquals(3500.0, db.supplierDao().getSupplierById(suppId)!!.balance, 0.01)

    engine.updateSupplierTransaction(stxId, 1200.0, isCredit = true, description = "بضاعة معدلة", dateMillis = newTxDate)
    assertEquals(newTxDate, db.transactionDao().getTransactionById(stxId)!!.date)
    assertEquals(3700.0, db.supplierDao().getSupplierById(suppId)!!.balance, 0.01)
  }

  @Test
  fun roomMigration1To3PreservesAllDataAndDeduplicatesInvoiceNumbers() = runBlocking {
    val dbName = "migration_test_1_2.db"
    val dbFile = context.getDatabasePath(dbName)
    dbFile.parentFile?.mkdirs()
    if (dbFile.exists()) dbFile.delete()
    File(dbFile.absolutePath + "-wal").delete()
    File(dbFile.absolutePath + "-shm").delete()

    // Create a genuine v1 SQLite schema matching pre-migration tables without v2 indexes
    val legacySqlite = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(dbFile, null)
    try {
      legacySqlite.execSQL("CREATE TABLE IF NOT EXISTS `customers` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `phone` TEXT NOT NULL, `address` TEXT NOT NULL, `balance` REAL NOT NULL)")
      legacySqlite.execSQL("CREATE TABLE IF NOT EXISTS `suppliers` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `phone` TEXT NOT NULL, `company` TEXT NOT NULL, `balance` REAL NOT NULL)")
      legacySqlite.execSQL("CREATE TABLE IF NOT EXISTS `inventory_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `category` TEXT NOT NULL, `quantity` INTEGER NOT NULL, `purchasePrice` REAL NOT NULL, `sellingPrice` REAL NOT NULL, `unit` TEXT NOT NULL)")
      legacySqlite.execSQL("CREATE TABLE IF NOT EXISTS `invoices` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `invoiceNumber` TEXT NOT NULL, `type` TEXT NOT NULL, `partyId` INTEGER NOT NULL, `partyName` TEXT NOT NULL, `date` INTEGER NOT NULL, `totalAmount` REAL NOT NULL, `paidAmount` REAL NOT NULL, `remainingAmount` REAL NOT NULL, `status` TEXT NOT NULL)")
      legacySqlite.execSQL("CREATE TABLE IF NOT EXISTS `invoice_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `invoiceId` INTEGER NOT NULL, `itemId` INTEGER NOT NULL, `itemName` TEXT NOT NULL, `quantity` INTEGER NOT NULL, `unitPrice` REAL NOT NULL, `totalPrice` REAL NOT NULL)")
      legacySqlite.execSQL("CREATE TABLE IF NOT EXISTS `transactions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `partyType` TEXT NOT NULL, `partyId` INTEGER NOT NULL, `partyName` TEXT NOT NULL, `type` TEXT NOT NULL, `amount` REAL NOT NULL, `description` TEXT NOT NULL, `date` INTEGER NOT NULL)")

      legacySqlite.execSQL("INSERT INTO customers (id, name, phone, address, balance) VALUES (1, 'فايز العزي', '777437514', 'صنعاء', 19000.0)")
      legacySqlite.execSQL("INSERT INTO suppliers (id, name, phone, company, balance) VALUES (1, 'مؤسسة النور', '771112223', 'النور', 50000.0)")
      legacySqlite.execSQL("INSERT INTO inventory_items (id, name, category, quantity, purchasePrice, sellingPrice, unit) VALUES (1, 'سكر 10ك', 'غذائية', 25, 4000.0, 4500.0, 'قطمة')")
      legacySqlite.execSQL("INSERT INTO invoices (id, invoiceNumber, type, partyId, partyName, date, totalAmount, paidAmount, remainingAmount, status) VALUES (1, 'DUP-101', 'CREDIT', 1, 'فايز العزي', 1763312400000, 9000.0, 0.0, 9000.0, 'UNPAID')")
      legacySqlite.execSQL("INSERT INTO invoices (id, invoiceNumber, type, partyId, partyName, date, totalAmount, paidAmount, remainingAmount, status) VALUES (2, 'DUP-101', 'CREDIT', 1, 'فايز العزي', 1763312500000, 10000.0, 0.0, 10000.0, 'UNPAID')")
      legacySqlite.execSQL("INSERT INTO invoice_items (id, invoiceId, itemId, itemName, quantity, unitPrice, totalPrice) VALUES (1, 1, 1, 'سكر 10ك', 2, 4500.0, 9000.0)")
      legacySqlite.execSQL("INSERT INTO invoice_items (id, invoiceId, itemId, itemName, quantity, unitPrice, totalPrice) VALUES (2, 2, 1, 'صنف خاص', 2, 5000.0, 10000.0)")
      legacySqlite.execSQL("INSERT INTO transactions (id, partyType, partyId, partyName, type, amount, description, date) VALUES (1, 'CUSTOMER', 1, 'فايز العزي', 'DEBIT (عليه)', 19000.0, 'رصيد مرحل', 1763312400000)")
      legacySqlite.execSQL("INSERT INTO transactions (id, partyType, partyId, partyName, type, amount, description, date) VALUES (2, 'CUSTOMER', 1, 'فايز العزي', 'DEBIT (عليه)', 9000.0, 'فاتورة مبيعات رقم DUP-101', 1763312400000)")
      legacySqlite.execSQL("PRAGMA user_version = 1")
    } finally {
      legacySqlite.close()
    }

    val migratedDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
      .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5)
      .allowMainThreadQueries()
      .build()
    try {
      val customers = migratedDb.customerDao().getCustomersSnapshot()
      val suppliers = migratedDb.supplierDao().getSuppliersSnapshot()
      val inventory = migratedDb.inventoryDao().getItemsSnapshot()
      val invoices = migratedDb.invoiceDao().getInvoicesSnapshot()
      val invoiceItems = migratedDb.invoiceDao().getInvoiceItemsSnapshot()
      val txs = migratedDb.transactionDao().getTransactionsSnapshot()

      assertEquals(1, customers.size)
      assertEquals(19000.0, customers[0].balance, 0.01)
      assertEquals(1, suppliers.size)
      assertEquals(1, inventory.size)
      assertEquals(2, invoices.size)
      assertEquals(2, invoiceItems.size)
      assertTrue(invoiceItems.all { it.stockQuantity == 0 && it.unitFactor == 1 && it.unitLabel.isEmpty() })
      assertEquals(2, txs.size)
      assertEquals(1L, txs.single { it.id == 2L }.invoiceId)

      // Duplicate invoiceNumber row id=2 must be deduplicated cleanly to DUP-101-DUP-2
      val numbers = invoices.map { it.invoiceNumber }.toSet()
      assertEquals(setOf("DUP-101", "DUP-101-DUP-2"), numbers)

      // Unique index now rejects subsequent duplicate invoiceNumbers while allowing distinct ones
      val newInvId = migratedDb.invoiceDao().insertInvoice(
        InvoiceEntity(0L, "POST-MIG-200", "CASH", 1L, "فايز العزي", 1763312600000L, 4500.0, 4500.0, 0.0, "PAID")
      )
      assertTrue(newInvId > 0L)
    } finally {
      migratedDb.close()
      dbFile.delete()
      File(dbFile.absolutePath + "-wal").delete()
      File(dbFile.absolutePath + "-shm").delete()
    }
  }

  @Test
  fun twoPanelNotesCanBeSavedEditedListedAndDeleted() = runBlocking {
    val now = System.currentTimeMillis()
    val noteId = db.noteDao().insertNote(
      NoteEntity(
        id = 0L,
        createdAt = now,
        updatedAt = now,
        rightRowsJson = """[{"qty":"12","detail":"سكر 10 كيلو"},{"qty":"3","detail":"زيت"}]""",
        leftRowsJson = """[{"qty":"5","detail":"دقيق"}]"""
      )
    )
    assertTrue(noteId > 0L)
    val saved = db.noteDao().getNoteById(noteId)!!
    assertEquals(2, org.json.JSONArray(saved.rightRowsJson).length())
    assertEquals("دقيق", org.json.JSONArray(saved.leftRowsJson).getJSONObject(0).getString("detail"))
    db.noteDao().updateNote(saved.copy(updatedAt = now + 1000L, rightRowsJson = """[{"qty":"7","detail":"سكر"}]"""))
    assertEquals("7", org.json.JSONArray(db.noteDao().getNoteById(noteId)!!.rightRowsJson).getJSONObject(0).getString("qty"))
    assertEquals(noteId, db.noteDao().getNotesSnapshot().first().id)
    db.noteDao().deleteNoteById(noteId)
    assertEquals(null, db.noteDao().getNoteById(noteId))
  }

  @Test
  fun largeMonetaryAmountsRoundingAndOverflowRejectionWorkCorrectly() = runBlocking {
    // Decimal rounding checks (HALF_UP at 2 decimals)
    assertEquals(123.46, com.example.viewmodel.GroceryViewModel.roundMoney(123.455), 0.0001)
    assertEquals(4766079.55, com.example.viewmodel.GroceryViewModel.roundMoney(4766079.554), 0.0001)

    val custId = engine.saveCustomer(0L, "عميل الجملة الكبير", "771000200", "صنعاء", 4766079.55)
    val itemId = engine.saveInventoryItem(0L, "بضاعة جملة", "مواد", 100, 100000.0, 125000.0, "طرد")

    val invId = engine.saveInvoice(
      invoiceId = 0L,
      invoiceNumber = "BIG-1",
      type = "CREDIT",
      partyId = custId,
      partyName = "عميل الجملة الكبير",
      rawItems = listOf(InvoiceItemEntity(0L, 0L, itemId, "بضاعة جملة", 10, 125000.0, 1250000.0)),
      paidAmount = 250000.0
    )
    assertTrue(invId > 0)
    assertEquals(5766079.55, db.customerDao().getCustomerById(custId)!!.balance, 0.01)

    // Overflow beyond MAX_MONETARY_AMOUNT must be rejected
    val tooBig = com.example.viewmodel.GroceryViewModel.MAX_MONETARY_AMOUNT * 2.0
    try {
      engine.saveCustomer(0L, "تجاوز الحد", "", "", tooBig)
      fail("Expected monetary overflow rejection")
    } catch (_: IllegalArgumentException) {
    }

    val invalidRes = com.example.viewmodel.GroceryViewModel.validateInvoiceInput(
      invoiceNumber = "OVERFLOW-1",
      type = "CASH",
      items = listOf(InvoiceItemEntity(0L, 0L, 0L, "صنف", 1, tooBig, tooBig)),
      paidAmount = 0.0
    )
    assertTrue(!invalidRes.isValid)
  }

  @Test
  fun inventoryMovementHistoryAndTransactionSnapshotTrackBalancesAndStock() = runBlocking {
    val custId = engine.saveCustomer(0L, "أمين المخازن", "771234999", "تعز", 1000.0)
    val suppId = engine.saveSupplier(0L, "مورد الأرز", "770999888", "الخير", 0.0)
    val itemId = engine.saveInventoryItem(0L, "أرز بسمتي 5ك", "حبوب", 10, 5000.0, 6000.0, "كيس")

    engine.saveInvoice(
      invoiceId = 0L,
      invoiceNumber = "PUR-501",
      type = "PURCHASE",
      partyId = suppId,
      partyName = "مورد الأرز",
      rawItems = listOf(InvoiceItemEntity(0L, 0L, itemId, "أرز بسمتي 5ك", 12, 5000.0, 60000.0)),
      paidAmount = 20000.0,
      invoiceDateMillis = 1000000L
    )
    engine.saveInvoice(
      invoiceId = 0L,
      invoiceNumber = "SAL-502",
      type = "CREDIT",
      partyId = custId,
      partyName = "أمين المخازن",
      rawItems = listOf(InvoiceItemEntity(0L, 0L, itemId, "أرز بسمتي 5ك", 4, 6000.0, 24000.0)),
      paidAmount = 4000.0,
      invoiceDateMillis = 2000000L
    )

    val history = engine.getInventoryMovementHistory(itemId)!!
    assertEquals(18, history.item.quantity)
    assertEquals(12, history.totalPurchased)
    assertEquals(4, history.totalSold)
    assertEquals(2, history.movements.size)
    // Latest first: SAL-502 (-4), then PUR-501 (+12)
    assertEquals(-4, history.movements[0].deltaQty)
    assertEquals(12, history.movements[1].deltaQty)

    // Customer balance was 1000 + 20000 = 21000; add payment of 5000 => 16000
    val txId = engine.addCustomerTransaction(custId, 5000.0, isDebit = false, description = "دفعة على الحساب", dateMillis = 3000000L)
    val snap = engine.getTransactionReceiptSnapshot(txId)!!
    assertEquals(21000.0, snap.previousBalance, 0.01)
    assertEquals(16000.0, snap.currentBalance, 0.01)
    assertEquals("771234999", snap.partyPhone)
  }

  @Test
  fun comprehensiveBackupRestoreValidatesAllEdgeCasesAndCreatesSafetyBackup() = runBlocking {
    val appDb = AppDatabase.getDatabase(context)
    val appEngine = GroceryAccountingEngine(appDb)
    appEngine.resetAllDatabase()

    com.example.utils.StorePreferences.saveStoreInfo(context, "بقالة العزي اختبار", "777437514", "صنعاء", "ر.ي")
    com.example.utils.StorePreferences.savePrinterWidth(context, 80)

    val custId = appEngine.saveCustomer(0L, "عميل النسخ", "771234567", "صنعاء", 2000.0)
    val itemId = appEngine.saveInventoryItem(0L, "شاي كبوس", "مشروبات", 30, 800.0, 1000.0, "علبة")
    // Record a manual stock adjustment so backup/restore also exercises the audit trail.
    appEngine.saveInventoryItem(itemId, "شاي كبوس", "مشروبات", 31, 800.0, 1000.0, "علبة")
    val backupInvoiceId = appEngine.saveInvoice(
      invoiceId = 0L,
      invoiceNumber = "BKP-1",
      type = "CREDIT",
      partyId = custId,
      partyName = "عميل النسخ",
      rawItems = listOf(InvoiceItemEntity(0L, 0L, itemId, "شاي كبوس", 2, 1000.0, 2000.0)),
      paidAmount = 500.0
    )

    // 1. Valid backup including settings
    val validJson = BackupRestoreManager.buildBackupEnvelope(context).toString(2)
    assertEquals(1, appDb.inventoryAdjustmentDao().getAllAdjustmentsSnapshot().size)
    assertEquals(800.0, appDb.invoiceDao().getInvoiceItemsSnapshot().single().costPriceAtSale!!, 0.01)
    assertEquals(backupInvoiceId, appDb.transactionDao().getTransactionsSnapshot().single().invoiceId)

    // Change store data & settings, then restore validJson -> safety backup must capture pre-restore state
    appEngine.resetAllDatabase()
    val preRestoreCust = appEngine.saveCustomer(0L, "عميل قبل الاستعادة", "770000001", "", 999.0)
    assertTrue(preRestoreCust > 0)
    com.example.utils.StorePreferences.saveStoreInfo(context, "متجر مؤقت", "", "", "دولار")
    com.example.utils.StorePreferences.savePrinterWidth(context, 58)

    val safetyFile = BackupRestoreManager.getSafetyBackupFile(context)
    if (safetyFile.exists()) safetyFile.delete()

    val restoreMsg = BackupRestoreManager.restoreFromJson(context, validJson, createSafetyBackup = true)
    assertTrue(restoreMsg.contains("1 عميل"))
    assertEquals(1, appDb.inventoryAdjustmentDao().getAllAdjustmentsSnapshot().size)
    assertEquals(800.0, appDb.invoiceDao().getInvoiceItemsSnapshot().single().costPriceAtSale!!, 0.01)
    assertEquals(backupInvoiceId, appDb.transactionDao().getTransactionsSnapshot().single().invoiceId)
    assertEquals("بقالة العزي اختبار", com.example.utils.StorePreferences.getSettings(context).storeName)
    assertEquals(80, com.example.utils.StorePreferences.getSettings(context).printerWidthMm)

    // 10. Safety backup created automatically and recoverable
    assertTrue(safetyFile.exists() && safetyFile.length() > 50L)
    val safetyMsg = BackupRestoreManager.restoreFromSafetyBackup(context)
    assertTrue(safetyMsg.contains("1 عميل"))
    assertEquals("عميل قبل الاستعادة", appDb.customerDao().getCustomersSnapshot()[0].name)

    // 2. Backward-compatible legacy backup without "settings" field
    val noSettingsEnv = org.json.JSONObject(validJson).apply {
      val payload = getJSONObject("payload").apply { remove("settings") }
      put("sha256", sha256Hex(payload.toString()))
    }.toString()
    val noSettingsMsg = BackupRestoreManager.restoreFromJson(context, noSettingsEnv, createSafetyBackup = false)
    assertTrue(noSettingsMsg.contains("1 فاتورة"))

    // 3. Empty file rejected
    try {
      BackupRestoreManager.restoreFromJson(context, "   ")
      fail("Empty JSON should be rejected")
    } catch (e: IllegalArgumentException) {
      assertTrue(e.message!!.contains("فارغ"))
    }

    // 4. Corrupted JSON syntax throws Arabic error (not raw JSONException)
    try {
      BackupRestoreManager.restoreFromJson(context, "{invalid-json::")
      fail("Corrupted JSON should be rejected")
    } catch (e: IllegalArgumentException) {
      assertTrue(e.message!!.contains("JSON"))
    }

    // 5. Invalid checksum rejected
    val badHash = org.json.JSONObject(validJson).put("sha256", "0000deadbeef").toString()
    try {
      BackupRestoreManager.restoreFromJson(context, badHash)
      fail("Bad sha256 should be rejected")
    } catch (e: IllegalArgumentException) {
      assertTrue(e.message!!.contains("سلامة الملف"))
    }

    // 6. Missing table rejected
    val missingTable = org.json.JSONObject(validJson).let { env ->
      val p = env.getJSONObject("payload").apply { remove("inventoryItems") }
      env.put("sha256", sha256Hex(p.toString()))
      env.toString()
    }
    try {
      BackupRestoreManager.restoreFromJson(context, missingTable)
      fail("Missing table should be rejected")
    } catch (e: IllegalArgumentException) {
      assertTrue(e.message!!.contains("inventoryItems"))
    }

    // 7. Duplicate invoice numbers rejected
    val dupInvoiceNumber = org.json.JSONObject(validJson).let { env ->
      val p = env.getJSONObject("payload")
      val invArr = p.getJSONArray("invoices")
      val cloneInv = org.json.JSONObject(invArr.getJSONObject(0).toString()).put("id", 999L)
      invArr.put(cloneInv)
      val itemArr = p.getJSONArray("invoiceItems")
      val cloneLine = org.json.JSONObject(itemArr.getJSONObject(0).toString()).put("id", 999L).put("invoiceId", 999L)
      itemArr.put(cloneLine)
      env.put("sha256", sha256Hex(p.toString()))
      env.toString()
    }
    try {
      BackupRestoreManager.restoreFromJson(context, dupInvoiceNumber)
      fail("Duplicate invoice number should be rejected")
    } catch (e: IllegalArgumentException) {
      assertTrue(e.message!!.contains("مكررة"))
    }

    // 8. Invalid financial math rejected (item.totalPrice != quantity * unitPrice)
    val badMath = org.json.JSONObject(validJson).let { env ->
      val p = env.getJSONObject("payload")
      p.getJSONArray("invoiceItems").getJSONObject(0).put("totalPrice", 99999.0)
      env.put("sha256", sha256Hex(p.toString()))
      env.toString()
    }
    try {
      BackupRestoreManager.restoreFromJson(context, badMath)
      fail("Mismatched line total should be rejected")
    } catch (e: IllegalArgumentException) {
      assertTrue(e.message!!.contains("غير متطابقة"))
    }

    // 9. Broken itemId FK rejected
    val brokenFk = org.json.JSONObject(validJson).let { env ->
      val p = env.getJSONObject("payload")
      p.getJSONArray("invoiceItems").getJSONObject(0).put("itemId", 888888L)
      env.put("sha256", sha256Hex(p.toString()))
      env.toString()
    }
    try {
      BackupRestoreManager.restoreFromJson(context, brokenFk)
      fail("Broken itemId FK should be rejected")
    } catch (e: IllegalArgumentException) {
      assertTrue(e.message!!.contains("غير مرتبطة"))
    }

    appEngine.resetAllDatabase()
  }


  @Test
  fun invoiceLedgerRowsCannotBeEditedOrDeletedIndependentlyAndCostIsFrozenAtSale() = runBlocking {
    val customerId = engine.saveCustomer(0L, "عميل ربط الفاتورة", "", "", 0.0)
    val itemId = engine.saveInventoryItem(0L, "صنف بتكلفة متغيرة", "", 10, 10.0, 20.0, "حبة")
    val invoiceId = engine.saveInvoice(0L, "LINK-COST-001", "CREDIT", customerId, "عميل ربط الفاتورة",
      listOf(InvoiceItemEntity(0L, 0L, itemId, "صنف بتكلفة متغيرة", 2, 20.0, 40.0)), 0.0)
    val invoiceTx = db.transactionDao().getTransactionsForPartySnapshot("CUSTOMER", customerId).single()
    assertEquals(invoiceId, invoiceTx.invoiceId)
    try { engine.deleteCustomerTransaction(invoiceTx.id); fail("Invoice movement must be protected") }
    catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("فاتورة")) }
    try { engine.updateCustomerTransaction(invoiceTx.id, 1.0, true, "تعديل غير مسموح"); fail("Invoice movement must be protected") }
    catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("فاتورة")) }
    engine.saveInventoryItem(itemId, "صنف بتكلفة متغيرة", "", 8, 15.0, 25.0, "حبة")
    val report = engine.buildReport()
    assertEquals(20.0, report.cogs, 0.01)
    assertEquals(0, report.uncostedSalesLineCount)
    assertEquals(20.0, report.grossProfit, 0.01)
  }

  @Test
  fun manualInventoryQuantityChangesAreAuditedAndIncludedInHistory() = runBlocking {
    val itemId = engine.saveInventoryItem(0L, "صنف تسوية", "مواد", 20, 100.0, 130.0, "حبة")
    engine.saveInventoryItem(itemId, "صنف تسوية", "مواد", 15, 100.0, 130.0, "حبة")
    val history = engine.getInventoryMovementHistory(itemId)!!
    assertEquals(-5, history.totalAdjusted)
    assertEquals("ADJUSTMENT", history.movements.single().invoiceType)
    assertEquals(-5, history.movements.single().deltaQty)
  }


  @Test
  fun editingInvoiceWithPreviouslyRemovedLegacyLedgerRowDoesNotDoubleReverseBalance() = runBlocking {
    val customerId = engine.saveCustomer(0L, "عميل حركة قديمة", "", "", 0.0)
    val itemId = engine.saveInventoryItem(0L, "صنف حركة قديمة", "", 10, 5.0, 10.0, "حبة")
    val invoiceId = engine.saveInvoice(0L, "ORPHAN-INV-01", "CREDIT", customerId, "عميل حركة قديمة",
      listOf(InvoiceItemEntity(0L, 0L, itemId, "صنف حركة قديمة", 2, 10.0, 20.0)), 0.0)
    val oldTx = db.transactionDao().getTransactionsForInvoice(invoiceId).single()
    db.transactionDao().deleteTransaction(oldTx)
    db.customerDao().updateBalance(customerId, -20.0)
    engine.saveInvoice(invoiceId, "ORPHAN-INV-01", "CREDIT", customerId, "عميل حركة قديمة",
      listOf(InvoiceItemEntity(0L, 0L, itemId, "صنف حركة قديمة", 3, 10.0, 30.0)), 0.0)
    assertEquals(30.0, db.customerDao().getCustomerById(customerId)!!.balance, 0.01)
    val newTx = db.transactionDao().getTransactionsForInvoice(invoiceId).single()
    assertEquals(30.0, newTx.amount, 0.01)
  }

  @Test
  fun totalFirstFractionalUnitPricePreservesExactLineTotalAndRestoresCleanly() = runBlocking {
    val appDb = AppDatabase.getDatabase(context)
    val appEngine = GroceryAccountingEngine(appDb)
    appEngine.resetAllDatabase()

    val itemId = appEngine.saveInventoryItem(0L, "بسكويت جملة", "حلويات", 50, 2.5, 3.3333, "حبة")
    // qty = 30, total = 100.0 => 100 / 30 = 3.333333... rounded to 3.3333 (4 decimals)
    val unit4 = com.example.viewmodel.GroceryViewModel.roundUnitPrice(100.0 / 30.0)
    assertEquals(3.3333, unit4, 0.00001)

    val invoiceId = appEngine.saveInvoice(
      invoiceId = 0L,
      invoiceNumber = "FRAC-30-100",
      type = "CASH",
      partyId = 0L,
      partyName = "عميل نقدي",
      rawItems = listOf(InvoiceItemEntity(0L, 0L, itemId, "بسكويت جملة", 30, unit4, 100.0)),
      paidAmount = 100.0
    )

    val saved = appDb.invoiceDao().getInvoiceWithItems(invoiceId)!!
    assertEquals(100.0, saved.invoice.totalAmount, 0.001)
    assertEquals(100.0, saved.items.single().totalPrice, 0.001)
    assertEquals(3.3333, saved.items.single().unitPrice, 0.00001)

    // Save advanced settings and roundtrip through BackupRestoreManager
    com.example.utils.StorePreferences.saveAdvancedSettings(
      context,
      com.example.utils.StoreSettings(
        storeName = "بقالة العزي فرع 2",
        currency = "ر.ي",
        uiTextScale = "SMALL",
        compactLists = true,
        defaultInvoiceType = "CREDIT",
        autoFillCashPaid = false,
        confirmBeforeDeleteInvoice = false,
        quickPartyCount = 8,
        lowStockThreshold = 25,
        defaultInventorySort = "VALUE_DESC",
        defaultInventoryUnit = "باكت",
        defaultInventoryCategory = "بقوليات",
        defaultCustomerSort = "BALANCE_ASC",
        promptWhatsAppAfterTx = false,
        showZeroBalanceParties = false,
        defaultReportRange = "WEEK",
        showUncostedProfitWarning = false,
        printerWidthMm = 80,
        requireThermalPreview = false,
        autoOpenPdfAfterExport = false,
        preferWhatsAppDirect = false,
        autoSaveThermalImage = true
      )
    )

    val reportDefault = appEngine.buildReport(lowStockThreshold = 10)
    val reportCustom = appEngine.buildReport(lowStockThreshold = 25)
    assertEquals(0, reportDefault.lowStockCount) // remaining qty = 20 > 10
    assertEquals(1, reportCustom.lowStockCount) // remaining qty = 20 <= 25

    val envJson = BackupRestoreManager.buildBackupEnvelope(context).toString()
    com.example.utils.StorePreferences.resetAdvancedSettings(context)
    appEngine.resetAllDatabase()

    val restoreMsg = BackupRestoreManager.restoreFromJson(context, envJson, createSafetyBackup = false)
    assertTrue(restoreMsg.contains("1 فاتورة"))
    assertEquals(100.0, appDb.invoiceDao().getInvoiceWithItems(invoiceId)!!.invoice.totalAmount, 0.001)
    val restoredSettings = com.example.utils.StorePreferences.getSettings(context)
    assertEquals("SMALL", restoredSettings.uiTextScale)
    assertTrue(restoredSettings.compactLists)
    assertEquals("CREDIT", restoredSettings.defaultInvoiceType)
    assertEquals(8, restoredSettings.quickPartyCount)
    assertEquals(25, restoredSettings.lowStockThreshold)
    assertEquals("WEEK", restoredSettings.defaultReportRange)
    assertEquals(80, restoredSettings.printerWidthMm)
    assertTrue(restoredSettings.autoSaveThermalImage)

    appEngine.resetAllDatabase()
    com.example.utils.StorePreferences.resetAdvancedSettings(context)
  }

  private fun sha256Hex(value: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
      .digest(value.toByteArray(Charsets.UTF_8))
      .joinToString("") { "%02x".format(it) }

  @Test
  fun inventoryAdjustmentKeepsItsOwnRecordIdSeparateFromItemId() = runBlocking {
    val itemId = engine.saveInventoryItem(0L, "صنف تسوية معرف", "عام", 5, 100.0, 120.0, "حبة")
    engine.saveInventoryItem(itemId, "صنف تسوية معرف", "عام", 8, 100.0, 120.0, "حبة")

    val history = engine.getInventoryMovementHistory(itemId)!!
    val adjustment = history.movements.single()

    assertEquals("ADJUSTMENT", adjustment.invoiceType)
    assertEquals(0L, adjustment.invoiceId)
    assertTrue(adjustment.adjustmentId > 0L)
    assertEquals(3, adjustment.deltaQty)
    assertEquals(8, history.item.quantity)

    engine.updateInventoryAdjustment(adjustment.adjustmentId, 12, "تصحيح الكمية", 2000L)
    assertEquals(12, db.inventoryDao().getItemById(itemId)!!.quantity)
    val updated = engine.getInventoryMovementHistory(itemId)!!.movements.single()
    assertEquals(12, updated.adjustmentNewQuantity)
    assertEquals("تصحيح الكمية", updated.partyName)

    engine.deleteInventoryAdjustment(adjustment.adjustmentId)
    assertEquals(5, db.inventoryDao().getItemById(itemId)!!.quantity)
    assertTrue(engine.getInventoryMovementHistory(itemId)!!.movements.isEmpty())
  }


  @Test
  fun transactionReceiptUsesHistoricalBalanceAtSelectedTransactionNotLatestBalance() = runBlocking {
    val customerId = engine.saveCustomer(0L, "عميل سجل تاريخي", "771234567", "", 1000.0)
    val firstTxId = engine.addCustomerTransaction(customerId, 1000.0, isDebit = true, description = "الحركة الأولى", dateMillis = 1000L)
    engine.addCustomerTransaction(customerId, 300.0, isDebit = false, description = "الحركة الثانية", dateMillis = 2000L)

    val receipt = engine.getTransactionReceiptSnapshot(firstTxId)!!
    assertEquals(1000.0, receipt.previousBalance, 0.01)
    assertEquals(2000.0, receipt.currentBalance, 0.01)
  }

}
