package com.example.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.example.data.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

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

      val cleanInvoiceNumber = invoiceNumber.trim()
      if (cleanInvoiceNumber.isBlank()) {
        return InvoiceValidationResult(false, "رقم الفاتورة مطلوب")
      }

      if (items.isEmpty()) {
        return InvoiceValidationResult(false, "أضف صنفاً واحداً على الأقل")
      }

      val invalidItem = items.firstOrNull { item ->
        item.itemName.trim().isBlank() ||
          item.itemId <= 0 ||
          item.quantity <= 0 ||
          !item.unitPrice.isFinite() ||
          item.unitPrice < 0 ||
          !item.totalPrice.isFinite() ||
          item.totalPrice < 0
      }
      if (invalidItem != null) {
        return InvoiceValidationResult(false, "تحقق من الأصناف والكميات والأسعار")
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
            val changedRows = inventoryDao.adjustQuantity(item.itemId, stockDeltaSign * item.quantity)
            check(changedRows == 1) {
              "الكمية غير متوفرة للصنف: ${item.itemName} أو أن الصنف غير موجود"
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
