package com.example.data

import androidx.room.withTransaction
import com.example.viewmodel.GroceryViewModel
import java.util.Calendar
import kotlin.math.abs
import org.json.JSONObject

data class LedgerEntryWithBalance(
  val transaction: TransactionEntity,
  val runningBalance: Double
)

data class CustomerDetailSnapshot(
  val customer: CustomerEntity,
  val transactions: List<LedgerEntryWithBalance>,
  val invoices: List<InvoiceEntity>
)

data class SupplierDetailSnapshot(
  val supplier: SupplierEntity,
  val transactions: List<LedgerEntryWithBalance>,
  val invoices: List<InvoiceEntity>
)

data class TopSoldItemReport(
  val itemName: String,
  val totalQuantity: Int,
  val totalRevenue: Double
)

data class FinancialReportSnapshot(
  val todaySales: Double,
  val todayPurchases: Double,
  val periodSales: Double,
  val periodPurchases: Double,
  val cashSales: Double,
  val creditSales: Double,
  val cogs: Double,
  val grossProfit: Double,
  val uncostedSalesLineCount: Int = 0,
  val totalDebts: Double,
  val totalPayables: Double,
  val inventoryCostValue: Double,
  val inventoryRetailValue: Double,
  val lowStockCount: Int,
  val invoiceCount: Int,
  val topItems: List<TopSoldItemReport>,
  val topDebtors: List<CustomerEntity>,
  val topPurchases: List<InvoiceEntity>
)

data class StockMovementEntry(
  val invoiceId: Long,
  val invoiceNumber: String,
  val invoiceType: String,
  val partyName: String,
  val deltaQty: Int,
  val unitPrice: Double,
  val totalPrice: Double,
  val date: Long,
  val adjustmentId: Long = 0L,
  val adjustmentPreviousQuantity: Int? = null,
  val adjustmentNewQuantity: Int? = null
)

data class InventoryMovementSnapshot(
  val item: InventoryItemEntity,
  val totalPurchased: Int,
  val totalSold: Int,
  val movements: List<StockMovementEntry>,
  val totalAdjusted: Int = 0
)

data class TransactionReceiptSnapshot(
  val transaction: TransactionEntity,
  val partyPhone: String,
  val currentBalance: Double,
  val previousBalance: Double
)

class GroceryAccountingEngine(private val database: AppDatabase) {

  private val customerDao = database.customerDao()
  private val supplierDao = database.supplierDao()
  private val inventoryDao = database.inventoryDao()
  private val invoiceDao = database.invoiceDao()
  private val transactionDao = database.transactionDao()
  private val inventoryAdjustmentDao = database.inventoryAdjustmentDao()

  private fun purchaseInventoryUnitConfig(item: InvoiceItemEntity): String {
    val label = item.unitLabel.trim()
    val factor = item.unitFactor.coerceAtLeast(1)
    if (label.isBlank()) return "حبة"
    // A free-typed line with factor 1 is stocked in its selected base unit
    // (for example, one carton or one bag). Converted package units retain
    // their base-unit multiplier (for example, one carton = 48 pieces).
    val baseUnit = if (factor <= 1) label else "حبة"
    return JSONObject()
      .put("base", baseUnit)
      .put("packName", if (factor <= 1) "" else label)
      .put("packSize", if (factor <= 1) 1 else factor)
      .put("cartonName", "")
      .put("cartonPacks", 1)
      .toString()
  }

  private fun expectedInventoryUnitFactor(rawConfig: String, unitLabel: String): Int? {
    val label = unitLabel.trim()
    if (label.isBlank()) return null
    val config = runCatching { JSONObject(rawConfig) }.getOrNull()
      ?: return if (rawConfig.trim() == label) 1 else null
    val base = config.optString("base", "").trim()
    if (label == base && base.isNotBlank()) return 1
    val packName = config.optString("packName", "").trim()
    val packSize = config.optInt("packSize", 1)
    if (label == packName && packName.isNotBlank() && packSize > 0) return packSize
    val cartonName = config.optString("cartonName", "").trim()
    val cartonCount = config.optInt("cartonPacks", 1)
    if (label == cartonName && cartonName.isNotBlank() && cartonCount > 0) {
      val factor = if (packName.isNotBlank()) packSize.toLong() * cartonCount else cartonCount.toLong()
      return factor.takeIf { it in 1..1_000_000 }?.toInt()
    }
    return null
  }

  private fun inventoryBaseUnit(rawConfig: String): String {
    val parsed = runCatching { JSONObject(rawConfig).optString("base", "").trim() }.getOrNull().orEmpty()
    return parsed.ifBlank { rawConfig.trim() }
  }

  private fun sanitizeMoney(value: Double, label: String): Double {
    require(value.isFinite() && abs(value) <= GroceryViewModel.MAX_MONETARY_AMOUNT) { "$label خارج النطاق المسموح" }
    return GroceryViewModel.roundMoney(value)
  }

  private fun sanitizeUnitPrice(value: Double, label: String): Double {
    require(value.isFinite() && abs(value) <= GroceryViewModel.MAX_MONETARY_AMOUNT) { "$label خارج النطاق المسموح" }
    return GroceryViewModel.roundUnitPrice(value)
  }

  // ---------------- Customers ----------------

