package com.example.bridge

import android.app.Activity
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.example.data.*
import com.example.utils.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class AndroidBridge(
  private val activity: Activity,
  private val webView: WebView,
  private val onRequestBackupExport: () -> Unit = {},
  private val onRequestBackupImport: () -> Unit = {},
  private val onRequestLinkFolder: () -> Unit = {}
) {
  private val database = AppDatabase.getDatabase(activity)
  private val engine = GroceryAccountingEngine(database)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private val saveInvoiceLock = AtomicBoolean(false)

  private fun callJs(fnName: String, jsonPayload: String) {
    val quoted = JSONObject.quote(jsonPayload)
    activity.runOnUiThread {
      webView.evaluateJavascript("window.$fnName && window.$fnName($quoted);", null)
    }
  }

  private fun notifyResult(reqId: String, success: Boolean, message: String, dataJson: String? = null) {
    val qReq = JSONObject.quote(reqId)
    val qMsg = JSONObject.quote(message)
    val qData = if (dataJson != null) JSONObject.quote(dataJson) else "null"
    activity.runOnUiThread {
      webView.evaluateJavascript("window.onActionResult && window.onActionResult($qReq, $success, $qMsg, $qData);", null)
    }
  }

  private fun refreshCoreViews() {
    getDashboardData()
    getCustomers()
    getSuppliers()
    getInventory()
    getInvoices()
  }

  // ---------------- Dashboard ----------------

  @JavascriptInterface
  fun getDashboardData() {
    scope.launch(Dispatchers.IO) {
      val settings = StorePreferences.getSettings(activity)
      val customers = database.customerDao().getCustomersSnapshot()
      val suppliers = database.supplierDao().getSuppliersSnapshot()
      val inventory = database.inventoryDao().getItemsSnapshot()
      val invoices = database.invoiceDao().getInvoicesSnapshot()

      val startOfToday = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
      }.timeInMillis

      val todaySales = invoices
        .filter { it.date >= startOfToday && it.type in setOf("SALE", "CASH", "CREDIT") }
        .sumOf { it.totalAmount }
      val totalSales = invoices
        .filter { it.type in setOf("SALE", "CASH", "CREDIT") }
        .sumOf { it.totalAmount }
      val totalDebts = customers.sumOf { maxOf(it.balance, 0.0) }
      val totalPayables = suppliers.sumOf { maxOf(it.balance, 0.0) }
      val inventoryVal = inventory.sumOf { it.quantity.toDouble() * it.purchasePrice }
      val lowStockCount = inventory.count { it.quantity <= 10 }

      val recent = JSONArray().apply {
        invoices.take(6).forEach { inv ->
          put(JSONObject().apply {
            put("id", inv.id)
            put("invoiceNumber", inv.invoiceNumber)
            put("type", inv.type)
            put("partyName", inv.partyName)
            put("totalAmount", inv.totalAmount)
            put("paidAmount", inv.paidAmount)
            put("remainingAmount", inv.remainingAmount)
            put("status", inv.status)
            put("date", inv.date)
          })
        }
      }

      val json = JSONObject().apply {
        put("todaySales", todaySales)
        put("sales", todaySales)
        put("totalSales", totalSales)
        put("debts", totalDebts)
        put("payables", totalPayables)
        put("inventoryVal", inventoryVal)
        put("lowStockCount", lowStockCount)
        put("customersCount", customers.size)
        put("suppliersCount", suppliers.size)
        put("inventoryCount", inventory.size)
        put("invoicesCount", invoices.size)
        put("currency", settings.currency)
        put("storeName", settings.storeName)
        put("recentInvoices", recent)
      }
      callJs("onDashboardData", json.toString())
    }
  }

  // ---------------- Customers & Ledger ----------------

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
      callJs("onCustomersData", array.toString())
    }
  }

  @JavascriptInterface
  fun getCustomerDetail(customerId: Long) {
    scope.launch(Dispatchers.IO) {
      val detail = engine.getCustomerDetail(customerId) ?: return@launch
      val json = JSONObject().apply {
        put("customer", JSONObject().apply {
          put("id", detail.customer.id)
          put("name", detail.customer.name)
          put("phone", detail.customer.phone)
          put("address", detail.customer.address)
          put("balance", detail.customer.balance)
        })
        put("transactions", JSONArray().apply {
          detail.transactions.forEach { entry ->
            val t = entry.transaction
            put(JSONObject().apply {
              put("id", t.id)
              put("type", t.type)
              put("isDebit", t.type.startsWith("DEBIT"))
              put("amount", t.amount)
              put("description", t.description)
              put("date", t.date)
              put("runningBalance", entry.runningBalance)
            })
          }
        })
        put("invoices", JSONArray().apply {
          detail.invoices.forEach { inv ->
            put(JSONObject().apply {
              put("id", inv.id)
              put("invoiceNumber", inv.invoiceNumber)
              put("type", inv.type)
              put("totalAmount", inv.totalAmount)
              put("paidAmount", inv.paidAmount)
              put("remainingAmount", inv.remainingAmount)
              put("status", inv.status)
              put("date", inv.date)
            })
          }
        })
      }
      callJs("onCustomerDetailData", json.toString())
    }
  }

  @JavascriptInterface
  fun saveCustomer(jsonPayload: String, reqId: String) {
    scope.launch(Dispatchers.IO) {
      try {
        val obj = JSONObject(jsonPayload)
        val id = obj.optLong("id", 0L)
        val name = obj.optString("name", "")
        val phone = obj.optString("phone", "")
        val address = obj.optString("address", "")
        val balance = obj.optDouble("balance", 0.0)
        val savedId = engine.saveCustomer(id, name, phone, address, balance)
        refreshCoreViews()
        if (id > 0) getCustomerDetail(savedId)
        notifyResult(
          reqId,
          true,
          if (id > 0) "تم تحديث بيانات العميل بنجاح" else "تمت إضافة العميل بنجاح",
          JSONObject().put("id", savedId).toString()
        )
      } catch (e: Exception) {
        notifyResult(reqId, false, e.message ?: "تعذر حفظ بيانات العميل")
      }
    }
  }

  @JavascriptInterface
  fun deleteCustomer(customerId: Long, reqId: String) {
    scope.launch(Dispatchers.IO) {
      try {
        engine.deleteCustomer(customerId)
        refreshCoreViews()
        notifyResult(reqId, true, "تم حذف العميل بنجاح")
      } catch (e: Exception) {
        notifyResult(reqId, false, e.message ?: "تعذر حذف العميل")
      }
    }
  }

  @JavascriptInterface
  fun addCustomerTransaction(customerId: Long, amount: Double, isDebit: Boolean, description: String, reqId: String) {
    scope.launch(Dispatchers.IO) {
      try {
        engine.addCustomerTransaction(customerId, amount, isDebit, description)
        getCustomerDetail(customerId)
        refreshCoreViews()
        notifyResult(reqId, true, if (isDebit) "تم تسجيل مبلغ عليه ✓" else "تم تسجيل دفعة له ✓")
      } catch (e: Exception) {
        notifyResult(reqId, false, e.message ?: "تعذر تسجيل الحركة")
      }
    }
  }

  @JavascriptInterface
  fun updateCustomerTransaction(txId: Long, customerId: Long, amount: Double, isDebit: Boolean, description: String, reqId: String) {
    scope.launch(Dispatchers.IO) {
      try {
        engine.updateCustomerTransaction(txId, amount, isDebit, description)
        getCustomerDetail(customerId)
        refreshCoreViews()
        notifyResult(reqId, true, "تم تعديل الحركة وتحديث الرصيد ✓")
      } catch (e: Exception) {
        notifyResult(reqId, false, e.message ?: "تعذر تعديل الحركة")
      }
    }
  }

  @JavascriptInterface
  fun deleteCustomerTransaction(txId: Long, customerId: Long, reqId: String) {
    scope.launch(Dispatchers.IO) {
      try {
        engine.deleteCustomerTransaction(txId)
        getCustomerDetail(customerId)
        refreshCoreViews()
        notifyResult(reqId, true, "تم حذف الحركة وعكس أثرها على الرصيد ✓")
      } catch (e: Exception) {
        notifyResult(reqId, false, e.message ?: "تعذر حذف الحركة")
      }
    }
  }

  @JavascriptInterface
  fun shareCustomerWhatsApp(customerId: Long, reqId: String) {
    scope.launch(Dispatchers.IO) {
      try {
        val detail = engine.getCustomerDetail(customerId) ?: error("العميل غير موجود")
        val settings = StorePreferences.getSettings(activity)
        val c = detail.customer
        val balText = when {
          c.balance > 0.01 -> "عليه ${formatNum(c.balance)} ${settings.currency}"
          c.balance < -0.01 -> "له ${formatNum(-c.balance)} ${settings.currency}"
          else -> "مسدد بالكامل (0)"
        }
        val recentLines = detail.transactions.take(5).joinToString("\n") { entry ->
          val t = entry.transaction
          val sign = if (t.type.startsWith("DEBIT")) "عليه" else "له"
          "- $sign ${formatNum(t.amount)} ${settings.currency}: ${t.description}"
        }
        val msg = buildString {
          appendLine("${settings.storeName}")
          appendLine("كشف حساب العميل: ${c.name}")
          appendLine("الرصيد التراكمي الحالي: $balText")
          if (recentLines.isNotBlank()) {
            appendLine("--- آخر الحركات ---")
            appendLine(recentLines)
          }
          append("شكراً لتعاملكم معنا")
        }
        activity.runOnUiThread {
          WhatsAppHelper.shareText(activity, c.phone, msg)
        }
        notifyResult(reqId, true, "تم فتح تطبيق المشاركة ✓")
      } catch (e: Exception) {
        notifyResult(reqId, false, e.message ?: "تعذر مشاركة كشف الحساب")
      }
    }
  }

  // ---------------- Suppliers & Ledger ----------------

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
      callJs("onSuppliersData", array.toString())
    }
  }

  @JavascriptInterface
  fun getSupplierDetail(supplierId: Long) {
    scope.launch(Dispatchers.IO) {
      val detail = engine.getSupplierDetail(supplierId) ?: return@launch
      val json = JSONObject().apply {
        put("supplier", JSONObject().apply {
          put("id", detail.supplier.id)
          put("name", detail.supplier.name)
          put("phone", detail.supplier.phone)
          put("company", detail.supplier.company)
          put("balance", detail.supplier.balance)
        })
        put("transactions", JSONArray().apply {
          detail.transactions.forEach { entry ->
            val t = entry.transaction
            put(JSONObject().apply {
              put("id", t.id)
              put("type", t.type)
              put("isCredit", t.type.startsWith("CREDIT"))
              put("amount", t.amount)
              put("description", t.description)
              put("date", t.date)
              put("runningBalance", entry.runningBalance)
            })
          }
        })
        put("invoices", JSONArray().apply {
          detail.invoices.forEach { inv ->
            put(JSONObject().apply {
              put("id", inv.id)
              put("invoiceNumber", inv.invoiceNumber)
              put("type", inv.type)
              put("totalAmount", inv.totalAmount)
              put("paidAmount", inv.paidAmount)
              put("remainingAmount", inv.remainingAmount)
              put("status", inv.status)
              put("date", inv.date)
            })
          }
        })
      }
      callJs("onSupplierDetailData", json.toString())
    }
  }

  @JavascriptInterface
  fun saveSupplier(jsonPayload: String, reqId: String) {
    scope.launch(Dispatchers.IO) {
      try {
        val obj = JSONObject(jsonPayload)
        val id = obj.optLong("id", 0L)
        val name = obj.optString("name", "")
        val phone = obj.optString("phone", "")
        val company = obj.optString("company", "")
        val balance = obj.optDouble("balance", 0.0)
        val savedId = engine.saveSupplier(id, name, phone, company, balance)
        refreshCoreViews()
        if (id > 0) getSupplierDetail(savedId)
        notifyResult(
          reqId,
          true,
          if (id > 0) "تم تحديث بيانات المورد بنجاح" else "تمت إضافة المورد بنجاح",
          JSONObject().put("id", savedId).toString()
        )
      } catch (e: Exception) {
        notifyResult(reqId, false, e.message ?: "تعذر حفظ بيانات المورد")
      }
    }
  }

  @JavascriptInterface
  fun deleteSupplier(supplierId: Long, reqId: String) {
    scope.launch(Dispatchers.IO) {
      try {
        engine.deleteSupplier(supplierId)
        refreshCoreViews()
        notifyResult(reqId, true, "تم حذف المورد بنجاح")
      } catch (e: Exception) {
        notifyResult(reqId, false, e.message ?: "تعذر حذف المورد")
      }
    }
  }

  @JavascriptInterface
  fun addSupplierTransaction(supplierId: Long, amount: Double, isCredit: Boolean, description: String, reqId: String) {
    scope.launch(Dispatchers.IO) {
      try {
        engine.addSupplierTransaction(supplierId, amount, isCredit, description)
        getSupplierDetail(supplierId)
        refreshCoreViews()
        notifyResult(reqId, true, if (isCredit) "تم تسجيل مستحقات للمورد ✓" else "تم تسجيل دفعة سداد للمورد ✓")
      } catch (e: Exception) {
        notifyResult(reqId, false, e.message ?: "تعذر تسجيل حركة المورد")
      }
    }
  }

  @JavascriptInterface
  fun updateSupplierTransaction(txId: Long, supplierId: Long, amount: Double, isCredit: Boolean, description: String, reqId: String) {
    scope.launch(Dispatchers.IO) {
      try {
        engine.updateSupplierTransaction(txId, amount, isCredit, description)
        getSupplierDetail(supplierId)
        refreshCoreViews()
        notifyResult(reqId, true, "تم تعديل حركة المورد وتحديث الرصيد ✓")
      } catch (e: Exception) {
        notifyResult(reqId, false, e.message ?: "تعذر تعديل الحركة")
      }
    }
  }

  @JavascriptInterface
  fun deleteSupplierTransaction(txId: Long, supplierId: Long, reqId: String) {
    scope.launch(Dispatchers.IO) {
      try {
        engine.deleteSupplierTransaction(txId)
        getSupplierDetail(supplierId)
        refreshCoreViews()
        notifyResult(reqId, true, "تم حذف حركة المورد وعكس الرصيد ✓")
      } catch (e: Exception) {
        notifyResult(reqId, false, e.message ?: "تعذر حذف الحركة")
      }
    }
  }

  @JavascriptInterface
  fun shareSupplierWhatsApp(supplierId: Long, reqId: String) {
    scope.launch(Dispatchers.IO) {
      try {
        val detail = engine.getSupplierDetail(supplierId) ?: error("المورد غير موجود")
        val settings = StorePreferences.getSettings(activity)
        val s = detail.supplier
        val balText = when {
          s.balance > 0.01 -> "مستحق له ${formatNum(s.balance)} ${settings.currency}"
          s.balance < -0.01 -> "رصيد زائد لنا ${formatNum(-s.balance)} ${settings.currency}"
          else -> "الحساب مصفر (0)"
        }
        val msg = buildString {
          appendLine("${settings.storeName}")
          appendLine("كشف حساب المورد: ${s.name}")
          if (s.company.isNotBlank()) appendLine("الشركة: ${s.company}")
          appendLine("الرصيد الحالي: $balText")
        }
        activity.runOnUiThread {
          WhatsAppHelper.shareText(activity, s.phone, msg)
        }
        notifyResult(reqId, true, "تم فتح تطبيق المشاركة ✓")
      } catch (e: Exception) {
        notifyResult(reqId, false, e.message ?: "تعذر مشاركة كشف المورد")
      }
    }
  }

  // ---------------- Inventory ----------------

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
      callJs("onInventoryData", array.toString())
    }
  }

  @JavascriptInterface
  fun saveInventoryItem(jsonPayload: String, reqId: String) {
    scope.launch(Dispatchers.IO) {
      try {
        val obj = JSONObject(jsonPayload)
        val id = obj.optLong("id", 0L)
        val savedId = engine.saveInventoryItem(
          id = id,
          name = obj.optString("name", ""),
          category = obj.optString("category", "عام"),
          quantity = obj.optInt("quantity", 0),
          purchasePrice = obj.optDouble("purchasePrice", 0.0),
          sellingPrice = obj.optDouble("sellingPrice", 0.0),
          unit = obj.optString("unit", "حبة")
        )
        refreshCoreViews()
        notifyResult(
          reqId,
          true,
          if (id > 0) "تم تعديل الصنف بنجاح" else "تمت إضافة الصنف للمخزن بنجاح",
          JSONObject().apply {
            put("id", savedId)
            put("name", obj.optString("name", "").trim())
            put("quantity", obj.optInt("quantity", 0))
            put("purchasePrice", obj.optDouble("purchasePrice", 0.0))
            put("sellingPrice", obj.optDouble("sellingPrice", 0.0))
          }.toString()
        )
      } catch (e: Exception) {
        notifyResult(reqId, false, e.message ?: "تعذر حفظ الصنف")
      }
    }
  }

  @JavascriptInterface
  fun deleteInventoryItem(itemId: Long, reqId: String) {
    scope.launch(Dispatchers.IO) {
      try {
        engine.deleteInventoryItem(itemId)
        refreshCoreViews()
        notifyResult(reqId, true, "تم حذف الصنف من المخزن")
      } catch (e: Exception) {
        notifyResult(reqId, false, e.message ?: "تعذر حذف الصنف")
      }
    }
  }

  // ---------------- Invoices & Document Actions ----------------

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
          put("partyId", inv.partyId)
          put("partyName", inv.partyName)
          put("date", inv.date)
          put("totalAmount", inv.totalAmount)
          put("paidAmount", inv.paidAmount)
          put("remainingAmount", inv.remainingAmount)
          put("status", inv.status)
        })
      }
      callJs("onInvoicesData", array.toString())
    }
  }

  @JavascriptInterface
  fun getNextInvoiceNumber() {
    scope.launch(Dispatchers.IO) {
      val existing = database.invoiceDao().getInvoicesSnapshot()
      val maxId = existing.maxOfOrNull { it.id } ?: 0L
      val dayPrefix = SimpleDateFormat("yyMMdd", Locale.US).format(Date())
      var seq = maxId + 1
      var candidate = "AZ-$dayPrefix-${seq.toString().padStart(3, '0')}"
      while (database.invoiceDao().getInvoiceByNumber(candidate) != null) {
        seq++
        candidate = "AZ-$dayPrefix-${seq.toString().padStart(3, '0')}"
      }
      callJs("onNextInvoiceNumber", JSONObject().put("invoiceNumber", candidate).toString())
    }
  }

  @JavascriptInterface
  fun getInvoiceDetails(invoiceId: Long) {
    scope.launch(Dispatchers.IO) {
      val withItems = database.invoiceDao().getInvoiceWithItems(invoiceId) ?: return@launch
      val inv = withItems.invoice
      val partyBalance: Double? = if (inv.partyId > 0) {
        if (inv.type == "PURCHASE") database.supplierDao().getSupplierById(inv.partyId)?.balance
        else database.customerDao().getCustomerById(inv.partyId)?.balance
      } else null
      val partyPhone: String = if (inv.partyId > 0) {
        if (inv.type == "PURCHASE") database.supplierDao().getSupplierById(inv.partyId)?.phone ?: ""
        else database.customerDao().getCustomerById(inv.partyId)?.phone ?: ""
      } else ""
      val hasPdf = PdfGenerator.getSavedPdfFile(activity, inv.invoiceNumber) != null

      val json = JSONObject().apply {
        put("invoice", JSONObject().apply {
          put("id", inv.id)
          put("invoiceNumber", inv.invoiceNumber)
          put("type", inv.type)
          put("partyId", inv.partyId)
          put("partyName", inv.partyName)
          put("date", inv.date)
          put("totalAmount", inv.totalAmount)
          put("paidAmount", inv.paidAmount)
          put("remainingAmount", inv.remainingAmount)
          put("status", inv.status)
        })
        put("partyBalance", partyBalance ?: JSONObject.NULL)
        put("partyPhone", partyPhone)
        put("hasPdf", hasPdf)
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
      callJs("onInvoiceLoadedForEdit", json.toString())
    }
  }

  @JavascriptInterface
  fun saveInvoice(jsonPayload: String, reqId: String) {
    if (!saveInvoiceLock.compareAndSet(false, true)) {
      notifyResult(reqId, false, "جاري حفظ الفاتورة، الرجاء الانتظار...")
      return
    }
    scope.launch(Dispatchers.IO) {
      try {
        val obj = JSONObject(jsonPayload)
        val idOpt = obj.optLong("id", 0L)
        val invoiceNumber = obj.optString("invoiceNumber", "")
        val type = obj.optString("type", "CASH")
        val partyId = obj.optLong("partyId", 0L)
        val partyName = obj.optString("partyName", "عميل نقدي")
        val paidAmount = obj.optDouble("paidAmount", 0.0)
        val itemsArray = obj.optJSONArray("items") ?: JSONArray()

        val items = mutableListOf<InvoiceItemEntity>()
        for (i in 0 until itemsArray.length()) {
          val itemObj = itemsArray.getJSONObject(i)
          val qty = itemObj.optInt("quantity", 0)
          val unitPrice = itemObj.optDouble("unitPrice", -1.0)
          items.add(
            InvoiceItemEntity(
              invoiceId = 0L,
              itemId = itemObj.optLong("itemId", 0L),
              itemName = itemObj.optString("itemName", ""),
              quantity = qty,
              unitPrice = unitPrice,
              totalPrice = qty.toDouble() * unitPrice
            )
          )
        }

        val savedId = engine.saveInvoice(
          invoiceId = idOpt,
          invoiceNumber = invoiceNumber,
          type = type,
          partyId = partyId,
          partyName = partyName,
          rawItems = items,
          paidAmount = paidAmount
        )

        refreshCoreViews()
        getInvoiceDetails(savedId)
        notifyResult(
          reqId,
          true,
          if (idOpt > 0) "تم تعديل الفاتورة وتحديث الحساب والمخزون ✓" else "تم حفظ الفاتورة وتحديث الحساب والمخزون ✓",
          JSONObject().put("invoiceId", savedId).toString()
        )
      } catch (e: Exception) {
        notifyResult(reqId, false, e.message ?: "تعذر حفظ الفاتورة")
      } finally {
        saveInvoiceLock.set(false)
      }
    }
  }

  @JavascriptInterface
  fun deleteInvoice(invoiceId: Long, reqId: String) {
    scope.launch(Dispatchers.IO) {
      try {
        engine.deleteInvoice(invoiceId)
        refreshCoreViews()
        notifyResult(reqId, true, "تم حذف الفاتورة وعكس حركة المخزون والحساب ✓")
      } catch (e: Exception) {
        notifyResult(reqId, false, e.message ?: "تعذر حذف الفاتورة")
      }
    }
  }

  @JavascriptInterface
  fun previewThermalInvoice(invoiceId: Long, reqId: String) {
    scope.launch(Dispatchers.IO) {
      try {
        val withItems = database.invoiceDao().getInvoiceWithItems(invoiceId) ?: error("احفظ الفاتورة أولاً للمعاينة")
        val inv = withItems.invoice
        val balanceAfter = resolvePartyBalance(inv)
        val settings = StorePreferences.getSettings(activity)
        val base64 = BluetoothPrinterManager.renderInvoicePreviewBase64(
          context = activity,
          invoice = inv,
          items = withItems.items,
          partyBalanceAfter = balanceAfter,
          paperWidthMm = settings.printerWidthMm
        )
        val conn = BluetoothPrinterManager.getConnectionInfo()
        val payload = JSONObject().apply {
          put("invoiceId", inv.id)
          put("invoiceNumber", inv.invoiceNumber)
          put("widthMm", settings.printerWidthMm)
          put("connected", BluetoothPrinterManager.isConnected())
          put("printerName", conn.first)
          put("imageBase64", base64)
        }
        callJs("onThermalPreviewData", payload.toString())
        notifyResult(reqId, true, "تم تجهيز معاينة الإيصال الحراري")
      } catch (e: Exception) {
        notifyResult(reqId, false, e.message ?: "تعذر إنشاء معاينة الإيصال")
      }
    }
  }

  @JavascriptInterface
  fun printInvoice(invoiceId: Long, reqId: String) {
    scope.launch(Dispatchers.IO) {
      try {
        val withItems = database.invoiceDao().getInvoiceWithItems(invoiceId) ?: error("احفظ الفاتورة أولاً للطباعة")
        val inv = withItems.invoice
        val balanceAfter = resolvePartyBalance(inv)
        val settings = StorePreferences.getSettings(activity)
        val result = BluetoothPrinterManager.printInvoiceReceipt(
          context = activity,
          invoice = inv,
          items = withItems.items,
          partyBalanceAfter = balanceAfter,
          paperWidthMm = settings.printerWidthMm
        )
        notifyResult(reqId, result.first, result.second)
      } catch (e: Exception) {
        notifyResult(reqId, false, e.message ?: "فشلت الطباعة")
      }
    }
  }

  @JavascriptInterface
  fun generateAndOpenPdf(invoiceId: Long, reqId: String) {
    scope.launch(Dispatchers.IO) {
      try {
        val withItems = database.invoiceDao().getInvoiceWithItems(invoiceId) ?: error("احفظ الفاتورة أولاً لتصدير PDF")
        val inv = withItems.invoice
        val balanceAfter = resolvePartyBalance(inv)
        val file = PdfGenerator.generateInvoicePdf(activity, inv, withItems.items, balanceAfter)
          ?: error("تعذر إنشاء ملف PDF")
        val uri = PdfGenerator.getUriForFile(activity, file)
        activity.runOnUiThread {
          WhatsAppHelper.openPdf(activity, uri)
        }
        notifyResult(reqId, true, "تم حفظ وفتح الفاتورة بصيغة PDF ✓", JSONObject().put("hasPdf", true).toString())
      } catch (e: Exception) {
        notifyResult(reqId, false, e.message ?: "تعذر إنشاء PDF")
      }
    }
  }

  @JavascriptInterface
  fun shareInvoicePdf(invoiceId: Long, reqId: String) {
    scope.launch(Dispatchers.IO) {
      try {
        val withItems = database.invoiceDao().getInvoiceWithItems(invoiceId) ?: error("احفظ الفاتورة أولاً للمشاركة")
        val inv = withItems.invoice
        val balanceAfter = resolvePartyBalance(inv)
        val phone = resolvePartyPhone(inv)
        val file = PdfGenerator.generateInvoicePdf(activity, inv, withItems.items, balanceAfter)
          ?: error("تعذر إنشاء ملف PDF")
        val uri = PdfGenerator.getUriForFile(activity, file)
        val settings = StorePreferences.getSettings(activity)
        val msg = "${settings.storeName} - فاتورة رقم ${inv.invoiceNumber} بإجمالي ${formatNum(inv.totalAmount)} ${settings.currency}"
        activity.runOnUiThread {
          WhatsAppHelper.shareFile(activity, uri, "application/pdf", msg, phone, preferWhatsApp = true)
        }
        notifyResult(reqId, true, "تم حفظ الفاتورة PDF وجاهزة للمشاركة ✓", JSONObject().put("hasPdf", true).toString())
      } catch (e: Exception) {
        notifyResult(reqId, false, e.message ?: "تعذر مشاركة ملف PDF")
      }
    }
  }

  @JavascriptInterface
  fun shareInvoiceImage(invoiceId: Long, reqId: String) {
    scope.launch(Dispatchers.IO) {
      try {
        val withItems = database.invoiceDao().getInvoiceWithItems(invoiceId) ?: error("احفظ الفاتورة أولاً للمشاركة")
        val inv = withItems.invoice
        val balanceAfter = resolvePartyBalance(inv)
        val phone = resolvePartyPhone(inv)
        val file = InvoiceImageGenerator.generateInvoiceImage(activity, inv, withItems.items, balanceAfter)
          ?: error("تعذر إنشاء صورة الفاتورة")
        val uri = InvoiceImageGenerator.getUriForFile(activity, file)
        val settings = StorePreferences.getSettings(activity)
        val msg = "${settings.storeName} - فاتورة رقم ${inv.invoiceNumber} بإجمالي ${formatNum(inv.totalAmount)} ${settings.currency}"
        activity.runOnUiThread {
          WhatsAppHelper.shareFile(activity, uri, "image/png", msg, phone, preferWhatsApp = true)
        }
        notifyResult(reqId, true, "تم تجهيز صورة الفاتورة للمشاركة ✓")
      } catch (e: Exception) {
        notifyResult(reqId, false, e.message ?: "تعذر مشاركة صورة الفاتورة")
      }
    }
  }

  // ---------------- Reports ----------------

  @JavascriptInterface
  fun getReports(startDateMillis: Long, endDateMillis: Long) {
    scope.launch(Dispatchers.IO) {
      val effectiveStart = if (startDateMillis > 0) startDateMillis else 0L
      val effectiveEnd = if (endDateMillis > 0) endDateMillis else Long.MAX_VALUE
      val report = engine.buildReport(effectiveStart, effectiveEnd)

      val json = JSONObject().apply {
        put("todaySales", report.todaySales)
        put("todayPurchases", report.todayPurchases)
        put("sales", report.periodSales)
        put("purchases", report.periodPurchases)
        put("cashSales", report.cashSales)
        put("creditSales", report.creditSales)
        put("cogs", report.cogs)
        put("grossProfit", report.grossProfit)
        put("debts", report.totalDebts)
        put("payables", report.totalPayables)
        put("inventoryVal", report.inventoryCostValue)
        put("inventoryRetailVal", report.inventoryRetailValue)
        put("lowStockCount", report.lowStockCount)
        put("invoiceCount", report.invoiceCount)
        put("topItems", JSONArray().apply {
          report.topItems.forEach { item ->
            put(JSONObject().apply {
              put("name", item.itemName)
              put("quantity", item.totalQuantity)
              put("revenue", item.totalRevenue)
            })
          }
        })
        put("topDebtors", JSONArray().apply {
          report.topDebtors.forEach { c ->
            put(JSONObject().apply {
              put("id", c.id)
              put("name", c.name)
              put("balance", c.balance)
            })
          }
        })
        put("topPurchases", JSONArray().apply {
          report.topPurchases.forEach { inv ->
            put(JSONObject().apply {
              put("id", inv.id)
              put("invoiceNumber", inv.invoiceNumber)
              put("partyName", inv.partyName)
              put("totalAmount", inv.totalAmount)
              put("date", inv.date)
            })
          }
        })
      }
      callJs("onReportsData", json.toString())
    }
  }

  // ---------------- Settings, Bluetooth, Backup & Reset ----------------

  @JavascriptInterface
  fun getSettings() {
    scope.launch(Dispatchers.IO) {
      val settings = StorePreferences.getSettings(activity)
      val conn = BluetoothPrinterManager.getConnectionInfo()
      val json = JSONObject().apply {
        put("storeName", settings.storeName)
        put("storePhone", settings.storePhone)
        put("storeAddress", settings.storeAddress)
        put("currency", settings.currency)
        put("printerWidthMm", settings.printerWidthMm)
        put("defaultPrinterAddress", settings.defaultPrinterAddress)
        put("defaultPrinterName", settings.defaultPrinterName)
        put("printerConnected", BluetoothPrinterManager.isConnected())
        put("connectedPrinterName", conn.first)
        put("connectedPrinterAddress", conn.second)
        put("bluetoothAvailable", BluetoothPrinterManager.isBluetoothAvailable())
        put("bluetoothEnabled", BluetoothPrinterManager.isBluetoothEnabled())
      }
      callJs("onSettingsData", json.toString())
    }
  }

  @JavascriptInterface
  fun saveStoreSettings(jsonPayload: String, reqId: String) {
    scope.launch(Dispatchers.IO) {
      try {
        val obj = JSONObject(jsonPayload)
        StorePreferences.saveStoreInfo(
          context = activity,
          storeName = obj.optString("storeName", ""),
          storePhone = obj.optString("storePhone", ""),
          storeAddress = obj.optString("storeAddress", ""),
          currency = obj.optString("currency", "ر.ي")
        )
        getSettings()
        getDashboardData()
        notifyResult(reqId, true, "تم حفظ إعدادات المتجر ✓")
      } catch (e: Exception) {
        notifyResult(reqId, false, e.message ?: "تعذر حفظ الإعدادات")
      }
    }
  }

  @JavascriptInterface
  fun setPrinterWidth(widthMm: Int, reqId: String) {
    scope.launch(Dispatchers.IO) {
      StorePreferences.savePrinterWidth(activity, widthMm)
      getSettings()
      notifyResult(reqId, true, "تم ضبط عرض ورق الطباعة على ${if (widthMm == 80) 80 else 58} مم ✓")
    }
  }

  @JavascriptInterface
  fun getBluetoothDevices() {
    scope.launch(Dispatchers.IO) {
      val devices = BluetoothPrinterManager.getPairedDevices()
      val array = JSONArray()
      devices.forEach { d ->
        val name = try { d.name ?: "طابعة بلوتوث" } catch (_: SecurityException) { "طابعة بلوتوث" }
        array.put(JSONObject().apply {
          put("name", name)
          put("address", d.address)
        })
      }
      val payload = JSONObject().apply {
        put("available", BluetoothPrinterManager.isBluetoothAvailable())
        put("enabled", BluetoothPrinterManager.isBluetoothEnabled())
        put("connected", BluetoothPrinterManager.isConnected())
        put("connectedAddress", BluetoothPrinterManager.getConnectionInfo().second)
        put("connectedName", BluetoothPrinterManager.getConnectionInfo().first)
        put("devices", array)
      }
      callJs("onBluetoothDevices", payload.toString())
    }
  }

  @JavascriptInterface
  fun connectPrinter(address: String, reqId: String) {
    scope.launch(Dispatchers.IO) {
      val devices = BluetoothPrinterManager.getPairedDevices()
      val device = devices.firstOrNull { it.address == address }
      if (device == null) {
        notifyResult(reqId, false, "الطابعة المحددة غير مقترنة أو البلوتوث غير مفعل")
        getBluetoothDevices()
        return@launch
      }
      val (ok, msg) = BluetoothPrinterManager.connectWithResult(device)
      if (ok) {
        val name = try { device.name ?: "طابعة بلوتوث" } catch (_: SecurityException) { "طابعة بلوتوث" }
        StorePreferences.saveDefaultPrinter(activity, device.address, name)
      }
      getBluetoothDevices()
      getSettings()
      notifyResult(reqId, ok, msg)
    }
  }

  @JavascriptInterface
  fun disconnectPrinter(reqId: String) {
    scope.launch(Dispatchers.IO) {
      BluetoothPrinterManager.disconnect()
      getBluetoothDevices()
      getSettings()
      notifyResult(reqId, true, "تم فصل الطابعة الحرارية")
    }
  }

  @JavascriptInterface
  fun printTestReceipt(reqId: String) {
    scope.launch(Dispatchers.IO) {
      val settings = StorePreferences.getSettings(activity)
      val success = BluetoothPrinterManager.printReceiptText(
        title = "تجربة طباعة حرارية (${settings.printerWidthMm} مم)",
        details = listOf(
          "صنف تجريبي 1 × 500 = 500 ${settings.currency}",
          "صنف تجريبي 2 × 250 = 500 ${settings.currency}"
        ),
        footer = "الإجمالي: 1,000 ${settings.currency}",
        paperWidthMm = settings.printerWidthMm
      )
      notifyResult(
        reqId,
        success,
        if (success) "تمت طباعة الإيصال التجريبي بنجاح ✓" else "فشل الطباعة: تأكد من اتصال الطابعة الحرارية"
      )
    }
  }

  @JavascriptInterface
  fun exportBackup() {
    activity.runOnUiThread { onRequestBackupExport() }
  }

  @JavascriptInterface
  fun importBackup() {
    activity.runOnUiThread { onRequestBackupImport() }
  }

  @JavascriptInterface
  fun exportBackupToAppFolder(reqId: String) {
    scope.launch(Dispatchers.IO) {
      try {
        val result = BackupRestoreManager.exportToAppDownloadsFolder(activity)
        getAppFolderFiles()
        notifyResult(
          reqId,
          true,
          "تم حفظ النسخة الاحتياطية في ${result.displayPath} ✓"
        )
      } catch (e: Exception) {
        notifyResult(reqId, false, e.message ?: "تعذر حفظ النسخة الاحتياطية في مجلد بقاله العزي")
      }
    }
  }

  @JavascriptInterface
  fun linkAppFolder() {
    activity.runOnUiThread { onRequestLinkFolder() }
  }

  @JavascriptInterface
  fun unlinkAppFolder(reqId: String) {
    scope.launch(Dispatchers.IO) {
      StorePreferences.clearLinkedFolderUri(activity)
      getAppFolderFiles()
      notifyResult(reqId, true, "تم إلغاء ربط المجلد المخصص")
    }
  }

  @JavascriptInterface
  fun getAppFolderFiles() {
    scope.launch(Dispatchers.IO) {
      val snapshot = AppDownloadsFolderManager.listFiles(activity)
      val payload = JSONObject().apply {
        put("folderDisplayPath", snapshot.folderDisplayPath)
        put("linkedTreeActive", snapshot.linkedTreeActive)
        put("isPublicDownloadsApiSupported", snapshot.isPublicDownloadsApiSupported)
        put("files", JSONArray().apply {
          snapshot.files.forEach { f ->
            put(JSONObject().apply {
              put("id", f.id)
              put("uriString", f.uriString)
              put("displayName", f.displayName)
              put("category", f.category)
              put("subfolder", f.subfolder)
              put("mimeType", f.mimeType)
              put("sizeBytes", f.sizeBytes)
              put("modifiedMillis", f.modifiedMillis)
              put("source", f.source)
            })
          }
        })
      }
      callJs("onAppFolderFilesData", payload.toString())
    }
  }

  @JavascriptInterface
  fun openAppFolderFile(uriString: String, mimeType: String, reqId: String) {
    activity.runOnUiThread {
      val ok = AppDownloadsFolderManager.openFile(activity, uriString, mimeType)
      if (!ok) {
        notifyResult(reqId, false, "لا يوجد تطبيق مناسب لفتح هذا الملف على جهازك")
      }
    }
  }

  @JavascriptInterface
  fun shareAppFolderFile(uriString: String, mimeType: String, title: String, reqId: String) {
    activity.runOnUiThread {
      val ok = AppDownloadsFolderManager.shareFile(activity, uriString, mimeType, title)
      if (!ok) {
        notifyResult(reqId, false, "تعذر مشاركة الملف المحدد")
      }
    }
  }

  @JavascriptInterface
  fun deleteAppFolderFile(uriString: String, reqId: String) {
    scope.launch(Dispatchers.IO) {
      val ok = AppDownloadsFolderManager.deleteFile(activity, uriString)
      getAppFolderFiles()
      notifyResult(
        reqId,
        ok,
        if (ok) "تم حذف الملف من مجلد بقاله العزي" else "تعذر حذف الملف المحدد"
      )
    }
  }

  @JavascriptInterface
  fun restoreBackupFromAppFolder(uriString: String, reqId: String) {
    scope.launch(Dispatchers.IO) {
      try {
        val msg = BackupRestoreManager.restoreFromUri(activity, Uri.parse(uriString))
        refreshCoreViews()
        getAppFolderFiles()
        notifyResult(reqId, true, msg)
      } catch (e: Exception) {
        notifyResult(reqId, false, e.message ?: "فشلت استعادة النسخة الاحتياطية")
      }
    }
  }

  @JavascriptInterface
  fun saveThermalPreviewToAppFolder(invoiceId: Long, reqId: String) {
    scope.launch(Dispatchers.IO) {
      try {
        val withItems = database.invoiceDao().getInvoiceWithItems(invoiceId) ?: error("احفظ الفاتورة أولاً")
        val inv = withItems.invoice
        val balanceAfter = resolvePartyBalance(inv)
        val settings = StorePreferences.getSettings(activity)
        val saved = BluetoothPrinterManager.saveThermalReceiptImageToDownloads(
          context = activity,
          invoice = inv,
          items = withItems.items,
          partyBalanceAfter = balanceAfter,
          paperWidthMm = settings.printerWidthMm
        )
        getAppFolderFiles()
        notifyResult(reqId, true, "تم حفظ صورة الإيصال في ${saved.displayPath} ✓")
      } catch (e: Exception) {
        notifyResult(reqId, false, e.message ?: "تعذر حفظ صورة الإيصال")
      }
    }
  }

  @JavascriptInterface
  fun exportFinancialReportPdf(startDateMillis: Long, endDateMillis: Long, periodLabel: String, reqId: String) {
    scope.launch(Dispatchers.IO) {
      try {
        val effectiveStart = if (startDateMillis > 0) startDateMillis else 0L
        val effectiveEnd = if (endDateMillis > 0) endDateMillis else Long.MAX_VALUE
        val report = engine.buildReport(effectiveStart, effectiveEnd)
        val result = PdfGenerator.generateFinancialReportPdf(activity, report, periodLabel.ifBlank { "الكل" })
          ?: error("تعذر إنشاء ملف التقرير PDF")
        val uri = PdfGenerator.getUriForFile(activity, result.first)
        activity.runOnUiThread {
          WhatsAppHelper.openPdf(activity, uri)
        }
        getAppFolderFiles()
        notifyResult(reqId, true, "تم حفظ التقرير المالي في ${result.second.displayPath} ✓")
      } catch (e: Exception) {
        notifyResult(reqId, false, e.message ?: "تعذر تصدير التقرير PDF")
      }
    }
  }

  @JavascriptInterface
  fun resetAppDatabase(reqId: String) {
    scope.launch(Dispatchers.IO) {
      try {
        engine.resetAllDatabase()
        refreshCoreViews()
        notifyResult(reqId, true, "تم تصفير جميع بيانات التطبيق بنجاح")
      } catch (e: Exception) {
        notifyResult(reqId, false, e.message ?: "تعذر تصفير البيانات")
      }
    }
  }

  fun onBackupOperationComplete(success: Boolean, message: String) {
    if (success) refreshCoreViews()
    getAppFolderFiles()
    notifyResult("backup_op", success, message)
  }

  fun onFolderLinkResult(success: Boolean, message: String) {
    getAppFolderFiles()
    notifyResult("folder_link_op", success, message)
  }

  // ---------------- Helpers ----------------

  private suspend fun resolvePartyBalance(invoice: InvoiceEntity): Double? {
    if (invoice.partyId <= 0) return null
    return if (invoice.type.equals("PURCHASE", true)) {
      database.supplierDao().getSupplierById(invoice.partyId)?.balance
    } else {
      database.customerDao().getCustomerById(invoice.partyId)?.balance
    }
  }

  private suspend fun resolvePartyPhone(invoice: InvoiceEntity): String {
    if (invoice.partyId <= 0) return ""
    return if (invoice.type.equals("PURCHASE", true)) {
      database.supplierDao().getSupplierById(invoice.partyId)?.phone ?: ""
    } else {
      database.customerDao().getCustomerById(invoice.partyId)?.phone ?: ""
    }
  }

  private fun formatNum(value: Double): String {
    return if (value % 1.0 == 0.0) {
      String.format(Locale.US, "%,.0f", value)
    } else {
      String.format(Locale.US, "%,.2f", value)
    }
  }
}
