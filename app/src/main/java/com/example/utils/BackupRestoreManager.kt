package com.example.utils

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.example.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** Portable, versioned JSON backup. Restore is atomic and validates the entire file first. */
object BackupRestoreManager {
  private const val FORMAT = "alaziy-grocery-backup"
  private const val BACKUP_VERSION = 1

  suspend fun buildBackupEnvelope(context: Context): JSONObject = withContext(Dispatchers.IO) {
    val db = AppDatabase.getDatabase(context)
    val payload = db.withTransaction { JSONObject().apply {
      put("customers", JSONArray().apply { db.customerDao().getCustomersSnapshot().forEach { c ->
        put(JSONObject().apply { put("id", c.id); put("name", c.name); put("phone", c.phone); put("address", c.address); put("balance", c.balance) })
      } })
      put("suppliers", JSONArray().apply { db.supplierDao().getSuppliersSnapshot().forEach { s ->
        put(JSONObject().apply { put("id", s.id); put("name", s.name); put("phone", s.phone); put("company", s.company); put("balance", s.balance) })
      } })
      put("inventoryItems", JSONArray().apply { db.inventoryDao().getItemsSnapshot().forEach { i ->
        put(JSONObject().apply { put("id", i.id); put("name", i.name); put("category", i.category); put("quantity", i.quantity); put("purchasePrice", i.purchasePrice); put("sellingPrice", i.sellingPrice); put("unit", i.unit) })
      } })
      put("invoices", JSONArray().apply { db.invoiceDao().getInvoicesSnapshot().forEach { i ->
        put(JSONObject().apply { put("id", i.id); put("invoiceNumber", i.invoiceNumber); put("type", i.type); put("partyId", i.partyId); put("partyName", i.partyName); put("date", i.date); put("totalAmount", i.totalAmount); put("paidAmount", i.paidAmount); put("remainingAmount", i.remainingAmount); put("status", i.status) })
      } })
      put("invoiceItems", JSONArray().apply { db.invoiceDao().getInvoiceItemsSnapshot().forEach { i ->
        put(JSONObject().apply { put("id", i.id); put("invoiceId", i.invoiceId); put("itemId", i.itemId); put("itemName", i.itemName); put("quantity", i.quantity); put("unitPrice", i.unitPrice); put("totalPrice", i.totalPrice) })
      } })
      put("transactions", JSONArray().apply { db.transactionDao().getTransactionsSnapshot().forEach { t ->
        put(JSONObject().apply { put("id", t.id); put("partyType", t.partyType); put("partyId", t.partyId); put("partyName", t.partyName); put("type", t.type); put("amount", t.amount); put("description", t.description); put("date", t.date) })
      } })
    } }
    JSONObject().apply {
      put("format", FORMAT); put("version", BACKUP_VERSION); put("createdAt", System.currentTimeMillis())
      put("payload", payload); put("sha256", sha256(payload.toString()))
    }
  }

  suspend fun exportToAppDownloadsFolder(context: Context): SavedDownloadResult = withContext(Dispatchers.IO) {
    val envelope = buildBackupEnvelope(context)
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val fileName = "alaziy_backup_$stamp.json"
    val bytes = envelope.toString(2).toByteArray(Charsets.UTF_8)
    AppDownloadsFolderManager.saveBytes(
      context = context,
      subfolder = AppDownloadsFolderManager.SUB_BACKUPS,
      fileName = fileName,
      mimeType = "application/json",
      bytes = bytes,
      overwriteExisting = false
    )
  }

  suspend fun exportToUri(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
    val envelope = buildBackupEnvelope(context)
    val output = context.contentResolver.openOutputStream(uri, "w") ?: error("تعذر فتح ملف النسخة الاحتياطية للكتابة")
    output.bufferedWriter(Charsets.UTF_8).use { it.write(envelope.toString(2)) }
    "تم إنشاء النسخة الاحتياطية بنجاح"
  }

