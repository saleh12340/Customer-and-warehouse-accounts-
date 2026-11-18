package com.example.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.example.data.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.math.abs

class GroceryViewModel(application: Application) : AndroidViewModel(application) {
  private val database = AppDatabase.getDatabase(application)
  private val customerDao = database.customerDao()
  private val supplierDao = database.supplierDao()
  private val inventoryDao = database.inventoryDao()
  private val invoiceDao = database.invoiceDao()
  private val transactionDao = database.transactionDao()

  val customers = customerDao.getAllCustomers().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
  val suppliers = supplierDao.getAllSuppliers().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
  val inventoryItems = inventoryDao.getAllItems().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
  val invoices = invoiceDao.getAllInvoices().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
  val transactions = transactionDao.getAllTransactions().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

  data class InvoiceValidationResult(
    val isValid: Boolean,
    val message: String = "",
    val normalizedType: String = "",
    val totalAmount: Double = 0.0,
    val remainingAmount: Double = 0.0,
    val status: String = ""
  )

  companion object {
    fun validateInvoiceInput(
      invoiceNumber: String,
      type: String,
      items: List<InvoiceItemEntity>,
      paidAmount: Double
    ): InvoiceValidationResult {
      val normalizedType = type.trim().uppercase()
      if (normalizedType !in setOf("SALE", "CASH", "CREDIT", "PURCHASE")) {
        return InvoiceValidationResult(false, "نوع الفاتورة غير صحيح")
      }

      if (invoiceNumber.trim().isBlank()) {
        return InvoiceValidationResult(false, "رقم الفاتورة مطلوب")
      }

      if (items.isEmpty()) {
        return InvoiceValidationResult(false, "أضف صنفاً واحداً على الأقل")
      }

      val hasInvalidItem = items.any { item ->
        val expectedTotal = item.quantity.toDouble() * item.unitPrice
        val tolerance = 0.000001 * maxOf(1.0, abs(expectedTotal))
        item.itemName.trim().isBlank() ||
          item.itemId < 0 ||
          item.quantity <= 0 ||
          !item.unitPrice.isFinite() ||
          item.unitPrice < 0 ||
          !item.totalPrice.isFinite() ||
          item.totalPrice < 0 ||
          !expectedTotal.isFinite() ||
          abs(item.totalPrice - expectedTotal) > tolerance
      }
      if (hasInvalidItem) {
        return InvoiceValidationResult(false, "تحقق من الأصناف والكميات والأسعار وإجمالي كل صنف")
      }

      val totalAmount = items.sumOf { it.totalPrice }
      if (!totalAmount.isFinite() || totalAmount <= 0) {
        return InvoiceValidationResult(false, "إجمالي الفاتورة غير صحيح")
      }

      if (!paidAmount.isFinite() || paidAmount < 0 || paidAmount > totalAmount) {
        return InvoiceValidationResult(false, "المبلغ المدفوع يجب أن يكون بين صفر وإجمالي الفاتورة")
      }

      val remainingAmount = totalAmount - paidAmount
      val status = when {
        remainingAmount <= 0.000001 -> "PAID"
        paidAmount > 0 -> "PARTIAL"
        else -> "UNPAID"
      }

      return InvoiceValidationResult(
        isValid = true,
        normalizedType = normalizedType,
        totalAmount = totalAmount,
        remainingAmount = remainingAmount,
        status = status
      )
    }
  }

  // Customer actions
  fun addCustomer(name: String, phone: String, address: String, initialBalance: Double) {
    viewModelScope.launch {
      customerDao.insertCustomer(CustomerEntity(name = name, phone = phone, address = address, balance = initialBalance))
    }
  }

  fun updateCustomer(customer: CustomerEntity) {
    viewModelScope.launch { customerDao.updateCustomer(customer) }
  }

  fun deleteCustomer(customer: CustomerEntity) {
    viewModelScope.launch { customerDao.deleteCustomer(customer) }
  }