  suspend fun saveCustomer(
    id: Long,
    name: String,
    phone: String,
    address: String,
    initialBalance: Double
  ): Long {
    val cleanName = name.trim()
    require(cleanName.isNotEmpty()) { "اسم العميل مطلوب" }
    val safeBalance = sanitizeMoney(initialBalance, "الرصيد")

    return database.withTransaction {
      val existingByName = customerDao.getCustomerByName(cleanName)
      check(existingByName == null || existingByName.id == id) { "يوجد عميل مسجل بهذا الاسم مسبقاً" }

      if (id > 0) {
        val current = customerDao.getCustomerById(id) ?: error("العميل غير موجود")
        val diff = GroceryViewModel.roundMoney(safeBalance - current.balance)
        customerDao.updateCustomer(
          current.copy(
            name = cleanName,
            phone = phone.trim(),
            address = address.trim(),
            balance = safeBalance
          )
        )
        if (abs(diff) > 0.001) {
          val existingTxs = transactionDao.getTransactionsForPartySnapshot("CUSTOMER", id)
          if (existingTxs.isNotEmpty()) {
            transactionDao.insertTransaction(
              TransactionEntity(
                partyType = "CUSTOMER",
                partyId = id,
                partyName = cleanName,
                type = if (diff > 0) "DEBIT (عليه)" else "CREDIT (له)",
                amount = abs(diff),
                description = "تعديل رصيد يدوي",
                date = System.currentTimeMillis()
              )
            )
          }
        }
        id
      } else {
        customerDao.insertCustomer(
          CustomerEntity(
            name = cleanName,
            phone = phone.trim(),
            address = address.trim(),
            balance = safeBalance
          )
        )
      }
    }
  }

  suspend fun deleteCustomer(customerId: Long) {
    database.withTransaction {
      val customer = customerDao.getCustomerById(customerId) ?: error("العميل غير موجود")
      check(abs(customer.balance) <= 0.01) { "لا يمكن حذف عميل لديه رصيد متبقٍ في الحساب" }
      check(invoiceDao.countInvoicesForParty(customerId, isPurchase = false) == 0) {
        "لا يمكن حذف عميل مرتبط بفواتير في السجل"
      }
      transactionDao.deleteByParty("CUSTOMER", customerId)
      customerDao.deleteCustomer(customer)
    }
  }

  suspend fun addCustomerTransaction(
    customerId: Long,
    amount: Double,
    isDebit: Boolean,
    description: String,
    dateMillis: Long = System.currentTimeMillis()
  ): Long {
    val safeAmount = sanitizeMoney(amount, "المبلغ")
    require(safeAmount > 0) { "أدخل مبلغاً صحيحاً أكبر من صفر" }
    return database.withTransaction {
      val customer = customerDao.getCustomerById(customerId) ?: error("العميل غير موجود")
      val delta = if (isDebit) safeAmount else -safeAmount
      check(customerDao.updateBalance(customerId, delta) == 1) { "تعذر تحديث رصيد العميل" }
      transactionDao.insertTransaction(
        TransactionEntity(
          partyType = "CUSTOMER",
          partyId = customerId,
          partyName = customer.name,
          type = if (isDebit) "DEBIT (عليه)" else "CREDIT (له)",
          amount = safeAmount,
          description = description.trim().ifBlank { if (isDebit) "دين جديد" else "سداد دفعة" },
          date = dateMillis
        )
      )
    }
  }

  suspend fun updateCustomerTransaction(
    txId: Long,
    amount: Double,
    isDebit: Boolean,
    description: String,
    dateMillis: Long? = null
  ) {
    val safeAmount = sanitizeMoney(amount, "المبلغ")
    require(safeAmount > 0) { "أدخل مبلغاً صحيحاً أكبر من صفر" }
    database.withTransaction {
      val oldTx = transactionDao.getTransactionById(txId) ?: error("الحركة غير موجودة")
      require(oldTx.partyType == "CUSTOMER") { "نوع الحركة غير متطابق" }
      check(oldTx.invoiceId == null) { "هذه الحركة مرتبطة بفاتورة؛ عدّل الفاتورة نفسها حتى لا يختل الرصيد" }
      val oldDelta = if (oldTx.type.startsWith("DEBIT")) oldTx.amount else -oldTx.amount
      val newDelta = if (isDebit) safeAmount else -safeAmount
      check(customerDao.updateBalance(oldTx.partyId, GroceryViewModel.roundMoney(newDelta - oldDelta)) == 1) { "تعذر تعديل رصيد العميل" }
      transactionDao.updateTransaction(
        oldTx.copy(
          type = if (isDebit) "DEBIT (عليه)" else "CREDIT (له)",
          amount = safeAmount,
          description = description.trim().ifBlank { oldTx.description },
          date = dateMillis?.takeIf { it > 0L } ?: oldTx.date
        )
      )
    }
  }

  suspend fun deleteCustomerTransaction(txId: Long) {
    database.withTransaction {
      val oldTx = transactionDao.getTransactionById(txId) ?: error("الحركة غير موجودة")
      require(oldTx.partyType == "CUSTOMER") { "نوع الحركة غير متطابق" }
      check(oldTx.invoiceId == null) { "لا يمكن حذف حركة فاتورة من كشف الحساب؛ احذف أو عدّل الفاتورة نفسها" }
      val oldDelta = if (oldTx.type.startsWith("DEBIT")) oldTx.amount else -oldTx.amount
      check(customerDao.updateBalance(oldTx.partyId, -oldDelta) == 1) { "تعذر عكس الرصيد على العميل" }
      transactionDao.deleteTransaction(oldTx)
    }
  }

  suspend fun getCustomerDetail(customerId: Long): CustomerDetailSnapshot? {
    val customer = customerDao.getCustomerById(customerId) ?: return null
    val txDesc = transactionDao.getTransactionsForPartySnapshot("CUSTOMER", customerId)
    val ledger = attachRunningBalances(customer.balance, txDesc, debitIncreasesBalance = true)
    val invoices = invoiceDao.getInvoicesSnapshot().filter { it.partyId == customerId && it.type != "PURCHASE" }
    return CustomerDetailSnapshot(customer, ledger, invoices)
  }

  // ---------------- Suppliers ----------------

