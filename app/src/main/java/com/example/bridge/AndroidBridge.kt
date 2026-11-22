package com.example.bridge

import android.app.Activity
import android.graphics.Bitmap
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class AndroidBridge(
  private val activity: Activity,
  private val webView: WebView,
  private val onRequestBackupExport: () -> Unit = {},
  private val onRequestBackupImport: () -> Unit = {},
  private val onRequestLinkFolder: () -> Unit = {},
  private val onRequestPickContact: () -> Unit = {}
) {
  private val database = AppDatabase.getDatabase(activity)
  private val engine = GroceryAccountingEngine(database)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private val saveInvoiceLock = AtomicBoolean(false)
  private val previewedInvoiceSignatures = ConcurrentHashMap.newKeySet<String>()
  private val verifiedThermalSignatures = ConcurrentHashMap.newKeySet<String>()

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
    getNotes()
  }

  @JavascriptInterface
  fun pickCustomerContact() {
    activity.runOnUiThread { onRequestPickContact() }
  }

  /** Called only after the user explicitly selects one phone contact in Android's picker. */
  fun onContactPicked(name: String, phone: String) {
    callJs(
      "onCustomerContactPicked",
      JSONObject().put("name", name).put("phone", phone).toString()
    )
  }


  // Activity timeline is derived from existing persisted records; no database migration or duplicate events.
  @JavascriptInterface
  fun getActivityLog() {
    scope.launch(Dispatchers.IO) {
      try {
        data class ActivityRow(val date: Long, val priority: Int, val json: JSONObject)
        val combined = mutableListOf<ActivityRow>()
        database.invoiceDao().getInvoicesSnapshot().forEach { inv ->
          val purchase = inv.type == "PURCHASE"
          combined += ActivityRow(inv.date, 0, JSONObject().apply {
            put("kind", "INVOICE"); put("id", inv.id); put("recordId", inv.id); put("partyId", inv.partyId)
            put("partyType", if (purchase) "SUPPLIER" else "CUSTOMER")
            put("invoiceType", inv.type); put("invoiceNumber", inv.invoiceNumber)
            put("title", (if (purchase) "فاتورة شراء #" else "فاتورة بيع #") + inv.invoiceNumber)
            put("subtitle", inv.partyName + " • " + inv.status); put("partyName", inv.partyName)
            put("amount", inv.totalAmount); put("date", inv.date)
          })
        }
        database.transactionDao().getTransactionsSnapshot().filter { it.invoiceId == null }.forEach { tx ->
          val customer = tx.partyType == "CUSTOMER"
          val debit = tx.type.startsWith("DEBIT")
          val label = if (customer) { if (debit) "دين على العميل" else "دفعة من العميل" }
                      else { if (debit) "دفعة للمورد" else "مستحق للمورد" }
          combined += ActivityRow(tx.date, 1, JSONObject().apply {
            put("kind", if (customer) "CUSTOMER_TX" else "SUPPLIER_TX"); put("id", tx.id); put("recordId", tx.id)
            put("partyId", tx.partyId); put("partyType", tx.partyType); put("title", label)
            put("subtitle", tx.partyName + " • " + tx.description); put("description", tx.description)
            put("partyName", tx.partyName); put("amount", tx.amount); put("date", tx.date)
          })
        }
        database.inventoryAdjustmentDao().getAllAdjustmentsSnapshot().forEach { adj ->
          combined += ActivityRow(adj.date, 2, JSONObject().apply {
            put("kind", "STOCK"); put("id", adj.id); put("recordId", adj.id)
            put("adjustmentId", adj.id); put("itemId", adj.itemId); put("title", "تسوية مخزون: " + adj.itemName)
            put("subtitle", adj.reason + " • الكمية: " + adj.previousQuantity + " ← " + adj.newQuantity)
            put("description", adj.reason); put("amount", 0); put("date", adj.date)
          })
        }
        val payload = JSONArray()
        combined.sortedWith(compareByDescending<ActivityRow> { it.date }.thenByDescending { it.priority })
          .take(500).forEach { payload.put(it.json) }
        callJs("onActivityLogData", payload.toString())
      } catch (e: Exception) { callJs("onActivityLogData", "[]") }
    }
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
      val lowStockCount = inventory.count { it.quantity <= settings.lowStockThreshold }

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
        put("lowStockThreshold", settings.lowStockThreshold)
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
              put("invoiceId", t.invoiceId ?: 0L)
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
    addCustomerTransactionWithDate(customerId, amount, isDebit, description, 0L, reqId)
  }

  @JavascriptInterface
  fun addCustomerTransactionWithDate(
    customerId: Long,
    amount: Double,
    isDebit: Boolean,
    description: String,
    dateMillis: Long,
    reqId: String
  ) {
    scope.launch(Dispatchers.IO) {
      try {
        val effectiveDate = if (dateMillis > 0L) dateMillis else System.currentTimeMillis()
        engine.addCustomerTransaction(customerId, amount, isDebit, description, effectiveDate)
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
    updateCustomerTransactionWithDate(txId, customerId, amount, isDebit, description, 0L, reqId)
  }

  @JavascriptInterface
  fun updateCustomerTransactionWithDate(
    txId: Long,
    customerId: Long,
    amount: Double,
    isDebit: Boolean,
    description: String,
    dateMillis: Long,
    reqId: String
  ) {
    scope.launch(Dispatchers.IO) {
      try {
        val effectiveDate = dateMillis.takeIf { it > 0L }
        engine.updateCustomerTransaction(txId, amount, isDebit, description, effectiveDate)
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
  fun shareTransactionWhatsApp(txId: Long, reqId: String) {
    scope.launch(Dispatchers.IO) {
      try {
        val snap = engine.getTransactionReceiptSnapshot(txId) ?: error("العملية غير موجودة")
        val tx = snap.transaction
        val settings = StorePreferences.getSettings(activity)
        val isCustomer = tx.partyType == "CUSTOMER"
        val isDebit = tx.type.startsWith("DEBIT")
        val action = when {
          isCustomer && isDebit -> "دين على العميل"
          isCustomer -> "دفعة من العميل"
          isDebit -> "دفعة للمورد"
          else -> "مستحق للمورد"
        }
        val previous = if (snap.previousBalance > 0.01) "عليه ${formatNum(snap.previousBalance)}"
          else if (snap.previousBalance < -0.01) "له ${formatNum(-snap.previousBalance)}" else "رصيد صفر"
        val current = if (snap.currentBalance > 0.01) "عليه ${formatNum(snap.currentBalance)}"
          else if (snap.currentBalance < -0.01) "له ${formatNum(-snap.currentBalance)}" else "رصيد صفر"
        val dateText = SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale.getDefault()).format(Date(tx.date))
        val msg = buildString {
          appendLine(settings.storeName)
          appendLine("سند حركة حساب")
          appendLine("الطرف: ${tx.partyName}")
          appendLine("نوع الحركة: $action")
          appendLine("المبلغ: ${formatNum(tx.amount)} ${settings.currency}")
          appendLine("البيان: ${tx.description}")
          appendLine("التاريخ: $dateText")
          appendLine("الرصيد قبل الحركة: $previous ${settings.currency}")
          append("الرصيد بعد الحركة: $current ${settings.currency}")
        }
        activity.runOnUiThread {
          val ok = WhatsAppHelper.shareText(activity, snap.partyPhone, msg)
          notifyResult(reqId, ok, if (ok) "تم فتح مشاركة سند الحركة ✓" else "تعذر فتح مشاركة سند الحركة")
        }
      } catch (e: Exception) {
        notifyResult(reqId, false, e.message ?: "تعذرت مشاركة الحركة المحددة")
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
          val ok = WhatsAppHelper.shareText(activity, c.phone, msg)
          notifyResult(reqId, ok, if (ok) "تم فتح تطبيق المشاركة ✓" else "تعذر فتح تطبيق المشاركة")
        }
      } catch (e: Exception) {
        notifyResult(reqId, false, e.message ?: "تعذر مشاركة كشف الحساب")
      }
    }
  }

  // ---------------- Two-panel Notes ----------------

  @JavascriptInterface
  fun getNotes() {
    scope.launch(Dispatchers.IO) {
      val notes = database.noteDao().getNotesSnapshot()
      val payload = JSONArray().apply {
        notes.forEach { note ->
          put(JSONObject().apply {
            put("id", note.id)
            put("createdAt", note.createdAt)
            put("updatedAt", note.updatedAt)
            put("rightRows", JSONArray(note.rightRowsJson))
            put("leftRows", JSONArray(note.leftRowsJson))
          })
        }
      }
      callJs("onNotesData", payload.toString())
    }
  }

  @JavascriptInterface
  fun saveNote(jsonPayload: String, reqId: String) {
    scope.launch(Dispatchers.IO) {
      val obj = try { JSONObject(jsonPayload) } catch (e: Exception) {
        notifyResult(reqId, false, "بيانات الملاحظة غير صالحة")
        return@launch
      }
      val revision = obj.optLong("revision", -1L)
      val session = obj.optLong("session", -1L)
      val manual = obj.optBoolean("manual", false)
      try {
        val id = obj.optLong("id", 0L)
        val rightRows = obj.optJSONArray("rightRows") ?: JSONArray()
        val leftRows = obj.optJSONArray("leftRows") ?: JSONArray()
        fun hasContent(rows: JSONArray): Boolean = (0 until rows.length()).any { index ->
          val row = rows.optJSONObject(index) ?: JSONObject()
          row.optString("qty").isNotBlank() || row.optString("detail").isNotBlank()
        }
        require(rightRows.length() <= 500 && leftRows.length() <= 500) { "عدد صفوف الملاحظة تجاوز الحد المسموح" }
        val old = if (id > 0L) database.noteDao().getNoteById(id) else null
        require(hasContent(rightRows) || hasContent(leftRows) || old != null) {
          "أدخل بيانات في أحد شقي الملاحظة على الأقل"
        }
        val now = System.currentTimeMillis()
        val note = NoteEntity(
          id = old?.id ?: 0L,
          createdAt = old?.createdAt ?: now,
          updatedAt = now,
          rightRowsJson = rightRows.toString(),
          leftRowsJson = leftRows.toString()
        )
        val savedId = if (old == null) database.noteDao().insertNote(note) else {
          database.noteDao().updateNote(note)
          old.id
        }
        callJs(
          "onNoteSaved",
          JSONObject().put("id", savedId).put("revision", revision).put("session", session).put("manual", manual).toString()
        )
        getNotes()
        if (manual) notifyResult(reqId, true, "تم حفظ الملاحظة بنجاح ✓")
      } catch (e: Exception) {
        callJs(
          "onNoteSaveFailed",
          JSONObject().put("revision", revision).put("session", session)
            .put("message", e.message ?: "تعذر حفظ الملاحظة").toString()
        )
        if (manual) notifyResult(reqId, false, e.message ?: "تعذر حفظ الملاحظة")
      }
    }
  }

  @JavascriptInterface
  fun deleteNote(noteId: Long, reqId: String) {
    scope.launch(Dispatchers.IO) {
      try {
        database.noteDao().deleteNoteById(noteId)
        getNotes()
        notifyResult(reqId, true, "تم حذف الملاحظة")
      } catch (e: Exception) {
        notifyResult(reqId, false, e.message ?: "تعذر حذف الملاحظة")
      }
    }
  }

  @JavascriptInterface
  fun exportNotePdf(noteId: Long, reqId: String) {
    scope.launch(Dispatchers.IO) {
      try {
        val note = database.noteDao().getNoteById(noteId) ?: error("الملاحظة غير موجودة")
        val file = NotePdfGenerator.generate(activity, note)
        val saved = AppDownloadsFolderManager.saveFile(
          context = activity,
          subfolder = AppDownloadsFolderManager.SUB_PDFS,
          sourceFile = file,
          displayName = "note_${note.id}.pdf",
          mimeType = "application/pdf",
          overwriteExisting = true
        )
        val uri = PdfGenerator.getUriForFile(activity, file)
        val settings = StorePreferences.getSettings(activity)
        activity.runOnUiThread {
          val opened = if (settings.autoOpenPdfAfterExport) WhatsAppHelper.openPdf(activity, uri) else false
          notifyResult(reqId, true, if (opened) "تم حفظ وفتح ملف الملاحظة PDF ✓" else "تم حفظ ملف الملاحظة في ${saved?.displayPath ?: "مجلد ملفات التطبيق"} ✓")
        }
      } catch (e: Exception) {
        notifyResult(reqId, false, e.message ?: "تعذر إنشاء ملف الملاحظة PDF")
      }
    }
  }

  @JavascriptInterface
  fun shareNoteWhatsApp(noteId: Long, reqId: String) {
    scope.launch(Dispatchers.IO) {
      try {
        val note = database.noteDao().getNoteById(noteId) ?: error("احفظ الملاحظة أولاً")
        val file = NotePdfGenerator.generate(activity, note)
        val saved = AppDownloadsFolderManager.saveFile(
          context = activity,
          subfolder = AppDownloadsFolderManager.SUB_PDFS,
          sourceFile = file,
          displayName = "note_${note.id}.pdf",
          mimeType = "application/pdf",
          overwriteExisting = true
        )
        val uri = PdfGenerator.getUriForFile(activity, file)
        val settings = StorePreferences.getSettings(activity)
        activity.runOnUiThread {
          val ok = WhatsAppHelper.shareFile(
            context = activity,
            fileUri = uri,
            mimeType = "application/pdf",
            message = "ملاحظة من ${settings.storeName}",
            preferWhatsApp = settings.preferWhatsAppDirect
          )
          notifyResult(reqId, ok, if (ok) "تم فتح مشاركة الملاحظة ✓" else "تعذر فتح المشاركة؛ تم حفظ الملف في ${saved?.displayPath ?: "مجلد ملفات التطبيق"}")
        }
      } catch (e: Exception) {
        notifyResult(reqId, false, e.message ?: "تعذرت مشاركة الملاحظة")
      }
    }
  }

  @JavascriptInterface
  fun exportNoteImageHd(noteId: Long, reqId: String) {
    scope.launch(Dispatchers.IO) {
      try {
        val note = database.noteDao().getNoteById(noteId) ?: error("الملاحظة غير موجودة")
        val file = NoteImageGenerator.generate(activity, note)
        val saved = AppDownloadsFolderManager.saveFile(
          context = activity,
          subfolder = AppDownloadsFolderManager.SUB_IMAGES,
          sourceFile = file,
          displayName = "note_${note.id}_HD.png",
          mimeType = "image/png",
          overwriteExisting = true
        )
        notifyResult(reqId, true, "تم حفظ صورة الملاحظة HD بدقة عالية في ${saved?.displayPath ?: "مجلد الصور"} ✓")
      } catch (e: Exception) {
        notifyResult(reqId, false, e.message ?: "تعذر إنشاء صورة الملاحظة HD")
      }
    }
  }

  @JavascriptInterface
  fun shareNoteImageWhatsApp(noteId: Long, reqId: String) {
    scope.launch(Dispatchers.IO) {
      try {
        val note = database.noteDao().getNoteById(noteId) ?: error("احفظ الملاحظة أولاً")
        val file = NoteImageGenerator.generate(activity, note)
        val saved = AppDownloadsFolderManager.saveFile(
          context = activity,
          subfolder = AppDownloadsFolderManager.SUB_IMAGES,
          sourceFile = file,
          displayName = "note_${note.id}_HD.png",
          mimeType = "image/png",
          overwriteExisting = true
        )
        val uri = PdfGenerator.getUriForFile(activity, file)
        val settings = StorePreferences.getSettings(activity)
        activity.runOnUiThread {
          val ok = WhatsAppHelper.shareFile(
            context = activity,
            fileUri = uri,
            mimeType = "application/octet-stream",
            message = "صورة ملاحظة من ${settings.storeName} - HD",
            preferWhatsApp = settings.preferWhatsAppDirect
          )
          notifyResult(reqId, ok, if (ok) "تم فتح مشاركة صورة الملاحظة HD ✓" else "تعذرت المشاركة؛ تم حفظ الصورة في ${saved?.displayPath ?: "مجلد الصور"}")
        }
      } catch (e: Exception) {
        notifyResult(reqId, false, e.message ?: "تعذرت مشاركة صورة الملاحظة")
      }
    }
  }

  @JavascriptInterface
  fun shareNoteThermalImageWhatsApp(noteId: Long, reqId: String) {
    scope.launch(Dispatchers.IO) {
      try {
        val note = database.noteDao().getNoteById(noteId) ?: error("احفظ الملاحظة أولاً")
        val settings = StorePreferences.getSettings(activity)
        val bitmap = BluetoothPrinterManager.buildNoteReceiptBitmap(
          activity, note, settings.printerWidthMm, renderScale = 3
        )
        val file = java.io.File(activity.cacheDir, "thermal_note_${note.id}_HD.png")
        java.io.FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val saved = AppDownloadsFolderManager.saveFile(
          context = activity,
          subfolder = AppDownloadsFolderManager.SUB_IMAGES,
          sourceFile = file,
          displayName = "thermal_note_${note.id}_HD.png",
          mimeType = "image/png",
          overwriteExisting = true
        )
        val uri = PdfGenerator.getUriForFile(activity, file)
        activity.runOnUiThread {
          val ok = WhatsAppHelper.shareFile(
            context = activity,
            fileUri = uri,
            mimeType = "application/octet-stream",
            message = "صورة حرارية للملاحظة من ${settings.storeName} - HD",
            preferWhatsApp = settings.preferWhatsAppDirect
          )
          notifyResult(reqId, ok, if (ok) "تم فتح مشاركة صورة الملاحظة الحرارية HD ✓" else "تعذرت المشاركة؛ تم حفظ الصورة في ${saved?.displayPath ?: "مجلد الصور"}")
        }
      } catch (e: Exception) {
        notifyResult(reqId, false, e.message ?: "تعذرت مشاركة صورة الملاحظة الحرارية")
      }
    }
  }

  @JavascriptInterface
  fun previewNoteThermalDraft(noteJson: String, reqId: String) {
    scope.launch(Dispatchers.IO) {
      try {
        val obj = JSONObject(noteJson)
        val id = obj.optLong("id", 0L)
        val rightRows = obj.optJSONArray("rightRows") ?: JSONArray()
        val leftRows = obj.optJSONArray("leftRows") ?: JSONArray()
        val updatedAt = obj.optLong("updatedAt", System.currentTimeMillis()).takeIf { it > 0L } ?: System.currentTimeMillis()
        val draftNote = NoteEntity(
          id = id,
          createdAt = updatedAt,
          updatedAt = updatedAt,
          rightRowsJson = rightRows.toString(),
          leftRowsJson = leftRows.toString()
        )
        val settings = StorePreferences.getSettings(activity)
        val widthMm = settings.printerWidthMm
        val bmp = BluetoothPrinterManager.buildNoteReceiptBitmap(activity, draftNote, widthMm)
        val base64 = BluetoothPrinterManager.renderBitmapToBase64(bmp)
        val sig = "NOTE_${id}_${rightRows.toString().hashCode()}_${leftRows.toString().hashCode()}_$widthMm"
        verifiedThermalSignatures.add(sig)
        val conn = BluetoothPrinterManager.getConnectionInfo()
        val payload = JSONObject().apply {
          put("id", id)
          put("widthMm", widthMm)
          put("connected", BluetoothPrinterManager.isConnected())
          put("printerName", conn.first)
          put("previewSignature", sig)
          put("imageBase64", base64)
        }
        notifyResult(reqId, true, "تم تجهيز معاينة الملاحظة", payload.toString())
      } catch (e: Exception) {
        notifyResult(reqId, false, e.message ?: "تعذر تجهيز المعاينة الحرارية للملاحظة")
      }
    }
  }

  @JavascriptInterface
  fun printNote(noteId: Long, reqId: String) {
    scope.launch(Dispatchers.IO) {
      try {
        val note = database.noteDao().getNoteById(noteId) ?: error("احفظ الملاحظة أولاً")
        val settings = StorePreferences.getSettings(activity)
        val widthMm = settings.printerWidthMm
        val sig = "NOTE_${note.id}_${note.rightRowsJson.hashCode()}_${note.leftRowsJson.hashCode()}_$widthMm"
        val bmp = BluetoothPrinterManager.buildNoteReceiptBitmap(activity, note, widthMm)
        if (settings.requireThermalPreview && !verifiedThermalSignatures.contains(sig)) {
          val base64 = BluetoothPrinterManager.renderBitmapToBase64(bmp)
          verifiedThermalSignatures.add(sig)
          val conn = BluetoothPrinterManager.getConnectionInfo()
          callJs("onThermalPreviewData", JSONObject().apply {
            put("kind", "NOTE")
            put("targetId", note.id)
            put("extraStr", "")
            put("startMillis", 0L)
            put("endMillis", 0L)
            put("title", "معاينة ملاحظة #${note.id}")
            put("widthMm", widthMm)
            put("previewSignature", sig)
            put("connected", BluetoothPrinterManager.isConnected())
            put("printerName", conn.first)
            put("imageBase64", base64)
          }.toString())
          notifyResult(reqId, false, "المعاينة إلزامية قبل الطباعة البلوتوث — راجع المعاينة ثم اضغط طباعة")
          return@launch
        }
        val result = BluetoothPrinterManager.printBitmap(bmp)
        notifyResult(reqId, result.first, result.second)
      } catch (e: Exception) {
        notifyResult(reqId, false, e.message ?: "تعذرت طباعة الملاحظة")
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
              put("invoiceId", t.invoiceId ?: 0L)
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
    addSupplierTransactionWithDate(supplierId, amount, isCredit, description, 0L, reqId)
  }

  @JavascriptInterface
  fun addSupplierTransactionWithDate(
    supplierId: Long,
    amount: Double,
    isCredit: Boolean,
    description: String,
    dateMillis: Long,
    reqId: String
  ) {
    scope.launch(Dispatchers.IO) {
      try {
        val effectiveDate = if (dateMillis > 0L) dateMillis else System.currentTimeMillis()
        engine.addSupplierTransaction(supplierId, amount, isCredit, description, effectiveDate)
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
    updateSupplierTransactionWithDate(txId, supplierId, amount, isCredit, description, 0L, reqId)
  }

  @JavascriptInterface
  fun updateSupplierTransactionWithDate(
    txId: Long,
    supplierId: Long,
    amount: Double,
    isCredit: Boolean,
    description: String,
    dateMillis: Long,
    reqId: String
  ) {
    scope.launch(Dispatchers.IO) {
      try {
        val effectiveDate = dateMillis.takeIf { it > 0L }
        engine.updateSupplierTransaction(txId, amount, isCredit, description, effectiveDate)
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
          val ok = WhatsAppHelper.shareText(activity, s.phone, msg)
          notifyResult(reqId, ok, if (ok) "تم فتح تطبيق المشاركة ✓" else "تعذر فتح تطبيق المشاركة")
        }
      } catch (e: Exception) {
        notifyResult(reqId, false, e.message ?: "تعذر مشاركة كشف المورد")
      }
    }
  }

  // ---------------- Account Statements (PDF & WhatsApp) ----------------

  @JavascriptInterface
  fun exportStatementPdf(partyType: String, partyId: Long, reqId: String) {
    scope.launch(Dispatchers.IO) {
      try {
        val result = generatePartyStatementPdfInternal(partyType, partyId)
        val uri = PdfGenerator.getUriForFile(activity, result.first)
        val settings = StorePreferences.getSettings(activity)
        activity.runOnUiThread {
          val opened = if (settings.autoOpenPdfAfterExport) WhatsAppHelper.openPdf(activity, uri) else false
          notifyResult(
            reqId,
            true,
            if (opened) "تم حفظ وفتح كشف الحساب PDF في ${result.second.displayPath} ✓"
            else "تم حفظ كشف الحساب PDF في ${result.second.displayPath} ✓"
          )
        }
      } catch (e: Exception) {
        notifyResult(reqId, false, e.message ?: "تعذر تصدير كشف الحساب PDF")
      }
    }
  }

  @JavascriptInterface
  fun shareStatementWhatsAppPdf(partyType: String, partyId: Long, reqId: String) {
    scope.launch(Dispatchers.IO) {
      try {
        val result = generatePartyStatementPdfInternal(partyType, partyId)
        val uri = PdfGenerator.getUriForFile(activity, result.first)
        val settings = StorePreferences.getSettings(activity)
        val phone: String
        val partyName: String
        if (partyType.equals("CUSTOMER", true)) {
          val c = database.customerDao().getCustomerById(partyId) ?: error("العميل غير موجود")
          phone = c.phone
          partyName = c.name
        } else {
          val s = database.supplierDao().getSupplierById(partyId) ?: error("المورد غير موجود")
          phone = s.phone
          partyName = s.name
        }
        val msg = "${settings.storeName} - كشف حساب: $partyName"
        activity.runOnUiThread {
          val ok = WhatsAppHelper.shareFile(activity, uri, "application/pdf", msg, phone, preferWhatsApp = settings.preferWhatsAppDirect)
          notifyResult(reqId, ok, if (ok) "تم تجهيز كشف الحساب PDF للمشاركة ✓" else "تعذر مشاركة كشف الحساب")
        }
      } catch (e: Exception) {
        notifyResult(reqId, false, e.message ?: "تعذر مشاركة كشف الحساب")
      }
    }
  }

  private suspend fun generatePartyStatementPdfInternal(partyType: String, partyId: Long) =
    if (partyType.equals("CUSTOMER", true)) {
      val detail = engine.getCustomerDetail(partyId) ?: error("العميل غير موجود")
      PdfGenerator.generateStatementPdf(
        context = activity,
        partyType = "CUSTOMER",
        partyName = detail.customer.name,
        partyPhone = detail.customer.phone,
        extraSubtitle = detail.customer.address,
        currentBalance = detail.customer.balance,
        entries = detail.transactions
      ) ?: error("تعذر إنشاء كشف الحساب PDF")
    } else {
      val detail = engine.getSupplierDetail(partyId) ?: error("المورد غير موجود")
      PdfGenerator.generateStatementPdf(
        context = activity,
        partyType = "SUPPLIER",
        partyName = detail.supplier.name,
        partyPhone = detail.supplier.phone,
        extraSubtitle = detail.supplier.company,
        currentBalance = detail.supplier.balance,
        entries = detail.transactions
      ) ?: error("تعذر إنشاء كشف حساب المورد PDF")
    }

  // ---------------- Inventory & History ----------------

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
  fun getInventoryHistory(itemId: Long, reqId: String) {
    scope.launch(Dispatchers.IO) {
      try {
        val snap = engine.getInventoryMovementHistory(itemId) ?: error("الصنف غير موجود")
        val json = JSONObject().apply {
          put("item", JSONObject().apply {
            put("id", snap.item.id)
            put("name", snap.item.name)
            put("category", snap.item.category)
            put("quantity", snap.item.quantity)
            put("purchasePrice", snap.item.purchasePrice)
            put("sellingPrice", snap.item.sellingPrice)
            put("unit", snap.item.unit)
          })
          put("totalPurchased", snap.totalPurchased)
          put("totalSold", snap.totalSold)
          put("totalAdjusted", snap.totalAdjusted)
          put("movements", JSONArray().apply {
            snap.movements.forEach { m ->
              put(JSONObject().apply {
                put("invoiceId", m.invoiceId)
                put("adjustmentId", m.adjustmentId)
                m.adjustmentPreviousQuantity?.let { put("previousQuantity", it) }
                m.adjustmentNewQuantity?.let { put("newQuantity", it) }
                put("invoiceNumber", m.invoiceNumber)
                put("invoiceType", m.invoiceType)
                put("partyName", m.partyName)
                put("deltaQty", m.deltaQty)
                put("unitPrice", m.unitPrice)
                put("totalPrice", m.totalPrice)
                put("date", m.date)
              })
            }
          })
        }
        callJs("onInventoryHistoryData", json.toString())
        notifyResult(reqId, true, "تم تحميل سجل حركات الصنف")
      } catch (e: Exception) {
        notifyResult(reqId, false, e.message ?: "تعذر تحميل حركات الصنف")
      }
    }
  }

  @JavascriptInterface
  fun updateInventoryAdjustment(adjustmentId: Long, newQuantity: Int, reason: String, reqId: String) {
    scope.launch(Dispatchers.IO) {
      try {
        engine.updateInventoryAdjustment(adjustmentId, newQuantity, reason)
        refreshCoreViews()
        notifyResult(reqId, true, "تم تعديل تسوية المخزون وعكس الفرق على الكمية ✓")
      } catch (e: Exception) {
        notifyResult(reqId, false, e.message ?: "تعذر تعديل تسوية المخزون")
      }
    }
  }

  @JavascriptInterface
  fun deleteInventoryAdjustment(adjustmentId: Long, reqId: String) {
    scope.launch(Dispatchers.IO) {
      try {
        engine.deleteInventoryAdjustment(adjustmentId)
        refreshCoreViews()
        notifyResult(reqId, true, "تم حذف التسوية وعكس أثرها على المخزون ✓")
      } catch (e: Exception) {
        notifyResult(reqId, false, e.message ?: "تعذر حذف تسوية المخزون")
      }
    }
  }

  @JavascriptInterface
  fun exportInventoryPdf(reqId: String) {
    scope.launch(Dispatchers.IO) {
      try {
        val items = database.inventoryDao().getItemsSnapshot()
        val result = PdfGenerator.generateInventoryPdf(activity, items) ?: error("تعذر إنشاء تقرير جرد المخزون PDF")
        val uri = PdfGenerator.getUriForFile(activity, result.first)
        val settings = StorePreferences.getSettings(activity)
        activity.runOnUiThread {
          if (settings.autoOpenPdfAfterExport) WhatsAppHelper.openPdf(activity, uri)
          notifyResult(reqId, true, "تم حفظ جرد المخزون PDF في ${result.second.displayPath} ✓")
        }
      } catch (e: Exception) {
        notifyResult(reqId, false, e.message ?: "تعذر تصدير جرد المخزون")
      }
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
  fun exportPurchaseInvoicesToInventory(reqId: String) {
    scope.launch(Dispatchers.IO) {
      try {
        val (lineCount, createdCount) = engine.exportUnlinkedPurchaseItemsToInventory()
        refreshCoreViews()
        val message = if (lineCount == 0) {
          "كل أصناف فواتير الشراء مرتبطة بالمخزن بالفعل"
        } else {
          "تم ترحيل $lineCount صنفاً من فواتير الشراء إلى المخزن، منها $createdCount صنف جديد ✓"
        }
        notifyResult(reqId, true, message, JSONObject().put("importedLines", lineCount).put("createdItems", createdCount).toString())
      } catch (e: Exception) {
        notifyResult(reqId, false, e.message ?: "تعذر ترحيل فواتير الشراء إلى المخزن")
      }
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
              put("stockQuantity", item.stockQuantity)
              put("unitLabel", item.unitLabel)
              put("unitFactor", item.unitFactor)
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
        val dateOpt = obj.optLong("date", 0L).takeIf { it > 0L }
        val itemsArray = obj.optJSONArray("items") ?: JSONArray()

        val items = mutableListOf<InvoiceItemEntity>()
        for (i in 0 until itemsArray.length()) {
          val itemObj = itemsArray.getJSONObject(i)
          val qty = itemObj.optInt("quantity", 0)
          val unitPrice = itemObj.optDouble("unitPrice", -1.0)
          val totalPriceOpt = itemObj.optDouble("totalPrice", -1.0)
          val lineTotal = if (totalPriceOpt.isFinite() && totalPriceOpt >= 0.0) totalPriceOpt else qty.toDouble() * unitPrice
          items.add(
            InvoiceItemEntity(
              invoiceId = 0L,
              itemId = itemObj.optLong("itemId", 0L),
              itemName = itemObj.optString("itemName", ""),
              quantity = qty,
              unitPrice = unitPrice,
              totalPrice = lineTotal,
              stockQuantity = itemObj.optInt("stockQuantity", 0),
              unitLabel = itemObj.optString("unitLabel", ""),
              unitFactor = itemObj.optInt("unitFactor", 1)
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
          paidAmount = paidAmount,
          invoiceDateMillis = dateOpt
        )

        val settings = StorePreferences.getSettings(activity)
        if (settings.autoSaveThermalImage && savedId > 0L) {
          runCatching {
            database.invoiceDao().getInvoiceWithItems(savedId)?.let { savedWithItems ->
              BluetoothPrinterManager.saveThermalReceiptImageToDownloads(
                context = activity,
                invoice = savedWithItems.invoice,
                items = savedWithItems.items,
                partyBalanceAfter = resolvePartyBalance(savedWithItems.invoice),
                paperWidthMm = settings.printerWidthMm
              )
            }
          }
        }

        // Refresh lists only. getInvoiceDetails() invokes the edit-form callback and
        // can reopen the invoice screen after a successful save due to async timing.
        refreshCoreViews()
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

  private fun computePreviewSignature(inv: InvoiceEntity, items: List<InvoiceItemEntity>, widthMm: Int): String {
    val itemsSig = items.joinToString("|") { "${it.itemId}:${it.itemName}:${it.quantity}:${it.unitPrice}" }
    return "${inv.id}_${inv.invoiceNumber}_${inv.date}_${inv.totalAmount}_${inv.paidAmount}_${widthMm}_${itemsSig.hashCode()}"
  }

  private suspend fun emitThermalPreview(withItems: InvoiceWithItems): Pair<String, Int> {
    val inv = withItems.invoice
    val balanceAfter = resolvePartyBalance(inv)
    val settings = StorePreferences.getSettings(activity)
    val widthMm = settings.printerWidthMm
    val signature = computePreviewSignature(inv, withItems.items, widthMm)
    val base64 = BluetoothPrinterManager.renderInvoicePreviewBase64(
      context = activity,
      invoice = inv,
      items = withItems.items,
      partyBalanceAfter = balanceAfter,
      paperWidthMm = widthMm
    )
    previewedInvoiceSignatures.add(signature)
    val conn = BluetoothPrinterManager.getConnectionInfo()
    val payload = JSONObject().apply {
      put("kind", "INVOICE")
      put("invoiceId", inv.id)
      put("invoiceNumber", inv.invoiceNumber)
      put("title", "معاينة فاتورة #${inv.invoiceNumber}")
      put("widthMm", widthMm)
      put("previewSignature", signature)
      put("connected", BluetoothPrinterManager.isConnected())
      put("printerName", conn.first)
      put("imageBase64", base64)
    }
    callJs("onThermalPreviewData", payload.toString())
    return signature to widthMm
  }

  @JavascriptInterface
  fun previewThermalInvoice(invoiceId: Long, reqId: String) {
    scope.launch(Dispatchers.IO) {
      try {
        val withItems = database.invoiceDao().getInvoiceWithItems(invoiceId) ?: error("احفظ الفاتورة أولاً للمعاينة")
        emitThermalPreview(withItems)
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
        val settings = StorePreferences.getSettings(activity)
        val signature = computePreviewSignature(inv, withItems.items, settings.printerWidthMm)
        if (settings.requireThermalPreview && !previewedInvoiceSignatures.contains(signature)) {
          emitThermalPreview(withItems)
          notifyResult(reqId, false, "المعاينة إلزامية قبل الطباعة البلوتوث — راجع المعاينة ثم اضغط طباعة")
          return@launch
        }
        val balanceAfter = resolvePartyBalance(inv)
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

  // ---------------- Generic Mandatory Thermal Preview / Print (Transactions, Statements, Reports) ----------------

  private suspend fun renderGenericThermalBitmap(
    kind: String,
    targetId: Long,
    extraStr: String,
    startMillis: Long,
    endMillis: Long,
    widthMm: Int
  ): Triple<String, String, Bitmap> {
    return when (kind.uppercase(Locale.US)) {
      "TX" -> {
        val snap = engine.getTransactionReceiptSnapshot(targetId) ?: error("العملية غير موجودة")
        val tx = snap.transaction
        val sig = "TX_${tx.id}_${tx.amount}_${tx.type}_${tx.date}_${tx.description.hashCode()}_$widthMm"
        val bmp = BluetoothPrinterManager.buildTransactionReceiptBitmap(activity, snap, widthMm)
        Triple("معاينة سند عملية #${tx.id}", sig, bmp)
      }
      "STATEMENT" -> {
        val isCust = extraStr.equals("CUSTOMER", true)
        if (isCust) {
          val detail = engine.getCustomerDetail(targetId) ?: error("العميل غير موجود")
          val sig = "ST_C_${detail.customer.id}_${detail.customer.balance}_${detail.transactions.size}_$widthMm"
          val bmp = BluetoothPrinterManager.buildStatementReceiptBitmap(
            activity, "CUSTOMER", detail.customer.name, detail.customer.phone,
            detail.customer.balance, detail.transactions, widthMm
          )
          Triple("معاينة كشف حساب: ${detail.customer.name}", sig, bmp)
        } else {
          val detail = engine.getSupplierDetail(targetId) ?: error("المورد غير موجود")
          val sig = "ST_S_${detail.supplier.id}_${detail.supplier.balance}_${detail.transactions.size}_$widthMm"
          val bmp = BluetoothPrinterManager.buildStatementReceiptBitmap(
            activity, "SUPPLIER", detail.supplier.name, detail.supplier.phone,
            detail.supplier.balance, detail.transactions, widthMm
          )
          Triple("معاينة كشف مورد: ${detail.supplier.name}", sig, bmp)
        }
      }
      "REPORT" -> {
        val effStart = if (startMillis > 0L) startMillis else 0L
        val effEnd = if (endMillis > 0L) endMillis else Long.MAX_VALUE
        val report = engine.buildReport(effStart, effEnd)
        val label = extraStr.ifBlank { "الكل" }
        val sig = "REP_${effStart}_${effEnd}_${report.invoiceCount}_${report.periodSales}_$widthMm"
        val bmp = BluetoothPrinterManager.buildReportReceiptBitmap(activity, report, label, widthMm)
        Triple("معاينة التقرير المالي ($label)", sig, bmp)
      }
      "NOTE" -> {
        val note = database.noteDao().getNoteById(targetId) ?: error("الملاحظة غير موجودة")
        val sig = "NOTE_${note.id}_${note.rightRowsJson.hashCode()}_${note.leftRowsJson.hashCode()}_$widthMm"
        val bmp = BluetoothPrinterManager.buildNoteReceiptBitmap(activity, note, widthMm)
        Triple("معاينة ملاحظة #${note.id}", sig, bmp)
      }
      else -> error("نوع المعاينة الحرارية غير مدعوم")
    }
  }

  @JavascriptInterface
  fun previewGenericThermal(
    kind: String,
    targetId: Long,
    extraStr: String,
    startMillis: Long,
    endMillis: Long,
    reqId: String
  ) {
    scope.launch(Dispatchers.IO) {
      try {
        val settings = StorePreferences.getSettings(activity)
        val widthMm = settings.printerWidthMm
        val (title, sig, bmp) = renderGenericThermalBitmap(kind, targetId, extraStr, startMillis, endMillis, widthMm)
        val base64 = BluetoothPrinterManager.renderBitmapToBase64(bmp)
        verifiedThermalSignatures.add(sig)
        val conn = BluetoothPrinterManager.getConnectionInfo()
        val payload = JSONObject().apply {
          put("kind", kind.uppercase(Locale.US))
          put("targetId", targetId)
          put("extraStr", extraStr)
          put("startMillis", startMillis)
          put("endMillis", endMillis)
          put("title", title)
          put("widthMm", widthMm)
          put("previewSignature", sig)
          put("connected", BluetoothPrinterManager.isConnected())
          put("printerName", conn.first)
          put("imageBase64", base64)
        }
        callJs("onThermalPreviewData", payload.toString())
        notifyResult(reqId, true, "تم تجهيز المعاينة الحرارية")
      } catch (e: Exception) {
        notifyResult(reqId, false, e.message ?: "تعذر تجهيز المعاينة الحرارية")
      }
    }
  }

  @JavascriptInterface
  fun printGenericThermal(
    kind: String,
    targetId: Long,
    extraStr: String,
    startMillis: Long,
    endMillis: Long,
    reqId: String
  ) {
    scope.launch(Dispatchers.IO) {
      try {
        val settings = StorePreferences.getSettings(activity)
        val widthMm = settings.printerWidthMm
        val (title, sig, bmp) = renderGenericThermalBitmap(kind, targetId, extraStr, startMillis, endMillis, widthMm)
        if (settings.requireThermalPreview && !verifiedThermalSignatures.contains(sig)) {
          val base64 = BluetoothPrinterManager.renderBitmapToBase64(bmp)
          verifiedThermalSignatures.add(sig)
          val conn = BluetoothPrinterManager.getConnectionInfo()
          callJs("onThermalPreviewData", JSONObject().apply {
            put("kind", kind.uppercase(Locale.US))
            put("targetId", targetId)
            put("extraStr", extraStr)
            put("startMillis", startMillis)
            put("endMillis", endMillis)
            put("title", title)
            put("widthMm", widthMm)
            put("previewSignature", sig)
            put("connected", BluetoothPrinterManager.isConnected())
            put("printerName", conn.first)
            put("imageBase64", base64)
          }.toString())
          notifyResult(reqId, false, "المعاينة إلزامية قبل الطباعة البلوتوث — راجع المعاينة ثم اضغط طباعة")
          return@launch
        }
        val result = BluetoothPrinterManager.printBitmap(bmp)
        notifyResult(reqId, result.first, result.second)
      } catch (e: Exception) {
        notifyResult(reqId, false, e.message ?: "فشلت الطباعة")
      }
    }
  }

  @JavascriptInterface
  fun saveGenericThermalToAppFolder(
    kind: String,
    targetId: Long,
    extraStr: String,
    startMillis: Long,
    endMillis: Long,
    reqId: String
  ) {
    scope.launch(Dispatchers.IO) {
      try {
        val settings = StorePreferences.getSettings(activity)
        val bmp = if (kind.equals("NOTE", ignoreCase = true)) {
          val note = database.noteDao().getNoteById(targetId) ?: error("الملاحظة غير موجودة")
          BluetoothPrinterManager.buildNoteReceiptBitmap(activity, note, settings.printerWidthMm, renderScale = 3)
        } else {
          renderGenericThermalBitmap(kind, targetId, extraStr, startMillis, endMillis, settings.printerWidthMm).third
        }
        val prefix = "thermal_${kind.lowercase(Locale.US)}_$targetId"
        val saved = BluetoothPrinterManager.saveThermalBitmapToDownloads(activity, bmp, prefix)
        notifyResult(reqId, true, "تم حفظ صورة الإيصال في ${saved.displayPath} ✓")
      } catch (e: Exception) {
        notifyResult(reqId, false, e.message ?: "تعذر حفظ صورة الإيصال")
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
        val settings = StorePreferences.getSettings(activity)
        activity.runOnUiThread {
          val opened = if (settings.autoOpenPdfAfterExport) WhatsAppHelper.openPdf(activity, uri) else false
          notifyResult(
            reqId,
            true,
            if (opened) "تم حفظ وفتح الفاتورة بصيغة PDF ✓" else "تم حفظ الفاتورة بصيغة PDF ✓",
            JSONObject().put("hasPdf", true).toString()
          )
        }
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
          val ok = WhatsAppHelper.shareFile(activity, uri, "application/pdf", msg, phone, preferWhatsApp = settings.preferWhatsAppDirect)
          notifyResult(
            reqId,
            ok,
            if (ok) "تم حفظ الفاتورة PDF وجاهزة للمشاركة ✓" else "تعذر مشاركة ملف PDF",
            JSONObject().put("hasPdf", true).toString()
          )
        }
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
          val ok = WhatsAppHelper.shareFile(activity, uri, "application/octet-stream", msg, phone, preferWhatsApp = settings.preferWhatsAppDirect)
          notifyResult(reqId, ok, if (ok) "تم تجهيز صورة الفاتورة للمشاركة ✓" else "تعذر مشاركة صورة الفاتورة")
        }
      } catch (e: Exception) {
        notifyResult(reqId, false, e.message ?: "تعذر مشاركة صورة الفاتورة")
      }
    }
  }

  // ---------------- Reports ----------------

  @JavascriptInterface
  fun getReports(startDateMillis: Long, endDateMillis: Long) {
    scope.launch(Dispatchers.IO) {
      val settings = StorePreferences.getSettings(activity)
      val effectiveStart = if (startDateMillis > 0) startDateMillis else 0L
      val effectiveEnd = if (endDateMillis > 0) endDateMillis else Long.MAX_VALUE
      val report = engine.buildReport(effectiveStart, effectiveEnd, settings.lowStockThreshold)

      val json = JSONObject().apply {
        put("todaySales", report.todaySales)
        put("todayPurchases", report.todayPurchases)
        put("sales", report.periodSales)
        put("purchases", report.periodPurchases)
        put("cashSales", report.cashSales)
        put("creditSales", report.creditSales)
        put("cogs", report.cogs)
        put("grossProfit", report.grossProfit)
        put("uncostedSalesLineCount", report.uncostedSalesLineCount)
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

  @JavascriptInterface
  fun shareReportWhatsAppPdf(startDateMillis: Long, endDateMillis: Long, periodLabel: String, reqId: String) {
    scope.launch(Dispatchers.IO) {
      try {
        val settings = StorePreferences.getSettings(activity)
        val effectiveStart = if (startDateMillis > 0) startDateMillis else 0L
        val effectiveEnd = if (endDateMillis > 0) endDateMillis else Long.MAX_VALUE
        val label = periodLabel.ifBlank { "الكل" }
        val report = engine.buildReport(effectiveStart, effectiveEnd, settings.lowStockThreshold)
        val result = PdfGenerator.generateFinancialReportPdf(activity, report, label)
          ?: error("تعذر إنشاء ملف التقرير PDF")
        val uri = PdfGenerator.getUriForFile(activity, result.first)
        val msg = "${settings.storeName} - التقرير المالي ($label)"
        activity.runOnUiThread {
          val ok = WhatsAppHelper.shareFile(activity, uri, "application/pdf", msg, "", preferWhatsApp = settings.preferWhatsAppDirect)
          notifyResult(reqId, ok, if (ok) "تم تجهيز التقرير PDF للمشاركة ✓" else "تعذر مشاركة التقرير")
        }
      } catch (e: Exception) {
        notifyResult(reqId, false, e.message ?: "تعذر مشاركة التقرير PDF")
      }
    }
  }

  // ---------------- Settings, Bluetooth, Backup & Reset ----------------

  private fun buildSettingsJson(): JSONObject {
    val settings = StorePreferences.getSettings(activity)
    val conn = BluetoothPrinterManager.getConnectionInfo()
    val safetyFile = BackupRestoreManager.getSafetyBackupFile(activity)
    return JSONObject().apply {
      put("storeName", settings.storeName)
      put("storePhone", settings.storePhone)
      put("storeAddress", settings.storeAddress)
      put("currency", settings.currency)
      put("uiTextScale", settings.uiTextScale)
      put("compactLists", settings.compactLists)
      put("defaultInvoiceType", settings.defaultInvoiceType)
      put("autoFillCashPaid", settings.autoFillCashPaid)
      put("confirmBeforeDeleteInvoice", settings.confirmBeforeDeleteInvoice)
      put("quickPartyCount", settings.quickPartyCount)
      put("lowStockThreshold", settings.lowStockThreshold)
      put("defaultInventorySort", settings.defaultInventorySort)
      put("defaultInventoryUnit", settings.defaultInventoryUnit)
      put("defaultInventoryCategory", settings.defaultInventoryCategory)
      put("defaultCustomerSort", settings.defaultCustomerSort)
      put("promptWhatsAppAfterTx", settings.promptWhatsAppAfterTx)
      put("showZeroBalanceParties", settings.showZeroBalanceParties)
      put("defaultReportRange", settings.defaultReportRange)
      put("showUncostedProfitWarning", settings.showUncostedProfitWarning)
      put("printerWidthMm", settings.printerWidthMm)
      put("defaultPrinterAddress", settings.defaultPrinterAddress)
      put("defaultPrinterName", settings.defaultPrinterName)
      put("requireThermalPreview", settings.requireThermalPreview)
      put("autoOpenPdfAfterExport", settings.autoOpenPdfAfterExport)
      put("preferWhatsAppDirect", settings.preferWhatsAppDirect)
      put("autoSaveThermalImage", settings.autoSaveThermalImage)
      put("linkedFolderActive", settings.linkedFolderUri.isNotBlank())
      put("folderDisplayPath", "Download/${AppDownloadsFolderManager.APP_FOLDER_NAME}")
      put("hasSafetyBackup", safetyFile.exists() && safetyFile.length() > 0L)
      put("printerConnected", BluetoothPrinterManager.isConnected())
      put("connectedPrinterName", conn.first)
      put("connectedPrinterAddress", conn.second)
      put("bluetoothAvailable", BluetoothPrinterManager.isBluetoothAvailable())
      put("bluetoothEnabled", BluetoothPrinterManager.isBluetoothEnabled())
    }
  }

  @JavascriptInterface
  fun getSettings() {
    scope.launch(Dispatchers.IO) {
      callJs("onSettingsData", buildSettingsJson().toString())
    }
  }

  @JavascriptInterface
  fun saveStoreSettings(jsonPayload: String, reqId: String) {
    saveAdvancedSettings(jsonPayload, reqId)
  }

  @JavascriptInterface
  fun saveAdvancedSettings(jsonPayload: String, reqId: String) {
    scope.launch(Dispatchers.IO) {
      try {
        val obj = JSONObject(jsonPayload)
        val cur = StorePreferences.getSettings(activity)
        val updated = StoreSettings(
          storeName = obj.optString("storeName", cur.storeName),
          storePhone = obj.optString("storePhone", cur.storePhone),
          storeAddress = obj.optString("storeAddress", cur.storeAddress),
          currency = obj.optString("currency", cur.currency),
          uiTextScale = obj.optString("uiTextScale", cur.uiTextScale),
          compactLists = obj.optBoolean("compactLists", cur.compactLists),
          defaultInvoiceType = obj.optString("defaultInvoiceType", cur.defaultInvoiceType),
          autoFillCashPaid = obj.optBoolean("autoFillCashPaid", cur.autoFillCashPaid),
          confirmBeforeDeleteInvoice = obj.optBoolean("confirmBeforeDeleteInvoice", cur.confirmBeforeDeleteInvoice),
          quickPartyCount = obj.optInt("quickPartyCount", cur.quickPartyCount),
          lowStockThreshold = obj.optInt("lowStockThreshold", cur.lowStockThreshold),
          defaultInventorySort = obj.optString("defaultInventorySort", cur.defaultInventorySort),
          defaultInventoryUnit = obj.optString("defaultInventoryUnit", cur.defaultInventoryUnit),
          defaultInventoryCategory = obj.optString("defaultInventoryCategory", cur.defaultInventoryCategory),
          defaultCustomerSort = obj.optString("defaultCustomerSort", cur.defaultCustomerSort),
          promptWhatsAppAfterTx = obj.optBoolean("promptWhatsAppAfterTx", cur.promptWhatsAppAfterTx),
          showZeroBalanceParties = obj.optBoolean("showZeroBalanceParties", cur.showZeroBalanceParties),
          defaultReportRange = obj.optString("defaultReportRange", cur.defaultReportRange),
          showUncostedProfitWarning = obj.optBoolean("showUncostedProfitWarning", cur.showUncostedProfitWarning),
          printerWidthMm = obj.optInt("printerWidthMm", cur.printerWidthMm),
          defaultPrinterAddress = cur.defaultPrinterAddress,
          defaultPrinterName = cur.defaultPrinterName,
          requireThermalPreview = obj.optBoolean("requireThermalPreview", cur.requireThermalPreview),
          autoOpenPdfAfterExport = obj.optBoolean("autoOpenPdfAfterExport", cur.autoOpenPdfAfterExport),
          preferWhatsAppDirect = obj.optBoolean("preferWhatsAppDirect", cur.preferWhatsAppDirect),
          autoSaveThermalImage = obj.optBoolean("autoSaveThermalImage", cur.autoSaveThermalImage),
          linkedFolderUri = cur.linkedFolderUri
        )
        StorePreferences.saveAdvancedSettings(activity, updated)
        callJs("onSettingsData", buildSettingsJson().toString())
        getDashboardData()
        notifyResult(reqId, true, "تم حفظ الإعدادات بنجاح ✓")
      } catch (e: Exception) {
        notifyResult(reqId, false, e.message ?: "تعذر حفظ الإعدادات")
      }
    }
  }

  @JavascriptInterface
  fun resetAdvancedSettings(reqId: String) {
    scope.launch(Dispatchers.IO) {
      try {
        StorePreferences.resetAdvancedSettings(activity)
        callJs("onSettingsData", buildSettingsJson().toString())
        getDashboardData()
        notifyResult(reqId, true, "تمت استعادة الإعدادات الافتراضية ✓")
      } catch (e: Exception) {
        notifyResult(reqId, false, e.message ?: "تعذر إعادة ضبط الإعدادات")
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
        paperWidthMm = settings.printerWidthMm,
        context = activity
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
  fun restoreSafetyBackup(reqId: String) {
    scope.launch(Dispatchers.IO) {
      try {
        val msg = BackupRestoreManager.restoreFromSafetyBackup(activity)
        refreshCoreViews()
        getSettings()
        notifyResult(reqId, true, msg)
      } catch (e: Exception) {
        notifyResult(reqId, false, e.message ?: "تعذر استعادة نسخة الأمان السابقة")
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
      getSettings()
      notifyResult(reqId, true, "تم إلغاء ربط المجلد المخصص")
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
        val settings = StorePreferences.getSettings(activity)
        val effectiveStart = if (startDateMillis > 0) startDateMillis else 0L
        val effectiveEnd = if (endDateMillis > 0) endDateMillis else Long.MAX_VALUE
        val report = engine.buildReport(effectiveStart, effectiveEnd, settings.lowStockThreshold)
        val result = PdfGenerator.generateFinancialReportPdf(activity, report, periodLabel.ifBlank { "الكل" })
          ?: error("تعذر إنشاء ملف التقرير PDF")
        val uri = PdfGenerator.getUriForFile(activity, result.first)
        activity.runOnUiThread {
          if (settings.autoOpenPdfAfterExport) WhatsAppHelper.openPdf(activity, uri)
          notifyResult(reqId, true, "تم حفظ التقرير المالي في ${result.second.displayPath} ✓")
        }
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
    if (success) {
      refreshCoreViews()
      getSettings()
    }
    notifyResult("backup_op", success, message)
  }

  fun onFolderLinkResult(success: Boolean, message: String) {
    getSettings()
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