  fun addCustomerTransaction(customerId: Long, customerName: String, amount: Double, isDebit: Boolean, description: String) {
    viewModelScope.launch {
      if (!amount.isFinite() || amount <= 0) return@launch
      database.withTransaction {
        val delta = if (isDebit) amount else -amount
        check(customerDao.updateBalance(customerId, delta) == 1) { "العميل غير موجود" }
        val txType = if (isDebit) "DEBIT (عليه)" else "CREDIT (له)"
        transactionDao.insertTransaction(
          TransactionEntity(
            partyType = "CUSTOMER",
            partyId = customerId,
            partyName = customerName,
            type = txType,
            amount = amount,
            description = description,
            date = System.currentTimeMillis()
          )
        )
      }
    }
  }

  // Supplier actions
  fun addSupplier(name: String, phone: String, company: String, initialBalance: Double) {
    viewModelScope.launch {
      supplierDao.insertSupplier(SupplierEntity(name = name, phone = phone, company = company, balance = initialBalance))
    }
  }

  fun updateSupplier(supplier: SupplierEntity) {
    viewModelScope.launch { supplierDao.updateSupplier(supplier) }
  }

  fun deleteSupplier(supplier: SupplierEntity) {
    viewModelScope.launch { supplierDao.deleteSupplier(supplier) }
  }

  fun addSupplierTransaction(
    supplierId: Long,
    supplierName: String,
    amount: Double,
    isCredit: Boolean,
    description: String,
    onResult: (Boolean, String) -> Unit = { _, _ -> }
  ) {
    viewModelScope.launch {
      try {
        require(amount.isFinite() && amount > 0) { "أدخل مبلغاً صحيحاً أكبر من صفر" }
        database.withTransaction {
          val delta = if (isCredit) amount else -amount
          check(supplierDao.updateBalance(supplierId, delta) == 1) { "المورد غير موجود" }
          transactionDao.insertTransaction(
            TransactionEntity(
              partyType = "SUPPLIER",
              partyId = supplierId,
              partyName = supplierName,
              type = if (isCredit) "CREDIT (له)" else "DEBIT (عليه)",
              amount = amount,
              description = description.trim().ifBlank { "حركة حساب مورد" },
              date = System.currentTimeMillis()
            )
          )
        }
        onResult(true, "تم تحديث حساب المورد")
      } catch (error: Exception) {
        onResult(false, error.message ?: "تعذر تسجيل حركة المورد")
      }
    }
  }

  // Inventory actions
  fun addInventoryItem(name: String, category: String, quantity: Int, purchasePrice: Double, sellingPrice: Double, unit: String) {
    viewModelScope.launch {
      if (name.isBlank() || quantity < 0 || !purchasePrice.isFinite() || !sellingPrice.isFinite() || purchasePrice < 0 || sellingPrice < 0) return@launch
      inventoryDao.insertItem(InventoryItemEntity(name = name.trim(), category = category, quantity = quantity, purchasePrice = purchasePrice, sellingPrice = sellingPrice, unit = unit))
    }
  }

  fun updateInventoryItem(item: InventoryItemEntity) {
    viewModelScope.launch { inventoryDao.updateItem(item) }
  }

  fun deleteInventoryItem(item: InventoryItemEntity) {
    viewModelScope.launch { inventoryDao.deleteItem(item) }
  }

  fun loadInvoiceForEdit(invoiceId: Long, onLoaded: (InvoiceWithItems?) -> Unit) {
    viewModelScope.launch {
      onLoaded(invoiceDao.getInvoiceWithItems(invoiceId))
    }
  }