  suspend fun restoreFromUri(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
    val input = context.contentResolver.openInputStream(uri) ?: error("تعذر فتح ملف النسخة الاحتياطية")
    val json = input.bufferedReader(Charsets.UTF_8).use { it.readText() }
    require(json.isNotBlank() && json.length <= 50_000_000) { "ملف النسخة الاحتياطية فارغ أو أكبر من الحد المسموح" }
    val envelope = JSONObject(json)
    require(envelope.optString("format") == FORMAT) { "هذا الملف ليس نسخة احتياطية لتطبيق بقالة العزي" }
    require(envelope.optInt("version", -1) == BACKUP_VERSION) { "إصدار النسخة الاحتياطية غير مدعوم" }
    val payload = envelope.optJSONObject("payload") ?: error("بيانات النسخة الاحتياطية غير مكتملة")
    require(envelope.optString("sha256") == sha256(payload.toString())) { "فشل التحقق من سلامة الملف؛ قد يكون تالفاً أو تم تعديله" }

    // Parse every table before opening a write transaction.
    val customers = payload.requiredArray("customers").toList { o ->
      CustomerEntity(o.getLong("id"), o.getString("name"), o.getString("phone"), o.getString("address"), o.getDouble("balance").finite("رصيد العميل"))
    }
    val suppliers = payload.requiredArray("suppliers").toList { o ->
      SupplierEntity(o.getLong("id"), o.getString("name"), o.getString("phone"), o.getString("company"), o.getDouble("balance").finite("رصيد المورد"))
    }
    val inventory = payload.requiredArray("inventoryItems").toList { o ->
      InventoryItemEntity(o.getLong("id"), o.getString("name"), o.getString("category"),
        o.getInt("quantity").also { require(it >= 0) { "كمية مخزون غير صحيحة" } },
        o.getDouble("purchasePrice").finite("سعر الشراء").also { require(it >= 0) },
        o.getDouble("sellingPrice").finite("سعر البيع").also { require(it >= 0) }, o.getString("unit"))
    }
    val invoices = payload.requiredArray("invoices").toList { o ->
      InvoiceEntity(o.getLong("id"), o.getString("invoiceNumber"), o.getString("type"), o.getLong("partyId"),
        o.getString("partyName"), o.getLong("date"), o.getDouble("totalAmount").finite("إجمالي الفاتورة"),
        o.getDouble("paidAmount").finite("المدفوع"), o.getDouble("remainingAmount").finite("المتبقي"), o.getString("status"))
    }
    val invoiceItems = payload.requiredArray("invoiceItems").toList { o ->
      InvoiceItemEntity(o.getLong("id"), o.getLong("invoiceId"), o.getLong("itemId"), o.getString("itemName"),
        o.getInt("quantity").also { require(it > 0) { "كمية صنف فاتورة غير صحيحة" } },
        o.getDouble("unitPrice").finite("سعر الوحدة"), o.getDouble("totalPrice").finite("إجمالي الصنف"))
    }
    val transactions = payload.requiredArray("transactions").toList { o ->
      TransactionEntity(o.getLong("id"), o.getString("partyType"), o.getLong("partyId"), o.getString("partyName"),
        o.getString("type"), o.getDouble("amount").finite("مبلغ الحركة"), o.getString("description"), o.getLong("date"))
    }

    require(customers.all { it.name.isNotBlank() } && suppliers.all { it.name.isNotBlank() } &&
      inventory.all { it.name.isNotBlank() }) { "يوجد سجل باسم فارغ في النسخة الاحتياطية" }
    require(invoices.all { invoice ->
      invoice.invoiceNumber.isNotBlank() &&
        invoice.type in setOf("SALE", "CASH", "CREDIT", "PURCHASE") &&
        invoice.status in setOf("PAID", "UNPAID", "PARTIAL") &&
        invoice.totalAmount > 0 && invoice.paidAmount >= 0 && invoice.remainingAmount >= 0 &&
        invoice.paidAmount <= invoice.totalAmount + 0.000001 &&
        abs(invoice.totalAmount - invoice.paidAmount - invoice.remainingAmount) <= 0.01
    }) { "توجد بيانات مالية أو حالة فاتورة غير متطابقة" }
    require(invoices.map { it.id }.distinct().size == invoices.size) { "توجد معرفات فواتير مكررة" }
    require(invoices.map { it.invoiceNumber }.distinct().size == invoices.size) { "توجد أرقام فواتير مكررة" }
    require(invoiceItems.all { item ->
      item.invoiceId > 0 && item.itemId > 0 && item.unitPrice >= 0 && item.totalPrice >= 0 &&
        invoices.any { it.id == item.invoiceId }
    }) { "توجد تفاصيل فاتورة غير مرتبطة أو بأسعار غير صحيحة" }

    val customerIds = customers.map { it.id }.toSet()
    val supplierIds = suppliers.map { it.id }.toSet()
    require(invoices.all { invoice ->
      invoice.partyId == 0L || if (invoice.type == "PURCHASE") invoice.partyId in supplierIds else invoice.partyId in customerIds
    }) { "توجد فاتورة مرتبطة بعميل أو مورد غير موجود" }
    require(transactions.all { transaction ->
      transaction.partyId > 0 && transaction.amount > 0 &&
        transaction.type in setOf("DEBIT (عليه)", "CREDIT (له)") &&
        when (transaction.partyType) {
          "CUSTOMER" -> transaction.partyId in customerIds
          "SUPPLIER" -> transaction.partyId in supplierIds
          else -> false
        }
    }) { "توجد حركة مالية مرتبطة بطرف غير موجود أو ببيانات غير صحيحة" }
    require(listOf(customers.map { it.id }, suppliers.map { it.id }, inventory.map { it.id }, invoices.map { it.id },
      invoiceItems.map { it.id }, transactions.map { it.id }).all { ids -> ids.all { it > 0 } && ids.distinct().size == ids.size }) {
      "توجد معرفات سجلات غير صحيحة أو مكررة"
    }

    val db = AppDatabase.getDatabase(context)
    db.withTransaction {
      db.invoiceDao().clearInvoiceItems()
      db.transactionDao().clearTransactions()
      db.invoiceDao().clearInvoices()
      db.inventoryDao().clearItems()
      db.customerDao().clearCustomers()
      db.supplierDao().clearSuppliers()
      db.customerDao().restoreCustomers(customers)
      db.supplierDao().restoreSuppliers(suppliers)
      db.inventoryDao().restoreItems(inventory)
      db.invoiceDao().restoreInvoices(invoices)
      db.invoiceDao().restoreInvoiceItems(invoiceItems)
      db.transactionDao().restoreTransactions(transactions)
    }
    "تمت الاستعادة: ${customers.size} عميل، ${suppliers.size} مورد، ${inventory.size} صنف، ${invoices.size} فاتورة"
  }

  private fun JSONObject.requiredArray(key: String): JSONArray =
    optJSONArray(key) ?: error("النسخة الاحتياطية لا تحتوي على قسم $key")

  private inline fun <T> JSONArray.toList(parse: (JSONObject) -> T): List<T> =
    (0 until length()).map { index -> parse(optJSONObject(index) ?: error("سجل غير صالح في النسخة الاحتياطية")) }

  private fun Double.finite(label: String): Double {
    require(isFinite()) { "$label يحتوي على قيمة غير صالحة" }
    return this
  }

  private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