  suspend fun saveSupplier(
    id: Long,
    name: String,
    phone: String,
    company: String,
    initialBalance: Double
  ): Long {
    val cleanName = name.trim()
    require(cleanName.isNotEmpty()) { "اسم المورد مطلوب" }
    val safeBalance = sanitizeMoney(initialBalance, "الرصيد")

    return database.withTransaction {
      val existingByName = supplierDao.getSupplierByName(cleanName)
      check(existingByName == null || existingByName.id == id) { "يوجد مورد مسجل بهذا الاسم مسبقاً" }

      if (id > 0) {
        val current = supplierDao.getSupplierById(id) ?: error("المورد غير موجود")
        val diff = GroceryViewModel.roundMoney(safeBalance - current.balance)
        supplierDao.updateSupplier(
          current.copy(
            name = cleanName,
            phone = phone.trim(),
            company = company.trim(),
            balance = safeBalance
          )
        )
        if (abs(diff) > 0.001) {
          val existingTxs = transactionDao.getTransactionsForPartySnapshot("SUPPLIER", id)
          if (existingTxs.isNotEmpty()) {
            transactionDao.insertTransaction(
              TransactionEntity(
                partyType = "SUPPLIER",
                partyId = id,
                partyName = cleanName,
                type = if (diff > 0) "CREDIT (له)" else "DEBIT (عليه)",
                amount = abs(diff),
                description = "تعديل رصيد يدوي",
                date = System.currentTimeMillis()
              )
            )
          }
        }
        id
      } else {
        supplierDao.insertSupplier(
          SupplierEntity(
            name = cleanName,
            phone = phone.trim(),
            company = company.trim(),
            balance = safeBalance
          )
        )
      }
    }
  }

  suspend fun deleteSupplier(supplierId: Long) {
    database.withTransaction {
      val supplier = supplierDao.getSupplierById(supplierId) ?: error("المورد غير موجود")
      check(abs(supplier.balance) <= 0.01) { "لا يمكن حذف مورد لديه مستحقات أو رصيد متبقٍ" }
      check(invoiceDao.countInvoicesForParty(supplierId, isPurchase = true) == 0) {
        "لا يمكن حذف مورد مرتبط بفواتير مشتريات في السجل"
      }
      transactionDao.deleteByParty("SUPPLIER", supplierId)
      supplierDao.deleteSupplier(supplier)
    }
  }

  suspend fun addSupplierTransaction(
    supplierId: Long,
    amount: Double,
    isCredit: Boolean,
    description: String,
    dateMillis: Long = System.currentTimeMillis()
  ): Long {
    val safeAmount = sanitizeMoney(amount, "المبلغ")
    require(safeAmount > 0) { "أدخل مبلغاً صحيحاً أكبر من صفر" }
    return database.withTransaction {
      val supplier = supplierDao.getSupplierById(supplierId) ?: error("المورد غير موجود")
      // For suppliers, balance > 0 means the store owes the supplier (CREDIT له).
      val delta = if (isCredit) safeAmount else -safeAmount
      check(supplierDao.updateBalance(supplierId, delta) == 1) { "تعذر تحديث رصيد المورد" }
      transactionDao.insertTransaction(
        TransactionEntity(
          partyType = "SUPPLIER",
          partyId = supplierId,
          partyName = supplier.name,
          type = if (isCredit) "CREDIT (له)" else "DEBIT (عليه)",
          amount = safeAmount,
          description = description.trim().ifBlank { if (isCredit) "مستحقات للمورد" else "دفعة سداد للمورد" },
          date = dateMillis
        )
      )
    }
  }

  suspend fun updateSupplierTransaction(
    txId: Long,
    amount: Double,
    isCredit: Boolean,
    description: String,
    dateMillis: Long? = null
  ) {
    val safeAmount = sanitizeMoney(amount, "المبلغ")
    require(safeAmount > 0) { "أدخل مبلغاً صحيحاً أكبر من صفر" }
    database.withTransaction {
      val oldTx = transactionDao.getTransactionById(txId) ?: error("الحركة غير موجودة")
      require(oldTx.partyType == "SUPPLIER") { "نوع الحركة غير متطابق" }
      check(oldTx.invoiceId == null) { "هذه الحركة مرتبطة بفاتورة؛ عدّل الفاتورة نفسها حتى لا يختل الرصيد" }
      val oldDelta = if (oldTx.type.startsWith("CREDIT")) oldTx.amount else -oldTx.amount
      val newDelta = if (isCredit) safeAmount else -safeAmount
      check(supplierDao.updateBalance(oldTx.partyId, GroceryViewModel.roundMoney(newDelta - oldDelta)) == 1) { "تعذر تعديل رصيد المورد" }
      transactionDao.updateTransaction(
        oldTx.copy(
          type = if (isCredit) "CREDIT (له)" else "DEBIT (عليه)",
          amount = safeAmount,
          description = description.trim().ifBlank { oldTx.description },
          date = dateMillis?.takeIf { it > 0L } ?: oldTx.date
        )
      )
    }
  }

  suspend fun deleteSupplierTransaction(txId: Long) {
    database.withTransaction {
      val oldTx = transactionDao.getTransactionById(txId) ?: error("الحركة غير موجودة")
      require(oldTx.partyType == "SUPPLIER") { "نوع الحركة غير متطابق" }
      check(oldTx.invoiceId == null) { "لا يمكن حذف حركة فاتورة من كشف المورد؛ احذف أو عدّل الفاتورة نفسها" }
      val oldDelta = if (oldTx.type.startsWith("CREDIT")) oldTx.amount else -oldTx.amount
      check(supplierDao.updateBalance(oldTx.partyId, -oldDelta) == 1) { "تعذر عكس الرصيد على المورد" }
      transactionDao.deleteTransaction(oldTx)
    }
  }

  suspend fun getSupplierDetail(supplierId: Long): SupplierDetailSnapshot? {
    val supplier = supplierDao.getSupplierById(supplierId) ?: return null
    val txDesc = transactionDao.getTransactionsForPartySnapshot("SUPPLIER", supplierId)
    val ledger = attachRunningBalances(supplier.balance, txDesc, debitIncreasesBalance = false)
    val invoices = invoiceDao.getInvoicesSnapshot().filter { it.partyId == supplierId && it.type == "PURCHASE" }
    return SupplierDetailSnapshot(supplier, ledger, invoices)
  }