  /**
   * Replaces an existing invoice atomically. Previous stock and ledger effects
   * are reversed before the new invoice effects are applied. If any reversal
   * or new stock movement is invalid, Room rolls the entire edit back.
   */
  fun updateInvoice(
    invoiceId: Long,
    invoiceNumber: String,
    type: String,
    partyId: Long,
    partyName: String,
    items: List<InvoiceItemEntity>,
    paidAmount: Double,
    onResult: (Boolean, String) -> Unit = { _, _ -> }
  ) {
    viewModelScope.launch {
      try {
        val normalizedItems = items.map { it.copy(id = 0L, itemName = it.itemName.trim()) }
        val validation = validateInvoiceInput(invoiceNumber, type, normalizedItems, paidAmount)
        require(validation.isValid) { validation.message }
        val normalizedType = validation.normalizedType
        if (normalizedType == "PURCHASE") {
          require(partyId > 0) { "حدد المورد قبل حفظ فاتورة الشراء" }
        } else if (validation.remainingAmount > 0.000001) {
          require(partyId > 0) { "حدد العميل لتسجيل المبلغ المتبقي كدين" }
        }

        database.withTransaction {
          val previous = invoiceDao.getInvoiceWithItems(invoiceId)
            ?: error("الفاتورة المطلوب تعديلها غير موجودة")
          val duplicate = invoiceDao.getInvoiceByNumber(invoiceNumber.trim())
          check(duplicate == null || duplicate.id == invoiceId) { "رقم الفاتورة مستخدم من قبل" }
          if (normalizedType == "PURCHASE") {
            check(supplierDao.getSupplierById(partyId) != null) { "المورد المحدد غير موجود" }
          } else if (partyId > 0) {
            check(customerDao.getCustomerById(partyId) != null) { "العميل المحدد غير موجود" }
          }

          val oldInvoice = previous.invoice
          val oldStockSign = if (oldInvoice.type == "PURCHASE") -1 else 1
          previous.items.forEach { oldItem ->
            if (oldItem.itemId > 0L) {
              check(inventoryDao.adjustQuantity(oldItem.itemId, oldStockSign * oldItem.quantity) == 1) {
                "تعذر عكس حركة المخزون للصنف ${oldItem.itemName}. قد تكون الكمية قد استُهلكت"
              }
            }
          }

          if (oldInvoice.remainingAmount > 0.000001 && oldInvoice.partyId > 0) {
            if (oldInvoice.type == "PURCHASE") {
              check(supplierDao.updateBalance(oldInvoice.partyId, -oldInvoice.remainingAmount) == 1) {
                "تعذر عكس رصيد المورد السابق"
              }
              transactionDao.deleteByDescription(
                "SUPPLIER", oldInvoice.partyId, "فاتورة مشتريات رقم ${oldInvoice.invoiceNumber}"
              )
            } else {
              check(customerDao.updateBalance(oldInvoice.partyId, -oldInvoice.remainingAmount) == 1) {
                "تعذر عكس رصيد العميل السابق"
              }
              transactionDao.deleteByDescription(
                "CUSTOMER", oldInvoice.partyId, "فاتورة مبيعات رقم ${oldInvoice.invoiceNumber}"
              )
            }
          }

          val now = System.currentTimeMillis()
          val updated = oldInvoice.copy(
            invoiceNumber = invoiceNumber.trim(),
            type = normalizedType,
            partyId = partyId,
            partyName = partyName.trim(),
            date = now,
            totalAmount = validation.totalAmount,
            paidAmount = paidAmount,
            remainingAmount = validation.remainingAmount,
            status = validation.status
          )
          invoiceDao.updateInvoice(updated)
          invoiceDao.deleteItemsForInvoice(invoiceId)
          invoiceDao.insertItems(normalizedItems.map { it.copy(invoiceId = invoiceId) })

          val newStockSign = if (normalizedType == "PURCHASE") 1 else -1
          normalizedItems.forEach { item ->
            if (item.itemId > 0L) {
              check(inventoryDao.adjustQuantity(item.itemId, newStockSign * item.quantity) == 1) {
                "الكمية غير متوفرة للصنف: ${item.itemName} أو أن الصنف غير موجود"
              }
            }
          }

          if (validation.remainingAmount > 0.000001) {
            if (normalizedType == "PURCHASE") {
              check(supplierDao.updateBalance(partyId, validation.remainingAmount) == 1) { "المورد المحدد غير موجود" }
              transactionDao.insertTransaction(
                TransactionEntity(0L, "SUPPLIER", partyId, partyName.trim(), "CREDIT (له)",
                  validation.remainingAmount, "فاتورة مشتريات رقم ${updated.invoiceNumber}", now)
              )
            } else {
              check(customerDao.updateBalance(partyId, validation.remainingAmount) == 1) { "العميل المحدد غير موجود" }
              transactionDao.insertTransaction(
                TransactionEntity(0L, "CUSTOMER", partyId, partyName.trim(), "DEBIT (عليه)",
                  validation.remainingAmount, "فاتورة مبيعات رقم ${updated.invoiceNumber}", now)
              )
            }
          }
        }
        onResult(true, "تم تعديل الفاتورة وتحديث الحساب والمخزون")
      } catch (error: Exception) {
        onResult(false, error.message ?: "تعذر تعديل الفاتورة")
      }
    }
  }

