package com.example.bridge

import android.app.Activity
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.room.withTransaction
import com.example.data.*
import com.example.utils.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class AndroidBridge(
  private val activity: Activity,
  private val webView: WebView
) {
  private val database = AppDatabase.getDatabase(activity)
  private val scope = CoroutineScope(Dispatchers.Main)

  private fun runJs(script: String) {
    activity.runOnUiThread {
      webView.evaluateJavascript(script, null)
    }
  }

  @JavascriptInterface
  fun getDashboardData() {
    scope.launch(Dispatchers.IO) {
      val customers = database.customerDao().getCustomersSnapshot()
      val suppliers = database.supplierDao().getSuppliersSnapshot()
      val inventory = database.inventoryDao().getItemsSnapshot()
      val invoices = database.invoiceDao().getInvoicesSnapshot()

      val totalDebts = customers.sumOf { maxOf(it.balance, 0.0) }
      val totalPayables = suppliers.sumOf { maxOf(it.balance, 0.0) }
      val inventoryVal = inventory.sumOf { it.quantity * it.purchasePrice }
      val todaySales = invoices.filter { it.type in setOf("SALE", "CASH", "CREDIT") }.sumOf { it.totalOutputOrTotal() }

      val json = JSONObject().apply {
        put("sales", todaySales)
        put("debts", totalDebts)
        put("payables", totalPayables)
        put("inventoryVal", inventoryVal)
      }
      runJs("onDashboardData('${json.toString().replace("'", "\\'")}')")
    }
  }

  @JavascriptInterface
  fun getCustomers() {
    scope.launch(Dispatchers.IO) {
      val list = database.customerDao().getCustomersSnapshot()
      val array = JSONArray()
      list.forEach { c ->
        array.put(JSONObject().apply {
          put("id", c.id)
          put("name", c.name)
          put("phone", c.phone)
          put("address", c.address)
          put("balance", c.balance)
        })
      }
      runJs("onCustomersData('${array.toString().replace("'", "\\'")}')")
    }
  }

  @JavascriptInterface
  fun getCustomerDetail(customerId: Long) {
    scope.launch(Dispatchers.IO) {
      val customer = database.customerDao().getCustomerById(customerId) ?: return@launch
      val json = JSONObject().apply {
        put("customer", JSONObject().apply {
          put("id", customer.id)
          put("name", customer.name)
          put("phone", customer.phone)
          put("address", customer.address)
          put("balance", customer.balance)
        })
      }
      runJs("onCustomerDetailData('${json.toString().replace("'", "\\'")}')")
    }
  }

  @JavascriptInterface
  fun addCustomerTransaction(customerId: Long, amount: Double, isDebit: Boolean, description: String) {
    scope.launch(Dispatchers.IO) {
      try {
        val customer = database.customerDao().getCustomerById(customerId) ?: return@launch
        val delta = if (isDebit) amount else -amount
        database.customerDao().updateBalance(customerId, delta)
        database.transactionDao().insertTransaction(
          TransactionEntity(
            partyType = "CUSTOMER",
            partyId = customerId,
            partyName = customer.name,
            type = if (isDebit) "DEBIT (عليه)" else "CREDIT (له)",
            amount = amount,
            description = description.ifBlank { "دفعة / معاملة" },
            date = System.currentTimeMillis()
          )
        )
        getDashboardData()
        getCustomerDetail(customerId)
      } catch (e: Exception) {
        e.printStackTrace()
      }
    }
  }

  @JavascriptInterface
  fun shareCustomerWhatsApp(customerId: Long) {
    scope.launch(Dispatchers.IO) {
      val customer = database.customerDao().getCustomerById(customerId) ?: return@launch
      val msg = "السلام عليكم ${customer.name}، رصيدكم الحالي لدى بقالة العزي هو: ${customer.balance} ر.ي."
      WhatsAppHelper.shareText(activity, customer.phone, msg)
    }
  }

  @JavascriptInterface
  fun getSuppliers() {
    scope.launch(Dispatchers.IO) {
      val list = database.supplierDao().getSuppliersSnapshot()
      val array = JSONArray()
      list.forEach { s ->
        array.put(JSONObject().apply {
          put("id", s.id)
          put("name", s.name)
          put("phone", s.phone)
          put("company", s.company)
          put("balance", s.balance)
        })
      }
      runJs("onSuppliersData('${array.toString().replace("'", "\\'")}')")
    }
  }

  @JavascriptInterface
  fun getInventory() {
    scope.launch(Dispatchers.IO) {
      val list = database.inventoryDao().getItemsSnapshot()
      val array = JSONArray()
      list.forEach { i ->
        array.put(JSONObject().apply {
          put("id", i.id)
          put("name", i.name)
          put("category", i.category)
          put("quantity", i.quantity)
          put("purchasePrice", i.purchasePrice)
          put("sellingPrice", i.sellingPrice)
          put("unit", i.unit)
        })
      }
      runJs("onInventoryData('${array.toString().replace("'", "\\'")}')")
    }
  }

  @JavascriptInterface
  fun getInvoices() {
    scope.launch(Dispatchers.IO) {
      val list = database.invoiceDao().getInvoicesSnapshot()
      val array = JSONArray()
      list.forEach { inv ->
        array.put(JSONObject().apply {
          put("id", inv.id)
          put("invoiceNumber", inv.invoiceNumber)
          put("type", inv.type)
          put("partyName", inv.partyName)
          put("totalAmount", inv.totalAmount)
          put("status", inv.status)
        })
      }
      runJs("onInvoicesData('${array.toString().replace("'", "\\'")}')")
    }
  }

  @JavascriptInterface
  fun getInvoiceDetails(invoiceId: Long) {
    scope.launch(Dispatchers.IO) {
      val withItems = database.invoiceDao().getInvoiceWithItems(invoiceId) ?: return@launch
      val json = JSONObject().apply {
        put("invoice", JSONObject().apply {
          put("id", withItems.invoice.id)
          put("invoiceNumber", withItems.invoice.invoiceNumber)
          put("type", withItems.invoice.type)
          put("partyId", withItems.invoice.partyId)
          put("partyName", withItems.invoice.partyName)
          put("paidAmount", withItems.invoice.paidAmount)
        })
        put("items", JSONArray().apply {
          withItems.items.forEach { item ->
            put(JSONObject().apply {
              put("itemId", item.itemId)
              put("itemName", item.itemName)
              put("quantity", item.quantity)
              put("unitPrice", item.unitPrice)
              put("totalPrice", item.totalPrice)
            })
          }
        })
      }
      runJs("onInvoiceLoadedForEdit('${json.toString().replace("'", "\\'")}')")
    }
  }

  @JavascriptInterface
  fun saveInvoice(jsonPayload: String) {
    scope.launch(Dispatchers.IO) {
      try {
        val obj = JSONObject(jsonPayload)
        val idOpt = obj.optLong("id", -1L)
        val invoiceNumber = obj.getString("invoiceNumber")
        val type = obj.getString("type")
        val partyId = obj.optLong("partyId", 0L)
        val partyName = obj.optString("partyName", "عميل نقدي")
        val paidAmount = obj.getDouble("paidAmount")
        val itemsArray = obj.getJSONArray("items")

        val items = mutableListOf<InvoiceItemEntity>()
        for (i in 0 until itemsArray.length()) {
          val itemObj = itemsArray.getJSONObject(i)
          items.add(
            InvoiceItemEntity(
              invoiceId = 0L,
              itemId = itemObj.getLong("itemId"),
              itemName = itemObj.getString("itemName"),
              quantity = itemObj.getInt("quantity"),
              unitPrice = itemObj.getDouble("unitPrice"),
              totalPrice = itemObj.getDouble("totalPrice")
            )
          )
        }

        val totalAmount = items.sumOf { it.totalPrice }
        val remainingAmount = totalAmount - paidAmount
        val status = if (remainingAmount <= 0) "PAID" else if (paidAmount > 0) "PARTIAL" else "UNPAID"

        database.withTransaction {
          if (idOpt > 0) {
            val previous = database.invoiceDao().getInvoiceWithItems(idOpt)
            if (previous != null) {
              val oldStockSign = if (previous.invoice.type == "PURCHASE") -1 else 1
              previous.items.forEach { database.inventoryDao().adjustQuantity(it.itemId, oldStockSign * it.quantity) }
              if (previous.invoice.remainingAmount > 0 && previous.invoice.partyId > 0) {
                if (previous.invoice.type == "PURCHASE") {
                  database.supplierDao().updateBalance(previous.invoice.partyId, -previous.invoice.remainingAmount)
                } else {
                  database.customerDao().updateBalance(previous.invoice.partyId, -previous.invoice.remainingAmount)
                }
              }
              database.invoiceDao().deleteInvoice(previous.invoice)
              database.invoiceDao().deleteItemsForInvoice(idOpt)
            }
          }

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
          val invoiceId = database.invoiceDao().insertInvoice(invoice)
          database.invoiceDao().insertItems(items.map { it.copy(invoiceId = invoiceId) })

          val stockDelta = if (type == "PURCHASE") 1 else -1
          items.forEach { database.inventoryDao().adjustQuantity(it.itemId, stockDelta * it.quantity) }

          if (remainingAmount > 0 && partyId > 0) {
            if (type == "PURCHASE") {
              database.supplierDao().updateBalance(partyId, remainingAmount)
            } else {
              database.customerDao().updateBalance(partyId, remainingAmount)
            }
          }
        }
        activity.runOnUiThread {
          Toast.makeText(activity, "تم حفظ الفاتورة بنجاح", Toast.LENGTH_SHORT).show()
          runJs("switchView('invoices')")
        }
      } catch (e: Exception) {
        e.printStackTrace()
        activity.runOnUiThread {
          Toast.makeText(activity, "خطأ في حفظ الفاتورة: ${e.message}", Toast.LENGTH_SHORT).show()
        }
      }
    }
  }

  @JavascriptInterface
  fun getReports() {
    scope.launch(Dispatchers.IO) {
      val invoices = database.invoiceDao().getInvoicesSnapshot()
      val customers = database.customerDao().getCustomersSnapshot()
      val inventory = database.inventoryDao().getItemsSnapshot()

      val sales = invoices.filter { it.type in setOf("SALE", "CASH", "CREDIT") }.sumOf { it.totalAmount }
      val purchases = invoices.filter { it.type == "PURCHASE" }.sumOf { it.totalAmount }
      val debts = customers.sumOf { maxOf(it.balance, 0.0) }
      val inventoryVal = inventory.sumOf { it.quantity * it.purchasePrice }

      val json = JSONObject().apply {
        put("sales", sales)
        put("purchases", purchases)
        put("debts", debts)
        put("inventoryVal", inventoryVal)
      }
      runJs("onReportsData('${json.toString().replace("'", "\\'")}')")
    }
  }

  @JavascriptInterface
  fun getBluetoothDevices() {
    scope.launch(Dispatchers.IO) {
      val devices = BluetoothPrinterManager.getPairedDevices()
      val array = JSONArray()
      devices.forEach { d ->
        array.put(JSONObject().apply {
          put("name", d.name ?: "جهاز بلوتوث")
          put("address", d.address)
        })
      }
      runJs("onBluetoothDevices('${array.toString().replace("'", "\\'")}')")
    }
  }

  @JavascriptInterface
  fun connectPrinter(address: String) {
    scope.launch(Dispatchers.IO) {
      val devices = BluetoothPrinterManager.getPairedDevices()
      val device = devices.firstOrNull { it.address == address }
      if (device != null) {
        val success = BluetoothPrinterManager.connect(device)
        activity.runOnUiThread {
          Toast.makeText(activity, if (success) "تم الاتصال بالطابعة" else "فشل الاتصال بالطابعة", Toast.LENGTH_SHORT).show()
        }
      }
    }
  }

  @JavascriptInterface
  fun printTestReceipt() {
    scope.launch(Dispatchers.IO) {
      val success = BluetoothPrinterManager.printReceiptText(
        title = "تجاعة طباعة حرارية",
        details = listOf("صنف تجريبي: 1 × 1000 = 1000"),
        footer = "الإجمالي: 1000 ر.ي"
      )
      activity.runOnUiThread {
        Toast.makeText(activity, if (success) "تمت الطباعة بنجاح" else "فشل الطباعة أو الطابعة غير متصلة", Toast.LENGTH_SHORT).show()
      }
    }
  }

  @JavascriptInterface
  fun exportBackup() {
    activity.runOnUiThread {
      Toast.makeText(activity, "النسخ الاحتياطي متاح عبر إعدادات التطبيق", Toast.LENGTH_SHORT).show()
    }
  }

  @JavascriptInterface
  fun importBackup() {
    activity.runOnUiThread {
      Toast.makeText(activity, "استعادة النسخ متاحة عبر إعدادات التطبيق", Toast.LENGTH_SHORT).show()
    }
  }
}

private fun InvoiceEntity.totalOutputOrTotal(): Double = totalAmount