  // ---------------- Inventory ----------------

  suspend fun saveInventoryItem(
    id: Long,
    name: String,
    category: String,
    quantity: Int,
    purchasePrice: Double,
    sellingPrice: Double,
    unit: String
  ): Long {
    val cleanName = name.trim()
    val cleanUnit = unit.trim().ifBlank { "حبة" }
    require(cleanName.isNotEmpty()) { "اسم الصنف مطلوب" }
    require(quantity in 0..10_000_000) { "الكمية خارج النطاق المسموح" }
    require(purchasePrice.isFinite() && purchasePrice >= 0) { "سعر الشراء غير صحيح" }
    require(sellingPrice.isFinite() && sellingPrice >= 0) { "سعر البيع غير صحيح" }

    return database.withTransaction {
      val existingByName = inventoryDao.getItemByName(cleanName)
      check(existingByName == null || existingByName.id == id) { "يوجد صنف مسجل بهذا الاسم مسبقاً" }

      if (id > 0) {
        val previous = inventoryDao.getItemById(id) ?: error("الصنف غير موجود")
        val oldBaseUnit = inventoryBaseUnit(previous.unit)
        val newBaseUnit = inventoryBaseUnit(cleanUnit)
        val rebaseFactor = if (oldBaseUnit == newBaseUnit) 1 else {
          expectedInventoryUnitFactor(cleanUnit, oldBaseUnit)
            ?: error("لا يمكن تغيير الوحدة الأساسية من «$oldBaseUnit» إلى «$newBaseUnit» دون تحديد عدد وحدات «$newBaseUnit» داخل «$oldBaseUnit».")
        }
        require(rebaseFactor in 1..1_000_000) { "معامل تحويل الوحدة غير صحيح" }

        val adjustedQuantity = Math.multiplyExact(quantity, rebaseFactor)
        val adjustedPurchasePrice = GroceryViewModel.roundUnitPrice(purchasePrice / rebaseFactor)
        val adjustedSellingPrice = GroceryViewModel.roundUnitPrice(sellingPrice / rebaseFactor)

        if (rebaseFactor > 1) {
          // Rebase every historical stock movement to the new smallest unit so
          // invoice editing/deletion, inventory history, and COGS stay balanced.
          val oldLines = invoiceDao.getInvoiceItemsSnapshot().filter { it.itemId == id }
          if (oldLines.isNotEmpty()) {
            val convertedLines = oldLines.map { line ->
              val oldFactor = line.unitFactor.coerceAtLeast(1)
              val oldStockQty = line.stockQuantity.takeIf { it > 0 }
                ?: Math.multiplyExact(line.quantity, oldFactor)
              line.copy(
                stockQuantity = Math.multiplyExact(oldStockQty, rebaseFactor),
                unitLabel = line.unitLabel.ifBlank { oldBaseUnit },
                unitFactor = Math.multiplyExact(oldFactor, rebaseFactor),
                costPriceAtSale = line.costPriceAtSale?.let {
                  GroceryViewModel.roundUnitPrice(it / rebaseFactor)
                }
              )
            }
            invoiceDao.insertItems(convertedLines)
          }

          inventoryAdjustmentDao.getAdjustmentsForItem(id).forEach { adjustment ->
            inventoryAdjustmentDao.updateAdjustment(
              adjustment.copy(
                previousQuantity = Math.multiplyExact(adjustment.previousQuantity, rebaseFactor),
                newQuantity = Math.multiplyExact(adjustment.newQuantity, rebaseFactor),
                deltaQuantity = Math.multiplyExact(adjustment.deltaQuantity, rebaseFactor)
              )
            )
          }
        }

        val quantityBeforeManualChange = Math.multiplyExact(previous.quantity, rebaseFactor)
        if (quantityBeforeManualChange != adjustedQuantity) {
          inventoryAdjustmentDao.insertAdjustment(
            InventoryAdjustmentEntity(
              id = 0L,
              itemId = id,
              itemName = cleanName,
              previousQuantity = quantityBeforeManualChange,
              newQuantity = adjustedQuantity,
              deltaQuantity = adjustedQuantity - quantityBeforeManualChange,
              reason = if (rebaseFactor > 1) "تسوية الرصيد بعد تحويل الوحدة الأساسية" else "تسوية يدوية من شاشة بيانات الصنف",
              date = System.currentTimeMillis()
            )
          )
        }

        inventoryDao.updateItem(
          InventoryItemEntity(
            id = id,
            name = cleanName,
            category = category.trim().ifBlank { "عام" },
            quantity = adjustedQuantity,
            purchasePrice = adjustedPurchasePrice,
            sellingPrice = adjustedSellingPrice,
            unit = cleanUnit
          )
        )
        id
      } else {
        inventoryDao.insertItem(
          InventoryItemEntity(
            name = cleanName,
            category = category.trim().ifBlank { "عام" },
            quantity = quantity,
            purchasePrice = purchasePrice,
            sellingPrice = sellingPrice,
            unit = cleanUnit
          )
        )
      }
    }
  }

