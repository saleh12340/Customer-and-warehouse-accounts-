package com.example.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
      val delta = if (isDebit) amount else -amount
      customerDao.updateBalance(customerId, delta)
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
      inventoryDao.insertItem(InventoryItemEntity(name = name, category = category, quantity = quantity, purchasePrice = purchasePrice, sellingPrice = sellingPrice, unit = unit))
    }
  }

  fun updateInventoryItem(item: InventoryItemEntity) {
    viewModelScope.launch { inventoryDao.updateItem(item) }
  }

  fun deleteInventoryItem(item: InventoryItemEntity) {
    viewModelScope.launch { inventoryDao.deleteItem(item) }
  }

  // Invoice creation with item tracking & balance updates
  fun createInvoice(
    invoiceNumber: String,
    type: String, // SALE, PURCHASE, CASH, CREDIT
    partyId: Long,
    partyName: String,
    items: List<InvoiceItemEntity>,
    paidAmount: Double
  ) {
    viewModelScope.launch {
      val totalAmount = items.sumOf { it.totalPrice }
      val remainingAmount = totalAmount - paidAmount
      val status = if (remainingAmount <= 0) "PAID" else if (paidAmount > 0) "PARTIAL" else "UNPAID"

      val invoice = InvoiceEntity(
        invoiceNumber = invoiceNumber,
        type = type,
        partyId = partyId,
        partyName = partyName,
        date = System.currentTimeMillis(),
        totalAmount = totalAmount,
        paidAmount = paidAmount,
        remainingAmount = remainingAmount,
        status = status
      )

      val invoiceId = invoiceDao.insertInvoice(invoice)
      val itemsWithId = items.map { it.copy(invoiceId = invoiceId) }
      invoiceDao.insertItems(itemsWithId)

      // Adjust inventory quantities and customer/supplier balances
      for (item in items) {
        val delta = if (type == "SALE" || type == "CASH") -item.quantity else item.quantity
        inventoryDao.adjustQuantity(item.itemId, delta)
      }

      if (type == "SALE" || type == "CREDIT") {
        if (partyId > 0 && remainingAmount > 0) {
          customerDao.updateBalance(partyId, remainingAmount)
          transactionDao.insertTransaction(
            TransactionEntity(
              partyType = "CUSTOMER",
              partyId = partyId,
              partyName = partyName,
              type = "DEBIT (عليه)",
              amount = remainingAmount,
              description = "فاتورة مبيعات رقم $invoiceNumber",
              date = System.currentTimeMillis()
            )
          )
        }
      } else if (type == "PURCHASE") {
        if (partyId > 0 && remainingAmount > 0) {
          supplierDao.updateBalance(partyId, remainingAmount)
        }
      }
    }
  }
}
