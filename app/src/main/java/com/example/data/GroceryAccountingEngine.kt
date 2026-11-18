package com.example.data

import androidx.room.withTransaction
import com.example.viewmodel.GroceryViewModel
import java.util.Calendar
import kotlin.math.abs

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

class GroceryAccountingEngine(private val database: AppDatabase) {

  private val customerDao = database.customerDao()
  private val supplierDao = database.supplierDao()
  private val inventoryDao = database.inventoryDao()
  private val invoiceDao = database.invoiceDao()
  private val transactionDao = database.transactionDao()

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
    require(initialBalance.isFinite()) { "الرصيد غير صالح" }

    return database.withTransaction {
      val existingByName = customerDao.getCustomerByName(cleanName)
      check(existingByName == null || existingByName.id == id) { "يوجد عميل مسجل بهذا الاسم مسبقاً" }

      if (id > 0) {
        val current = customerDao.getCustomerById(id) ?: error("العميل غير موجود")
        customerDao.updateCustomer(
          current.copy(
            name = cleanName,
            phone = phone.trim(),
            address = address.trim()
          )
        )
        id
      } else {
        customerDao.insertCustomer(
          CustomerEntity(
            name = cleanName,
            phone = phone.trim(),
            address = address.trim(),
            balance = initialBalance
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
    require(amount.isFinite() && amount > 0) { "أدخل مبلغاً صحيحاً أكبر من صفر" }
    return database.withTransaction {
      val customer = customerDao.getCustomerById(customerId) ?: error("العميل غير موجود")
      val delta = if (isDebit) amount else -amount
      check(customerDao.updateBalance(customerId, delta) == 1) { "تعذر تحديث رصيد العميل" }
      transactionDao.insertTransaction(
        TransactionEntity(
          partyType = "CUSTOMER",
          partyId = customerId,
          partyName = customer.name,
          type = if (isDebit) "DEBIT (عليه)" else "CREDIT (له)",
          amount = amount,
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
    description: String
  ) {
    require(amount.isFinite() && amount > 0) { "أدخل مبلغاً صحيحاً أكبر من صفر" }
    database.withTransaction {
      val oldTx = transactionDao.getTransactionById(txId) ?: error("الحركة غير موجودة")
      require(oldTx.partyType == "CUSTOMER") { "نوع الحركة غير متطابق" }
      val oldDelta = if (oldTx.type.startsWith("DEBIT")) oldTx.amount else -oldTx.amount
      val newDelta = if (isDebit) amount else -amount
      check(customerDao.updateBalance(oldTx.partyId, newDelta - oldDelta) == 1) { "تعذر تعديل رصيد العميل" }
      transactionDao.updateTransaction(
        oldTx.copy(
          type = if (isDebit) "DEBIT (عليه)" else "CREDIT (له)",
          amount = amount,
          description = description.trim().ifBlank { oldTx.description }
        )
      )
    }
  }

  suspend fun deleteCustomerTransaction(txId: Long) {
    database.withTransaction {
      val oldTx = transactionDao.getTransactionById(txId) ?: error("الحركة غير موجودة")
      require(oldTx.partyType == "CUSTOMER") { "نوع الحركة غير متطابق" }
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
    require(initialBalance.isFinite()) { "الرصيد غير صالح" }

    return database.withTransaction {
      val existingByName = supplierDao.getSupplierByName(cleanName)
      check(existingByName == null || existingByName.id == id) { "يوجد مورد مسجل بهذا الاسم مسبقاً" }

      if (id > 0) {
        val current = supplierDao.getSupplierById(id) ?: error("المورد غير موجود")
        supplierDao.updateSupplier(
          current.copy(
            name = cleanName,
            phone = phone.trim(),
            company = company.trim()
          )
        )
        id
      } else {
        supplierDao.insertSupplier(
          SupplierEntity(
            name = cleanName,
            phone = phone.trim(),
            company = company.trim(),
            balance = initialBalance
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
    require(amount.isFinite() && amount > 0) { "أدخل مبلغاً صحيحاً أكبر من صفر" }
    return database.withTransaction {
      val supplier = supplierDao.getSupplierById(supplierId) ?: error("المورد غير موجود")
      // For suppliers, balance > 0 means the store owes the supplier (CREDIT له).
      val delta = if (isCredit) amount else -amount
      check(supplierDao.updateBalance(supplierId, delta) == 1) { "تعذر تحديث رصيد المورد" }
      transactionDao.insertTransaction(
        TransactionEntity(
          partyType = "SUPPLIER",
          partyId = supplierId,
          partyName = supplier.name,
          type = if (isCredit) "CREDIT (له)" else "DEBIT (عليه)",
          amount = amount,
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
    description: String
  ) {
    require(amount.isFinite() && amount > 0) { "أدخل مبلغاً صحيحاً أكبر من صفر" }
    database.withTransaction {
      val oldTx = transactionDao.getTransactionById(txId) ?: error("الحركة غير موجودة")
      require(oldTx.partyType == "SUPPLIER") { "نوع الحركة غير متطابق" }
      val oldDelta = if (oldTx.type.startsWith("CREDIT")) oldTx.amount else -oldTx.amount
      val newDelta = if (isCredit) amount else -amount
      check(supplierDao.updateBalance(oldTx.partyId, newDelta - oldDelta) == 1) { "تعذر تعديل رصيد المورد" }
      transactionDao.updateTransaction(
        oldTx.copy(
          type = if (isCredit) "CREDIT (له)" else "DEBIT (عليه)",
          amount = amount,
          description = description.trim().ifBlank { oldTx.description }
        )
      )
    }
  }

  suspend fun deleteSupplierTransaction(txId: Long) {
    database.withTransaction {
      val oldTx = transactionDao.getTransactionById(txId) ?: error("الحركة غير موجودة")
      require(oldTx.partyType == "SUPPLIER") { "نوع الحركة غير متطابق" }
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
    require(cleanName.isNotEmpty()) { "اسم الصنف مطلوب" }
    require(quantity >= 0) { "الكمية لا يمكن أن تكون سالبة" }
    require(purchasePrice.isFinite() && purchasePrice >= 0) { "سعر الشراء غير صحيح" }
    require(sellingPrice.isFinite() && sellingPrice >= 0) { "سعر البيع غير صحيح" }

    return database.withTransaction {
      val existingByName = inventoryDao.getItemByName(cleanName)
      check(existingByName == null || existingByName.id == id) { "يوجد صنف مسجل بهذا الاسم مسبقاً" }

      val entity = InventoryItemEntity(
        id = if (id > 0) id else 0L,
        name = cleanName,
        category = category.trim().ifBlank { "عام" },
        quantity = quantity,
        purchasePrice = purchasePrice,
        sellingPrice = sellingPrice,
        unit = unit.trim().ifBlank { "حبة" }
      )
      if (id > 0) {
        check(inventoryDao.getItemById(id) != null) { "الصنف غير موجود" }
        inventoryDao.updateItem(entity)
        id
      } else {
        inventoryDao.insertItem(entity)
      }
    }
  }

  suspend fun deleteInventoryItem(itemId: Long) {
    database.withTransaction {
      val item = inventoryDao.getItemById(itemId) ?: error("الصنف غير موجود")
      val usedInInvoices = invoiceDao.getInvoiceItemsSnapshot().any { it.itemId == itemId }
      check(!usedInInvoices) { "لا يمكن حذف صنف مستخدم في فواتير محفوظة" }
      inventoryDao.deleteItem(item)
    }
  }

  // ---------------- Invoices ----------------

  suspend fun saveInvoice(
    invoiceId: Long,
    invoiceNumber: String,
    type: String,
    partyId: Long,
    partyName: String,
    rawItems: List<InvoiceItemEntity>,
    paidAmount: Double
  ): Long {
    val normalizedItems = rawItems.map {
      val computedTotal = it.quantity.toDouble() * it.unitPrice
      it.copy(id = 0L, itemName = it.itemName.trim(), totalPrice = computedTotal)
    }
    val validation = GroceryViewModel.validateInvoiceInput(invoiceNumber, type, normalizedItems, paidAmount)
    require(validation.isValid) { validation.message }

    val cleanNumber = invoiceNumber.trim()
    val normalizedType = validation.normalizedType
    val cleanPartyName = partyName.trim().ifBlank { if (normalizedType == "PURCHASE") "مورد" else "عميل نقدي" }

    if (normalizedType == "PURCHASE") {
      require(partyId > 0) { "حدد المورد قبل حفظ فاتورة الشراء" }
    } else if (validation.remainingAmount > 0.000001) {
      require(partyId > 0) { "حدد العميل لتسجيل المبلغ المتبقي كدين" }
    }

    return database.withTransaction {
      val duplicate = invoiceDao.getInvoiceByNumber(cleanNumber)
      check(duplicate == null || duplicate.id == invoiceId) { "رقم الفاتورة مستخدم من قبل" }

      if (normalizedType == "PURCHASE") {
        check(supplierDao.getSupplierById(partyId) != null) { "المورد المحدد غير موجود" }
      } else if (partyId > 0) {
        check(customerDao.getCustomerById(partyId) != null) { "العميل المحدد غير موجود" }
      }

      val now = System.currentTimeMillis()
      val targetInvoiceId: Long

      if (invoiceId > 0) {
        val previous = invoiceDao.getInvoiceWithItems(invoiceId) ?: error("الفاتورة المطلوب تعديلها غير موجودة")
        reverseInvoiceEffects(previous)

        val updated = previous.invoice.copy(
          invoiceNumber = cleanNumber,
          type = normalizedType,
          partyId = partyId,
          partyName = cleanPartyName,
          date = now,
          totalAmount = validation.totalAmount,
          paidAmount = paidAmount,
          remainingAmount = validation.remainingAmount,
          status = validation.status
        )
        invoiceDao.updateInvoice(updated)
        invoiceDao.deleteItemsForInvoice(invoiceId)
        targetInvoiceId = invoiceId
      } else {
        val created = InvoiceEntity(
          invoiceNumber = cleanNumber,
          type = normalizedType,
          partyId = partyId,
          partyName = cleanPartyName,
          date = now,
          totalAmount = validation.totalAmount,
          paidAmount = paidAmount,
          remainingAmount = validation.remainingAmount,
          status = validation.status
        )
        targetInvoiceId = invoiceDao.insertInvoice(created)
      }

      invoiceDao.insertItems(normalizedItems.map { it.copy(invoiceId = targetInvoiceId) })

      val newStockSign = if (normalizedType == "PURCHASE") 1 else -1
      for (item in normalizedItems) {
        check(inventoryDao.adjustQuantity(item.itemId, newStockSign * item.quantity) == 1) {
          "الكمية غير متوفرة للصنف: ${item.itemName} أو أن الصنف غير موجود"
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
              date = now
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
              date = now
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
      check(inventoryDao.adjustQuantity(oldItem.itemId, oldStockSign * oldItem.quantity) == 1) {
        "تعذر عكس حركة المخزون للصنف ${oldItem.itemName}"
      }
    }
    if (oldInvoice.remainingAmount > 0.000001 && oldInvoice.partyId > 0) {
      if (oldInvoice.type == "PURCHASE") {
        check(supplierDao.updateBalance(oldInvoice.partyId, -oldInvoice.remainingAmount) == 1) {
          "تعذر عكس رصيد المورد السابق"
        }
        transactionDao.deleteByDescription(
          "SUPPLIER",
          oldInvoice.partyId,
          "فاتورة مشتريات رقم ${oldInvoice.invoiceNumber}"
        )
      } else {
        check(customerDao.updateBalance(oldInvoice.partyId, -oldInvoice.remainingAmount) == 1) {
          "تعذر عكس رصيد العميل السابق"
        }
        transactionDao.deleteByDescription(
          "CUSTOMER",
          oldInvoice.partyId,
          "فاتورة مبيعات رقم ${oldInvoice.invoiceNumber}"
        )
      }
    }
  }

  // ---------------- Reports & Reset ----------------

  suspend fun buildReport(startDateMillis: Long = 0L, endDateMillis: Long = Long.MAX_VALUE): FinancialReportSnapshot {
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

    val purchaseCostMap = inventory.associateBy({ it.id }, { it.purchasePrice })
    val salesInvoiceIds = salesInvoices.map { it.id }.toSet()
    val soldLines = allItems.filter { it.invoiceId in salesInvoiceIds }

    val cogs = soldLines.sumOf { line ->
      val unitCost = purchaseCostMap[line.itemId] ?: line.unitPrice
      line.quantity.toDouble() * unitCost
    }
    val grossProfit = periodSales - cogs

    val topItems = soldLines
      .groupBy { it.itemName }
      .map { (name, lines) ->
        TopSoldItemReport(
          itemName = name,
          totalQuantity = lines.sumOf { it.quantity },
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
      totalDebts = customers.sumOf { maxOf(it.balance, 0.0) },
      totalPayables = suppliers.sumOf { maxOf(it.balance, 0.0) },
      inventoryCostValue = inventory.sumOf { it.quantity.toDouble() * it.purchasePrice },
      inventoryRetailValue = inventory.sumOf { it.quantity.toDouble() * it.sellingPrice },
      lowStockCount = inventory.count { it.quantity <= 10 },
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