  /**
   * Imports only purchase invoice rows that are not linked to inventory yet.
   * Existing linked rows were already applied to stock when their invoice was saved.
   * All stock updates and row links are committed atomically, preventing repeat imports.
   */
  suspend fun exportUnlinkedPurchaseItemsToInventory(): Pair<Int, Int> {
    return database.withTransaction {
      val purchaseIds = invoiceDao.getInvoicesSnapshot()
        .filter { it.type == "PURCHASE" }
        .map { it.id }
        .toSet()
      val pendingLines = invoiceDao.getInvoiceItemsSnapshot()
        .filter { it.invoiceId in purchaseIds && it.itemId <= 0L }
      var importedLines = 0
      var createdItems = 0

      for (line in pendingLines) {
        val cleanName = line.itemName.trim()
        require(cleanName.isNotEmpty()) { "يوجد صنف بلا اسم في إحدى فواتير الشراء" }
        require(line.quantity > 0) { "كمية غير صحيحة للصنف: $cleanName" }
        require(line.unitPrice.isFinite() && line.unitPrice >= 0.0) { "سعر شراء غير صحيح للصنف: $cleanName" }

        val baseQuantity = line.stockQuantity.takeIf { it > 0 } ?: Math.multiplyExact(line.quantity, line.unitFactor.coerceAtLeast(1))
        val baseUnitPrice = GroceryViewModel.roundUnitPrice(line.unitPrice / line.unitFactor.coerceAtLeast(1))
        val existing = inventoryDao.getItemByName(cleanName)
        val targetId = if (existing != null) {
          val newQuantity = Math.addExact(existing.quantity, baseQuantity)
          inventoryDao.updateItem(existing.copy(quantity = newQuantity, purchasePrice = baseUnitPrice))
          existing.id
        } else {
          createdItems++
          inventoryDao.insertItem(
            InventoryItemEntity(
              name = cleanName,
              category = "عام",
              quantity = baseQuantity,
              purchasePrice = baseUnitPrice,
              sellingPrice = 0.0,
              unit = purchaseInventoryUnitConfig(line)
            )
          )
        }
        check(invoiceDao.linkInvoiceItemToInventory(line.id, targetId) == 1) {
          "تعذر ربط الصنف $cleanName بالمخزن؛ أعد المحاولة"
        }
        importedLines++
      }
      importedLines to createdItems
    }
  }

  suspend fun deleteInventoryItem(itemId: Long) {
    database.withTransaction {
      val item = inventoryDao.getItemById(itemId) ?: error("الصنف غير موجود")
      val usedInInvoices = invoiceDao.getInvoiceItemsSnapshot().any { it.itemId == itemId }
      check(!usedInInvoices) { "لا يمكن حذف صنف مستخدم في فواتير محفوظة" }
      check(inventoryAdjustmentDao.getAdjustmentsForItem(itemId).isEmpty()) {
        "لا يمكن حذف صنف له سجل تسويات مخزون؛ احتفظ بسجل حركة الكمية للمراجعة"
      }
      inventoryDao.deleteItem(item)
    }
  }

  suspend fun updateInventoryAdjustment(
    adjustmentId: Long,
    newQuantity: Int,
    reason: String
  ) {
    require(newQuantity in 0..10_000_000) { "الكمية الجديدة خارج النطاق المسموح" }
    val cleanReason = reason.trim()
    require(cleanReason.isNotEmpty()) { "سبب التسوية مطلوب" }
    database.withTransaction {
      val adjustment = inventoryAdjustmentDao.getAdjustmentById(adjustmentId) ?: error("سجل التسوية غير موجود")
      val item = inventoryDao.getItemById(adjustment.itemId) ?: error("الصنف المرتبط بالتسوية غير موجود")
      val newDelta = newQuantity - adjustment.previousQuantity
      val recalculatedStock = item.quantity - adjustment.deltaQuantity + newDelta
      require(recalculatedStock in 0..10_000_000) { "تعديل التسوية ينتج عنه كمية مخزون غير صالحة" }
      inventoryDao.updateItem(item.copy(quantity = recalculatedStock))
      check(inventoryAdjustmentDao.updateAdjustment(adjustment.copy(
        itemName = item.name,
        newQuantity = newQuantity,
        deltaQuantity = newDelta,
        reason = cleanReason,
        date = adjustment.date
      )) == 1) { "تعذر تحديث سجل التسوية" }
    }
  }

  suspend fun deleteInventoryAdjustment(adjustmentId: Long) {
    database.withTransaction {
      val adjustment = inventoryAdjustmentDao.getAdjustmentById(adjustmentId) ?: error("سجل التسوية غير موجود")
      val item = inventoryDao.getItemById(adjustment.itemId) ?: error("الصنف المرتبط بالتسوية غير موجود")
      val recalculatedStock = item.quantity - adjustment.deltaQuantity
      require(recalculatedStock in 0..10_000_000) { "حذف التسوية ينتج عنه كمية مخزون غير صالحة" }
      inventoryDao.updateItem(item.copy(quantity = recalculatedStock))
      check(inventoryAdjustmentDao.deleteAdjustmentById(adjustmentId) == 1) { "تعذر حذف سجل التسوية المحدد" }
    }
  }

  suspend fun getInventoryMovementHistory(itemId: Long): InventoryMovementSnapshot? {
    val item = inventoryDao.getItemById(itemId) ?: return null
    val invoiceMap = invoiceDao.getInvoicesSnapshot().associateBy { it.id }
    val matchingLines = invoiceDao.getInvoiceItemsSnapshot().filter { it.itemId == itemId }
    var totalPurchased = 0
    var totalSold = 0
    val invoiceMovements = matchingLines.mapNotNull { line ->
      val inv = invoiceMap[line.invoiceId] ?: return@mapNotNull null
      val isPurchase = inv.type == "PURCHASE"
      val baseQuantity = line.stockQuantity.takeIf { it > 0 } ?: Math.multiplyExact(line.quantity, line.unitFactor.coerceAtLeast(1))
      if (isPurchase) totalPurchased += baseQuantity else totalSold += baseQuantity
      StockMovementEntry(inv.id, inv.invoiceNumber, inv.type, inv.partyName,
        if (isPurchase) baseQuantity else -baseQuantity, line.unitPrice, line.totalPrice, inv.date)
    }
    val adjustments = inventoryAdjustmentDao.getAdjustmentsForItem(itemId).map { a ->
      StockMovementEntry(0L, "تسوية مخزون", "ADJUSTMENT", a.reason, a.deltaQuantity,
        item.purchasePrice, kotlin.math.abs(a.deltaQuantity).toDouble() * item.purchasePrice,
        a.date, a.id, a.previousQuantity, a.newQuantity)
    }
    val totalAdjusted = adjustments.sumOf { it.deltaQty }
    val movements = (invoiceMovements + adjustments).sortedByDescending { it.date }
    return InventoryMovementSnapshot(item, totalPurchased, totalSold, movements, totalAdjusted)
  }