  /**
   * Saves invoice, its lines, stock movements, party balance and ledger entry
   * as one Room transaction. Any validation or stock error rolls everything back.
   */
  fun createInvoice(
    invoiceNumber: String,
    type: String,
    partyId: Long,
    partyName: String,
    items: List<InvoiceItemEntity>,
    paidAmount: Double,
    onResult: (Boolean, String) -> Unit = { _, _ -> }
  ) {
    viewModelScope.launch {
      try {
        val normalizedItems = items.map { it.copy(itemName = it.itemName.trim()) }
        val validation = validateInvoiceInput(invoiceNumber, type, normalizedItems, paidAmount)
        require(validation.isValid) { validation.message }

        val normalizedType = validation.normalizedType
        val totalAmount = validation.totalAmount
        val remainingAmount = validation.remainingAmount
        val status = validation.status

        database.withTransaction {
          check(invoiceDao.getInvoiceByNumber(invoiceNumber.trim()) == null) { "رقم الفاتورة مستخدم من قبل" }
          if (normalizedType == "PURCHASE") {
            check(partyId > 0 && supplierDao.getSupplierById(partyId) != null) { "حدد مورداً صحيحاً لفاتورة الشراء" }
          }

          val invoice = InvoiceEntity(
            invoiceNumber = invoiceNumber.trim(),
            type = normalizedType,
            partyId = partyId,
            partyName = partyName.trim(),
            date = System.currentTimeMillis(),
            totalAmount = totalAmount,
            paidAmount = paidAmount,
            remainingAmount = remainingAmount,
            status = status
          )

          val invoiceId = invoiceDao.insertInvoice(invoice)
          invoiceDao.insertItems(normalizedItems.map { it.copy(invoiceId = invoiceId) })

          // Every non-purchase invoice leaves stock; purchases add stock.
          val stockDeltaSign = if (normalizedType == "PURCHASE") 1 else -1
          for (item in normalizedItems) {
            if (item.itemId > 0L) {
              val changedRows = inventoryDao.adjustQuantity(item.itemId, stockDeltaSign * item.quantity)
              check(changedRows == 1) {
                "الكمية غير متوفرة للصنف: ${item.itemName} أو أن الصنف غير موجود"
              }
            }
          }

          if (remainingAmount > 0.000001) {
            when (normalizedType) {
              "SALE", "CASH", "CREDIT" -> {
                check(partyId > 0) { "حدد العميل لتسجيل المبلغ المتبقي كدين" }
                check(customerDao.updateBalance(partyId, remainingAmount) == 1) { "العميل المحدد غير موجود" }
                transactionDao.insertTransaction(
                  TransactionEntity(
                    partyType = "CUSTOMER",
                    partyId = partyId,
                    partyName = partyName.trim(),
                    type = "DEBIT (عليه)",
                    amount = remainingAmount,
                    description = "فاتورة مبيعات رقم ${invoice.invoiceNumber}",
                    date = invoice.date
                  )
                )
              }
              "PURCHASE" -> {
                check(partyId > 0) { "حدد المورد لتسجيل المبلغ المتبقي" }
                check(supplierDao.updateBalance(partyId, remainingAmount) == 1) { "المورد المحدد غير موجود" }
                transactionDao.insertTransaction(
                  TransactionEntity(
                    partyType = "SUPPLIER",
                    partyId = partyId,
                    partyName = partyName.trim(),
                    type = "CREDIT (له)",
                    amount = remainingAmount,
                    description = "فاتورة مشتريات رقم ${invoice.invoiceNumber}",
                    date = invoice.date
                  )
                )
              }
            }
          }
        }
        onResult(true, "تم حفظ الفاتورة وتحديث الحساب والمخزون")
      } catch (error: Exception) {
        onResult(false, error.message ?: "تعذر حفظ الفاتورة")
      }
    }
  }
}