  suspend fun getTransactionReceiptSnapshot(txId: Long): TransactionReceiptSnapshot? {
    val tx = transactionDao.getTransactionById(txId) ?: return null
    val isCustomer = tx.partyType == "CUSTOMER"
    val balance: Double
    val phone: String
    if (isCustomer) {
      val c = customerDao.getCustomerById(tx.partyId) ?: return null
      balance = c.balance
      phone = c.phone
    } else {
      val s = supplierDao.getSupplierById(tx.partyId) ?: return null
      balance = s.balance
      phone = s.phone
    }
    val partyTransactions = transactionDao.getTransactionsForPartySnapshot(tx.partyType, tx.partyId)
      .sortedWith(compareBy<TransactionEntity> { it.date }.thenBy { it.id })
    val totalDelta = partyTransactions.sumOf { txDelta(it, debitIncreasesBalance = isCustomer) }
    var runningBalance = GroceryViewModel.roundMoney(balance - totalDelta)
    var previousBalance = runningBalance
    var transactionBalance = runningBalance
    var found = false
    for (entry in partyTransactions) {
      val before = runningBalance
      runningBalance = GroceryViewModel.roundMoney(runningBalance + txDelta(entry, debitIncreasesBalance = isCustomer))
      if (entry.id == txId) {
        previousBalance = before
        transactionBalance = runningBalance
        found = true
        break
      }
    }
    check(found) { "تعذر تحديد رصيد الحركة في تسلسل الحساب" }
    return TransactionReceiptSnapshot(tx, phone, transactionBalance, previousBalance)
  }

  // ---------------- Invoices ----------------

  suspend fun saveInvoice(
    invoiceId: Long,
    invoiceNumber: String,
    type: String,
    partyId: Long,
    partyName: String,
    rawItems: List<InvoiceItemEntity>,
    paidAmount: Double,
    invoiceDateMillis: Long? = null
  ): Long {
    val safePaidInput = sanitizeMoney(paidAmount, "المبلغ المدفوع")
    val normalizedItems = rawItems.map {
      val safeUnit = sanitizeUnitPrice(it.unitPrice, "سعر الوحدة")
      require(it.unitFactor > 0 && it.unitFactor <= 1_000_000) { "معامل الوحدة غير صحيح" }
      require(it.stockQuantity >= 0 && it.stockQuantity <= 10_000_000) { "كمية المخزون المحولة غير صحيحة" }
      val expectedFromUnit = it.quantity.toDouble() * safeUnit
      val lineTolerance = maxOf(0.02, 0.00001 * maxOf(1.0, abs(expectedFromUnit)))
      val computedTotal = if (it.totalPrice > 0 && it.totalPrice.isFinite() && abs(it.totalPrice - expectedFromUnit) <= lineTolerance) {
        GroceryViewModel.roundMoney(it.totalPrice)
      } else {
        GroceryViewModel.roundMoney(expectedFromUnit)
      }
      val convertedStock = Math.multiplyExact(it.quantity, it.unitFactor)
      require(convertedStock in 1..10_000_000) { "كمية المخزون المحولة غير صحيحة للصنف: ${it.itemName}" }
      it.copy(id = 0L, itemName = it.itemName.trim(), unitPrice = safeUnit, totalPrice = computedTotal, stockQuantity = convertedStock)
    }
    val validation = GroceryViewModel.validateInvoiceInput(invoiceNumber, type, normalizedItems, safePaidInput)
    require(validation.isValid) { validation.message }

    val cleanNumber = invoiceNumber.trim()
    val normalizedType = validation.normalizedType
    val normalizedPaid = minOf(safePaidInput, validation.totalAmount)
    val cleanPartyName = partyName.trim().ifBlank { if (normalizedType == "PURCHASE") "مورد" else "عميل نقدي" }

    if (normalizedType == "PURCHASE") {
      require(partyId > 0) { "حدد المورد قبل حفظ فاتورة الشراء" }
    } else if (validation.remainingAmount > 0.000001) {
      require(partyId > 0) { "حدد العميل لتسجيل المبلغ المتبقي كدين" }
    }

    return database.withTransaction {
      if (invoiceId <= 0L) {
        normalizedItems.filter { it.itemId > 0L }.forEach { line ->
          val stockItem = inventoryDao.getItemById(line.itemId)
            ?: error("الصنف غير موجود في المخزن: ${line.itemName}")
          if (line.unitLabel.isBlank() && line.unitFactor == 1) return@forEach
          val expectedFactor = expectedInventoryUnitFactor(stockItem.unit, line.unitLabel)
          require(expectedFactor != null && expectedFactor == line.unitFactor) {
            "وحدة الصنف «${line.unitLabel}» غير مطابقة لتعريف «${line.itemName}». راجع بيانات الصنف؛ يجب أن يكون معامل التحويل صحيحاً قبل حفظ الفاتورة."
          }
        }
      }
      val duplicate = invoiceDao.getInvoiceByNumber(cleanNumber)
      check(duplicate == null || duplicate.id == invoiceId) { "رقم الفاتورة مستخدم من قبل" }

      if (normalizedType == "PURCHASE") {
        check(supplierDao.getSupplierById(partyId) != null) { "المورد المحدد غير موجود" }
      } else if (partyId > 0) {
        check(customerDao.getCustomerById(partyId) != null) { "العميل المحدد غير موجود" }
      }

      val targetInvoiceId: Long
      val effectiveDate: Long

      if (invoiceId > 0) {
        val previous = invoiceDao.getInvoiceWithItems(invoiceId) ?: error("الفاتورة المطلوب تعديلها غير موجودة")
        reverseInvoiceEffects(previous)

        effectiveDate = invoiceDateMillis?.takeIf { it > 0L } ?: previous.invoice.date
        val updated = previous.invoice.copy(
          invoiceNumber = cleanNumber,
          type = normalizedType,
          partyId = partyId,
          partyName = cleanPartyName,
          date = effectiveDate,
          totalAmount = validation.totalAmount,
          paidAmount = normalizedPaid,
          remainingAmount = validation.remainingAmount,
          status = validation.status
        )
        invoiceDao.updateInvoice(updated)
        invoiceDao.deleteItemsForInvoice(invoiceId)
        targetInvoiceId = invoiceId
      } else {
        effectiveDate = invoiceDateMillis?.takeIf { it > 0L } ?: System.currentTimeMillis()
        val created = InvoiceEntity(
          invoiceNumber = cleanNumber,
          type = normalizedType,
          partyId = partyId,
          partyName = cleanPartyName,
          date = effectiveDate,
          totalAmount = validation.totalAmount,
          paidAmount = normalizedPaid,
          remainingAmount = validation.remainingAmount,
          status = validation.status
        )
        targetInvoiceId = invoiceDao.insertInvoice(created)
      }

      // Resolve every free-typed purchase line to a real inventory item inside
      // this same Room transaction. A purchase must never be left pending for a
      // separate "export to stock" action.
      val persistedItems = normalizedItems.map { item ->
        if (normalizedType != "PURCHASE") {
          val capturedCost = if (item.itemId > 0L) inventoryDao.getItemById(item.itemId)?.purchasePrice
            ?: error("الصنف غير موجود في المخزن: ${item.itemName}") else null
          item.copy(costPriceAtSale = capturedCost)
        } else {
          val factor = item.unitFactor.coerceAtLeast(1)
          val baseUnitPrice = GroceryViewModel.roundUnitPrice(item.unitPrice / factor)
          val resolvedItemId = if (item.itemId > 0L) {
            check(inventoryDao.getItemById(item.itemId) != null) {
              "الصنف غير موجود في المخزن: ${item.itemName}"
            }
            item.itemId
          } else {
            val existing = inventoryDao.getItemByName(item.itemName)
            if (existing != null) {
              existing.id
            } else {
              inventoryDao.insertItem(
                InventoryItemEntity(
                  name = item.itemName,
                  category = "عام",
                  quantity = 0,
                  purchasePrice = baseUnitPrice,
                  sellingPrice = 0.0,
                  unit = purchaseInventoryUnitConfig(item)
                )
              )
            }
          }
          item.copy(itemId = resolvedItemId)
        }
      }

      invoiceDao.insertItems(persistedItems.map { it.copy(invoiceId = targetInvoiceId) })

      val newStockSign = if (normalizedType == "PURCHASE") 1 else -1
      for (item in persistedItems) {
        if (item.itemId > 0L) {
          val stockDelta = item.stockQuantity.takeIf { it > 0 } ?: item.quantity
          check(inventoryDao.adjustQuantity(item.itemId, newStockSign * stockDelta) == 1) {
            "الكمية غير متوفرة للصنف: ${item.itemName} أو أن الصنف غير موجود"
          }
          if (normalizedType == "PURCHASE") {
            val stockItem = inventoryDao.getItemById(item.itemId)
              ?: error("الصنف غير موجود: ${item.itemName}")
            val factor = item.unitFactor.coerceAtLeast(1)
            val baseUnitPrice = GroceryViewModel.roundUnitPrice(item.unitPrice / factor)
            inventoryDao.updateItem(stockItem.copy(purchasePrice = baseUnitPrice))
          }
        }
      }

      if (validation.remainingAmount > 0.000001) {
        if (normalizedType == "PURCHASE") {
          check(supplierDao.updateBalance(partyId, validation.remainingAmount) == 1) { "المورد المحدد غير موجود" }
          transactionDao.insertTransaction(
            TransactionEntity(
              partyType = "SUPPLIER",
              partyId = partyId,
              partyName = cleanPartyName,
              type = "CREDIT (له)",
              amount = validation.remainingAmount,
              description = "فاتورة مشتريات رقم $cleanNumber",
              date = effectiveDate,
              invoiceId = targetInvoiceId
            )
          )
        } else {
          check(customerDao.updateBalance(partyId, validation.remainingAmount) == 1) { "العميل المحدد غير موجود" }
          transactionDao.insertTransaction(
            TransactionEntity(
              partyType = "CUSTOMER",
              partyId = partyId,
              partyName = cleanPartyName,
              type = "DEBIT (عليه)",
              amount = validation.remainingAmount,
              description = "فاتورة مبيعات رقم $cleanNumber",
              date = effectiveDate,
              invoiceId = targetInvoiceId
            )
          )
        }
      }

      targetInvoiceId
    }
  }

  suspend fun deleteInvoice(invoiceId: Long) {
    database.withTransaction {
      val previous = invoiceDao.getInvoiceWithItems(invoiceId) ?: error("الفاتورة غير موجودة")
      reverseInvoiceEffects(previous)
      invoiceDao.deleteItemsForInvoice(invoiceId)
      invoiceDao.deleteInvoice(previous.invoice)
    }
  }

  private suspend fun reverseInvoiceEffects(previous: InvoiceWithItems) {
    val oldInvoice = previous.invoice
    val oldStockSign = if (oldInvoice.type == "PURCHASE") -1 else 1
    for (oldItem in previous.items) {
      if (oldItem.itemId > 0L) {
        val stockQuantity = oldItem.stockQuantity.takeIf { it > 0 } ?: oldItem.quantity
        check(inventoryDao.adjustQuantity(oldItem.itemId, oldStockSign * stockQuantity) == 1) {
          "تعذر عكس حركة المخزون للصنف ${oldItem.itemName}"
        }
      }
    }
    if (oldInvoice.partyId > 0) {
      val linkedTransactions = transactionDao.getTransactionsForInvoice(oldInvoice.id)
      if (linkedTransactions.isNotEmpty()) {
        if (oldInvoice.type == "PURCHASE") {
          val reverseDelta = linkedTransactions.sumOf { -txDelta(it, debitIncreasesBalance = false) }
          check(supplierDao.updateBalance(oldInvoice.partyId, reverseDelta) == 1) {
            "تعذر عكس رصيد المورد السابق"
          }
        } else {
          val reverseDelta = linkedTransactions.sumOf { -txDelta(it, debitIncreasesBalance = true) }
          check(customerDao.updateBalance(oldInvoice.partyId, reverseDelta) == 1) {
            "تعذر عكس رصيد العميل السابق"
          }
        }
        transactionDao.deleteByInvoiceId(oldInvoice.id)
      }
    }
  }

  // ---------------- Reports & Reset ----------------

  suspend fun buildReport(
    startDateMillis: Long = 0L,
    endDateMillis: Long = Long.MAX_VALUE,
    lowStockThreshold: Int = 10
  ): FinancialReportSnapshot {
    val invoices = invoiceDao.getInvoicesSnapshot()
    val allItems = invoiceDao.getInvoiceItemsSnapshot()
    val inventory = inventoryDao.getItemsSnapshot()
    val customers = customerDao.getCustomersSnapshot()
    val suppliers = supplierDao.getSuppliersSnapshot()

    val startOfToday = Calendar.getInstance().apply {
      set(Calendar.HOUR_OF_DAY, 0)
      set(Calendar.MINUTE, 0)
      set(Calendar.SECOND, 0)
      set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    val todaySales = invoices
      .filter { it.date >= startOfToday && it.type in setOf("SALE", "CASH", "CREDIT") }
      .sumOf { it.totalAmount }
    val todayPurchases = invoices
      .filter { it.date >= startOfToday && it.type == "PURCHASE" }
      .sumOf { it.totalAmount }

    val rangeInvoices = invoices.filter { it.date in startDateMillis..endDateMillis }
    val salesInvoices = rangeInvoices.filter { it.type in setOf("SALE", "CASH", "CREDIT") }
    val purchaseInvoices = rangeInvoices.filter { it.type == "PURCHASE" }

    val periodSales = salesInvoices.sumOf { it.totalAmount }
    val periodPurchases = purchaseInvoices.sumOf { it.totalAmount }
    val cashSales = salesInvoices.sumOf { it.paidAmount }
    val creditSales = salesInvoices.sumOf { it.remainingAmount }

    val salesInvoiceIds = salesInvoices.map { it.id }.toSet()
    val soldLines = allItems.filter { it.invoiceId in salesInvoiceIds }
    val uncostedSalesLineCount = soldLines.count { it.costPriceAtSale == null }
    val cogs = soldLines.sumOf { line ->
      (line.stockQuantity.takeIf { it > 0 } ?: line.quantity).toDouble() * (line.costPriceAtSale ?: 0.0)
    }
    val grossProfit = periodSales - cogs

    val topItems = soldLines
      .groupBy { it.itemName }
      .map { (name, lines) ->
        TopSoldItemReport(
          itemName = name,
          totalQuantity = lines.sumOf { it.stockQuantity.takeIf { q -> q > 0 } ?: it.quantity },
          totalRevenue = lines.sumOf { it.totalPrice }
        )
      }
      .sortedByDescending { it.totalRevenue }
      .take(5)

    val topDebtors = customers
      .filter { it.balance > 0.01 }
      .sortedByDescending { it.balance }
      .take(5)

    val topPurchases = purchaseInvoices
      .sortedByDescending { it.totalAmount }
      .take(5)

    return FinancialReportSnapshot(
      todaySales = todaySales,
      todayPurchases = todayPurchases,
      periodSales = periodSales,
      periodPurchases = periodPurchases,
      cashSales = cashSales,
      creditSales = creditSales,
      cogs = cogs,
      grossProfit = grossProfit,
      uncostedSalesLineCount = uncostedSalesLineCount,
      totalDebts = customers.sumOf { maxOf(it.balance, 0.0) },
      totalPayables = suppliers.sumOf { maxOf(it.balance, 0.0) },
      inventoryCostValue = inventory.sumOf { it.quantity.toDouble() * it.purchasePrice },
      inventoryRetailValue = inventory.sumOf { it.quantity.toDouble() * it.sellingPrice },
      lowStockCount = inventory.count { it.quantity <= lowStockThreshold.coerceAtLeast(0) },
      invoiceCount = rangeInvoices.size,
      topItems = topItems,
      topDebtors = topDebtors,
      topPurchases = topPurchases
    )
  }

  suspend fun resetAllDatabase() {
    database.withTransaction {
      invoiceDao.clearInvoiceItems()
      transactionDao.clearTransactions()
      inventoryAdjustmentDao.clearAdjustments()
      database.noteDao().clearNotes()
      invoiceDao.clearInvoices()
      inventoryDao.clearItems()
      customerDao.clearCustomers()
      supplierDao.clearSuppliers()
    }
  }

  private fun attachRunningBalances(
    currentBalance: Double,
    descendingTransactions: List<TransactionEntity>,
    debitIncreasesBalance: Boolean
  ): List<LedgerEntryWithBalance> {
    if (descendingTransactions.isEmpty()) return emptyList()
    val asc = descendingTransactions.sortedWith(compareBy<TransactionEntity> { it.date }.thenBy { it.id })
    val totalDelta = asc.sumOf { txDelta(it, debitIncreasesBalance) }
    var running = currentBalance - totalDelta
    val ascWithBalance = asc.map { tx ->
      running += txDelta(tx, debitIncreasesBalance)
      LedgerEntryWithBalance(tx, running)
    }
    return ascWithBalance.reversed()
  }

  private fun txDelta(tx: TransactionEntity, debitIncreasesBalance: Boolean): Double {
    val isDebit = tx.type.startsWith("DEBIT")
    return if (debitIncreasesBalance) {
      if (isDebit) tx.amount else -tx.amount
    } else {
      if (isDebit) -tx.amount else tx.amount
    }
  }
}
