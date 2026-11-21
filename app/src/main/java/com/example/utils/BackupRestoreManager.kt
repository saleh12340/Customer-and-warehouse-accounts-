package com.example.utils

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.example.data.*
import com.example.viewmodel.GroceryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** Portable, versioned JSON backup. Restore is atomic and validates the entire file first. */
object BackupRestoreManager {
  private const val FORMAT = "alaziy-grocery-backup"
  private const val BACKUP_VERSION = 1
  private const val MAX_MONETARY = GroceryViewModel.MAX_MONETARY_AMOUNT

  suspend fun buildBackupEnvelope(context: Context): JSONObject = withContext(Dispatchers.IO) {
    val db = AppDatabase.getDatabase(context)
    val settings = StorePreferences.getSettings(context)
    val payload = db.withTransaction {
      JSONObject().apply {
        put("customers", JSONArray().apply {
          db.customerDao().getCustomersSnapshot().forEach { c ->
            put(JSONObject().apply {
              put("id", c.id)
              put("name", c.name)
              put("phone", c.phone)
              put("address", c.address)
              put("balance", c.balance)
            })
          }
        })
        put("suppliers", JSONArray().apply {
          db.supplierDao().getSuppliersSnapshot().forEach { s ->
            put(JSONObject().apply {
              put("id", s.id)
              put("name", s.name)
              put("phone", s.phone)
              put("company", s.company)
              put("balance", s.balance)
            })
          }
        })
        put("inventoryItems", JSONArray().apply {
          db.inventoryDao().getItemsSnapshot().forEach { i ->
            put(JSONObject().apply {
              put("id", i.id)
              put("name", i.name)
              put("category", i.category)
              put("quantity", i.quantity)
              put("purchasePrice", i.purchasePrice)
              put("sellingPrice", i.sellingPrice)
              put("unit", i.unit)
            })
          }
        })
        put("invoices", JSONArray().apply {
          db.invoiceDao().getInvoicesSnapshot().forEach { i ->
            put(JSONObject().apply {
              put("id", i.id)
              put("invoiceNumber", i.invoiceNumber)
              put("type", i.type)
              put("partyId", i.partyId)
              put("partyName", i.partyName)
              put("date", i.date)
              put("totalAmount", i.totalAmount)
              put("paidAmount", i.paidAmount)
              put("remainingAmount", i.remainingAmount)
              put("status", i.status)
            })
          }
        })
        put("invoiceItems", JSONArray().apply {
          db.invoiceDao().getInvoiceItemsSnapshot().forEach { i ->
            put(JSONObject().apply {
              put("id", i.id)
              put("invoiceId", i.invoiceId)
              put("itemId", i.itemId)
              put("itemName", i.itemName)
              put("quantity", i.quantity)
              put("unitPrice", i.unitPrice)
              put("totalPrice", i.totalPrice)
              put("stockQuantity", i.stockQuantity)
              put("unitLabel", i.unitLabel)
              put("unitFactor", i.unitFactor)
              i.costPriceAtSale?.let { put("costPriceAtSale", it) }
            })
          }
        })
        put("transactions", JSONArray().apply {
          db.transactionDao().getTransactionsSnapshot().forEach { t ->
            put(JSONObject().apply {
              put("id", t.id)
              put("partyType", t.partyType)
              put("partyId", t.partyId)
              put("partyName", t.partyName)
              put("type", t.type)
              put("amount", t.amount)
              put("description", t.description)
              put("date", t.date)
              t.invoiceId?.let { put("invoiceId", it) }
            })
          }
        })
        put("inventoryAdjustments", JSONArray().apply {
          db.inventoryAdjustmentDao().getAllAdjustmentsSnapshot().forEach { a ->
            put(JSONObject().apply {
              put("id", a.id); put("itemId", a.itemId); put("itemName", a.itemName)
              put("previousQuantity", a.previousQuantity); put("newQuantity", a.newQuantity)
              put("deltaQuantity", a.deltaQuantity); put("reason", a.reason); put("date", a.date)
            })
          }
        })
        put("notes", JSONArray().apply {
          db.noteDao().getNotesSnapshot().forEach { n ->
            put(JSONObject().apply {
              put("id", n.id)
              put("createdAt", n.createdAt)
              put("updatedAt", n.updatedAt)
              put("rightRowsJson", n.rightRowsJson)
              put("leftRowsJson", n.leftRowsJson)
            })
          }
        })
        put("settings", JSONObject().apply {
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
        })
      }
    }
    JSONObject().apply {
      put("format", FORMAT)
      put("version", BACKUP_VERSION)
      put("createdAt", System.currentTimeMillis())
      put("payload", payload)
      put("sha256", sha256(payload.toString()))
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

  fun getSafetyBackupFile(context: Context): File {
    val dir = File(context.filesDir, "safety_backups").apply { mkdirs() }
    return File(dir, "pre_restore_latest.json")
  }

  suspend fun createSafetyBackupBeforeRestore(context: Context): File = withContext(Dispatchers.IO) {
    val safetyFile = getSafetyBackupFile(context)
    val envelope = buildBackupEnvelope(context)
    safetyFile.writeText(envelope.toString(2), Charsets.UTF_8)
    safetyFile
  }

  suspend fun restoreFromSafetyBackup(context: Context): String = withContext(Dispatchers.IO) {
    val safetyFile = getSafetyBackupFile(context)
    require(safetyFile.exists() && safetyFile.length() > 0L) { "لا توجد نسخة أمان سابقة للاستعادة منها" }
    restoreFromJson(context, safetyFile.readText(Charsets.UTF_8), createSafetyBackup = false)
  }

  suspend fun restoreFromUri(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
    val input = context.contentResolver.openInputStream(uri) ?: error("تعذر فتح ملف النسخة الاحتياطية")
    val json = input.bufferedReader(Charsets.UTF_8).use { it.readText() }
    restoreFromJson(context, json, createSafetyBackup = true)
  }

  internal suspend fun restoreFromJson(
    context: Context,
    json: String,
    createSafetyBackup: Boolean = true
  ): String = withContext(Dispatchers.IO) {
    require(json.isNotBlank() && json.length <= 50_000_000) { "ملف النسخة الاحتياطية فارغ أو أكبر من الحد المسموح" }
    val envelope = runCatching { JSONObject(json) }.getOrElse {
      throw IllegalArgumentException("ملف النسخة الاحتياطية تالف أو بصيغة JSON غير صالحة")
    }
    require(envelope.optString("format") == FORMAT) { "هذا الملف ليس نسخة احتياطية لتطبيق بقالة العزي" }
    require(envelope.optInt("version", -1) == BACKUP_VERSION) { "إصدار النسخة الاحتياطية غير مدعوم" }
    val payload = envelope.optJSONObject("payload") ?: error("بيانات النسخة الاحتياطية غير مكتملة")
    require(envelope.optString("sha256") == sha256(payload.toString())) { "فشل التحقق من سلامة الملف؛ قد يكون تالفاً أو تم تعديله" }

    // Parse & validate every table before modifying Room or settings.
    val customers = try {
      payload.requiredArray("customers").toList { o ->
        CustomerEntity(
          o.getLong("id"),
          o.getString("name"),
          o.optString("phone", ""),
          o.optString("address", ""),
          o.getDouble("balance").boundedMoney("رصيد العميل", allowNegative = true)
        )
      }
    } catch (e: IllegalArgumentException) {
      throw e
    } catch (_: Exception) {
      throw IllegalArgumentException("بيانات جدول العملاء في النسخة الاحتياطية غير مكتملة أو تالفة")
    }

    val suppliers = try {
      payload.requiredArray("suppliers").toList { o ->
        SupplierEntity(
          o.getLong("id"),
          o.getString("name"),
          o.optString("phone", ""),
          o.optString("company", ""),
          o.getDouble("balance").boundedMoney("رصيد المورد", allowNegative = true)
        )
      }
    } catch (e: IllegalArgumentException) {
      throw e
    } catch (_: Exception) {
      throw IllegalArgumentException("بيانات جدول الموردين في النسخة الاحتياطية غير مكتملة أو تالفة")
    }

    val inventory = try {
      payload.requiredArray("inventoryItems").toList { o ->
        InventoryItemEntity(
          o.getLong("id"),
          o.getString("name"),
          o.optString("category", "مواد غذائية"),
          o.getInt("quantity").also { require(it in 0..10_000_000) { "كمية مخزون غير صحيحة" } },
          o.getDouble("purchasePrice").boundedMoney("سعر الشراء", allowNegative = false),
          o.getDouble("sellingPrice").boundedMoney("سعر البيع", allowNegative = false),
          o.optString("unit", "حبة")
        )
      }
    } catch (e: IllegalArgumentException) {
      throw e
    } catch (_: Exception) {
      throw IllegalArgumentException("بيانات جدول المخزون في النسخة الاحتياطية غير مكتملة أو تالفة")
    }

    val invoices = try {
      payload.requiredArray("invoices").toList { o ->
        InvoiceEntity(
          o.getLong("id"),
          o.getString("invoiceNumber"),
          o.getString("type"),
          o.getLong("partyId"),
          o.getString("partyName"),
          o.getLong("date").also { require(it > 0L) { "تاريخ الفاتورة غير صالح" } },
          o.getDouble("totalAmount").boundedMoney("إجمالي الفاتورة", allowNegative = false),
          o.getDouble("paidAmount").boundedMoney("المدفوع", allowNegative = false),
          o.getDouble("remainingAmount").boundedMoney("المتبقي", allowNegative = false),
          o.getString("status")
        )
      }
    } catch (e: IllegalArgumentException) {
      throw e
    } catch (_: Exception) {
      throw IllegalArgumentException("بيانات جدول الفواتير في النسخة الاحتياطية غير مكتملة أو تالفة")
    }

    val invoiceItems = try {
      payload.requiredArray("invoiceItems").toList { o ->
        InvoiceItemEntity(
          o.getLong("id"),
          o.getLong("invoiceId"),
          o.getLong("itemId"),
          o.getString("itemName"),
          o.getInt("quantity").also { require(it in 1..10_000_000) { "كمية صنف فاتورة غير صحيحة" } },
          o.getDouble("unitPrice").boundedUnitPrice("سعر الوحدة"),
          o.getDouble("totalPrice").boundedMoney("إجمالي الصنف", allowNegative = false),
          o.optInt("stockQuantity", 0).also { require(it in 0..10_000_000) { "كمية المخزون المحولة غير صحيحة" } },
          o.optString("unitLabel", ""),
          o.optInt("unitFactor", 1).also { require(it in 1..1_000_000) { "معامل الوحدة غير صحيح" } },
          if (!o.has("costPriceAtSale") || o.isNull("costPriceAtSale")) null else o.getDouble("costPriceAtSale").boundedMoney("تكلفة البيع وقت الفاتورة", allowNegative = false)
        )
      }
    } catch (e: IllegalArgumentException) {
      throw e
    } catch (_: Exception) {
      throw IllegalArgumentException("بيانات تفاصيل الفواتير في النسخة الاحتياطية غير مكتملة أو تالفة")
    }

    val transactions = try {
      payload.requiredArray("transactions").toList { o ->
        TransactionEntity(
          o.getLong("id"),
          o.getString("partyType"),
          o.getLong("partyId"),
          o.getString("partyName"),
          o.getString("type"),
          o.getDouble("amount").boundedMoney("مبلغ الحركة", allowNegative = false),
          o.optString("description", ""),
          o.getLong("date").also { require(it > 0L) { "تاريخ الحركة غير صالح" } },
          if (!o.has("invoiceId") || o.isNull("invoiceId")) null else o.getLong("invoiceId")
        )
      }
    } catch (e: IllegalArgumentException) {
      throw e
    } catch (_: Exception) {
      throw IllegalArgumentException("بيانات جدول الحركات المالية في النسخة الاحتياطية غير مكتملة أو تالفة")
    }

    val inventoryAdjustments = payload.optJSONArray("inventoryAdjustments")?.let { array ->
      (0 until array.length()).map { index ->
        val o = array.getJSONObject(index)
        InventoryAdjustmentEntity(o.getLong("id"), o.getLong("itemId"), o.getString("itemName"),
          o.getInt("previousQuantity"), o.getInt("newQuantity"), o.getInt("deltaQuantity"),
          o.optString("reason", "تسوية مخزون"), o.getLong("date"))
      }
    }.orEmpty()
    val notes = payload.optJSONArray("notes")?.let { array ->
      (0 until array.length()).map { index ->
        val o = array.getJSONObject(index)
        NoteEntity(
          id = o.getLong("id"),
          createdAt = o.getLong("createdAt").also { require(it > 0L) { "تاريخ الملاحظة غير صالح" } },
          updatedAt = o.getLong("updatedAt").also { require(it > 0L) { "تاريخ تحديث الملاحظة غير صالح" } },
          rightRowsJson = o.optString("rightRowsJson", "[]"),
          leftRowsJson = o.optString("leftRowsJson", "[]")
        )
      }
    }.orEmpty()
    require(notes.all { it.id > 0L && runCatching { JSONArray(it.rightRowsJson); JSONArray(it.leftRowsJson) }.isSuccess }) {
      "توجد بيانات ملاحظات غير صالحة في النسخة الاحتياطية"
    }
    require(notes.map { it.id }.distinct().size == notes.size) { "معرفات ملاحظات مكررة" }
    require(inventoryAdjustments.all { it.id > 0L && it.itemId > 0L && it.itemName.isNotBlank() && it.previousQuantity >= 0 && it.newQuantity >= 0 && it.deltaQuantity == it.newQuantity - it.previousQuantity && it.date > 0L }) { "توجد تسويات مخزون غير صالحة في النسخة الاحتياطية" }
    require(inventoryAdjustments.map { it.id }.distinct().size == inventoryAdjustments.size) { "معرفات تسويات مخزون مكررة" }

    require(customers.all { it.name.isNotBlank() } && suppliers.all { it.name.isNotBlank() } &&
      inventory.all { it.name.isNotBlank() }) { "يوجد سجل باسم فارغ في النسخة الاحتياطية" }
    require(customers.map { it.name.trim().lowercase(Locale.ROOT) }.distinct().size == customers.size) {
      "توجد أسماء عملاء مكررة في النسخة الاحتياطية"
    }
    require(suppliers.map { it.name.trim().lowercase(Locale.ROOT) }.distinct().size == suppliers.size) {
      "توجد أسماء موردين مكررة في النسخة الاحتياطية"
    }
    require(inventory.map { it.name.trim().lowercase(Locale.ROOT) }.distinct().size == inventory.size) {
      "توجد أسماء أصناف مخزون مكررة في النسخة الاحتياطية"
    }

    require(invoices.all { invoice ->
      invoice.invoiceNumber.isNotBlank() &&
        invoice.type in setOf("SALE", "CASH", "CREDIT", "PURCHASE") &&
        invoice.status in setOf("PAID", "UNPAID", "PARTIAL") &&
        invoice.totalAmount > 0 && invoice.paidAmount >= 0 && invoice.remainingAmount >= 0 &&
        invoice.paidAmount <= invoice.totalAmount + 0.01 &&
        abs(invoice.totalAmount - invoice.paidAmount - invoice.remainingAmount) <= 0.02
    }) { "توجد بيانات مالية أو حالة فاتورة غير متطابقة" }

    require(listOf(
      customers.map { it.id },
      suppliers.map { it.id },
      inventory.map { it.id },
      invoices.map { it.id },
      invoiceItems.map { it.id },
      transactions.map { it.id },
      notes.map { it.id },
      inventoryAdjustments.map { it.id }
    ).all { ids -> ids.all { it > 0 } && ids.distinct().size == ids.size }) {
      "توجد معرفات سجلات غير صحيحة أو مكررة"
    }
    require(invoices.map { it.invoiceNumber.trim() }.distinct().size == invoices.size) {
      "توجد أرقام فواتير مكررة"
    }

    val inventoryIds = inventory.map { it.id }.toSet()
    require(inventoryAdjustments.all { it.itemId in inventoryIds }) { "توجد تسوية مخزون مرتبطة بصنف غير موجود" }
    val invoiceIds = invoices.map { it.id }.toSet()
    require(invoiceItems.all { item ->
      item.invoiceId in invoiceIds &&
        item.itemName.isNotBlank() &&
        (item.itemId == 0L || item.itemId in inventoryIds) &&
        abs(item.totalPrice - GroceryViewModel.roundMoney(item.quantity.toDouble() * item.unitPrice)) <= 0.02
    }) { "توجد تفاصيل فاتورة غير مرتبطة أو بحسابات غير متطابقة" }

    val itemsByInvoice = invoiceItems.groupBy { it.invoiceId }
    require(invoices.all { invoice ->
      val lines = itemsByInvoice[invoice.id].orEmpty()
      lines.isNotEmpty() && abs(lines.sumOf { it.totalPrice } - invoice.totalAmount) <= 0.02
    }) { "إجمالي الفاتورة لا يطابق مجموع أصنافها في النسخة الاحتياطية" }

    val customerIds = customers.map { it.id }.toSet()
    val supplierIds = suppliers.map { it.id }.toSet()
    require(invoices.all { invoice ->
      invoice.partyId == 0L || if (invoice.type == "PURCHASE") invoice.partyId in supplierIds else invoice.partyId in customerIds
    }) { "توجد فاتورة مرتبطة بعميل أو مورد غير موجود" }

    require(transactions.all { transaction ->
      transaction.partyId > 0 && transaction.amount > 0 &&
        (transaction.invoiceId == null || transaction.invoiceId in invoiceIds) &&
        transaction.type in setOf("DEBIT (عليه)", "CREDIT (له)") &&
        when (transaction.partyType) {
          "CUSTOMER" -> transaction.partyId in customerIds
          "SUPPLIER" -> transaction.partyId in supplierIds
          else -> false
        }
    }) { "توجد حركة مالية مرتبطة بطرف غير موجود أو ببيانات غير صحيحة" }

    if (createSafetyBackup) {
      createSafetyBackupBeforeRestore(context)
    }

    val db = AppDatabase.getDatabase(context)
    db.withTransaction {
      db.invoiceDao().clearInvoiceItems()
      db.transactionDao().clearTransactions()
      db.inventoryAdjustmentDao().clearAdjustments()
      db.noteDao().clearNotes()
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
      db.inventoryAdjustmentDao().restoreAdjustments(inventoryAdjustments)
      db.noteDao().restoreNotes(notes)
    }

    try {
    payload.optJSONObject("settings")?.let { sObj ->
      val currentSettings = StorePreferences.getSettings(context)
      val merged = StoreSettings(
        storeName = sObj.optString("storeName", currentSettings.storeName).ifBlank { currentSettings.storeName },
        storePhone = sObj.optString("storePhone", currentSettings.storePhone),
        storeAddress = sObj.optString("storeAddress", currentSettings.storeAddress),
        currency = sObj.optString("currency", currentSettings.currency).ifBlank { currentSettings.currency },
        uiTextScale = sObj.optString("uiTextScale", currentSettings.uiTextScale),
        compactLists = sObj.optBoolean("compactLists", currentSettings.compactLists),
        defaultInvoiceType = sObj.optString("defaultInvoiceType", currentSettings.defaultInvoiceType),
        autoFillCashPaid = sObj.optBoolean("autoFillCashPaid", currentSettings.autoFillCashPaid),
        confirmBeforeDeleteInvoice = sObj.optBoolean("confirmBeforeDeleteInvoice", currentSettings.confirmBeforeDeleteInvoice),
        quickPartyCount = sObj.optInt("quickPartyCount", currentSettings.quickPartyCount),
        lowStockThreshold = sObj.optInt("lowStockThreshold", currentSettings.lowStockThreshold),
        defaultInventorySort = sObj.optString("defaultInventorySort", currentSettings.defaultInventorySort),
        defaultInventoryUnit = sObj.optString("defaultInventoryUnit", currentSettings.defaultInventoryUnit),
        defaultInventoryCategory = sObj.optString("defaultInventoryCategory", currentSettings.defaultInventoryCategory),
        defaultCustomerSort = sObj.optString("defaultCustomerSort", currentSettings.defaultCustomerSort),
        promptWhatsAppAfterTx = sObj.optBoolean("promptWhatsAppAfterTx", currentSettings.promptWhatsAppAfterTx),
        showZeroBalanceParties = sObj.optBoolean("showZeroBalanceParties", currentSettings.showZeroBalanceParties),
        defaultReportRange = sObj.optString("defaultReportRange", currentSettings.defaultReportRange),
        showUncostedProfitWarning = sObj.optBoolean("showUncostedProfitWarning", currentSettings.showUncostedProfitWarning),
        printerWidthMm = sObj.optInt("printerWidthMm", currentSettings.printerWidthMm),
        defaultPrinterAddress = sObj.optString("defaultPrinterAddress", currentSettings.defaultPrinterAddress),
        defaultPrinterName = sObj.optString("defaultPrinterName", currentSettings.defaultPrinterName),
        requireThermalPreview = sObj.optBoolean("requireThermalPreview", currentSettings.requireThermalPreview),
        autoOpenPdfAfterExport = sObj.optBoolean("autoOpenPdfAfterExport", currentSettings.autoOpenPdfAfterExport),
        preferWhatsAppDirect = sObj.optBoolean("preferWhatsAppDirect", currentSettings.preferWhatsAppDirect),
        autoSaveThermalImage = sObj.optBoolean("autoSaveThermalImage", currentSettings.autoSaveThermalImage),
        linkedFolderUri = currentSettings.linkedFolderUri
      )
      StorePreferences.saveAdvancedSettings(context, merged)
      val savedAddr = sObj.optString("defaultPrinterAddress", "")
      val savedName = sObj.optString("defaultPrinterName", "")
      if (savedAddr.isNotBlank()) {
        StorePreferences.saveDefaultPrinter(context, savedAddr, savedName)
      }
    }
    } catch (settingsError: Exception) {
      if (!createSafetyBackup) {
        throw IllegalStateException(
          "تعذر استعادة إعدادات النسخة الاحتياطية أثناء محاولة التراجع: ${settingsError.message ?: "خطأ غير معروف"}",
          settingsError
        )
      }
      val rollback = runCatching { restoreFromSafetyBackup(context) }
      if (rollback.isSuccess) {
        throw IllegalStateException(
          "فشل حفظ إعدادات النسخة الاحتياطية؛ تمت إعادة قاعدة البيانات والإعدادات إلى حالتهما قبل الاستعادة. لم تُعتمد الاستعادة.",
          settingsError
        )
      }
      throw IllegalStateException(
        "فشل حفظ إعدادات النسخة الاحتياطية، كما فشلت محاولة التراجع التلقائي. لا تتابع استخدام البيانات قبل استعادة ملف الأمان: ${rollback.exceptionOrNull()?.message ?: settingsError.message}",
        rollback.exceptionOrNull() ?: settingsError
      )
    }

    val debitTotal = transactions.filter { it.type.startsWith("DEBIT") }.sumOf { it.amount }
    val creditTotal = transactions.filter { it.type.startsWith("CREDIT") }.sumOf { it.amount }
    val invoiceTotal = invoices.sumOf { it.totalAmount }
    val invoiceRemaining = invoices.sumOf { it.remainingAmount }
    val customerBalanceTotal = customers.sumOf { it.balance }
    val supplierBalanceTotal = suppliers.sumOf { it.balance }
    val stockQuantityTotal = inventory.sumOf { it.quantity.toLong() }
    val adjustmentDeltaTotal = inventoryAdjustments.sumOf { it.deltaQuantity }
    val reconciliation = buildString {
      append("ملخص المطابقة: أرصدة العملاء ${GroceryViewModel.roundMoney(customerBalanceTotal)}، أرصدة الموردين ${GroceryViewModel.roundMoney(supplierBalanceTotal)}، إجمالي الفواتير ${GroceryViewModel.roundMoney(invoiceTotal)}، متبقي الفواتير ${GroceryViewModel.roundMoney(invoiceRemaining)}، الحركات المدينة ${GroceryViewModel.roundMoney(debitTotal)}، الحركات الدائنة ${GroceryViewModel.roundMoney(creditTotal)}، كمية المخزون ${stockQuantityTotal}، صافي التسويات ${adjustmentDeltaTotal}.")
      append(" لا يتم تعديل الأرصدة تلقائياً؛ قد تتضمن الأرصدة افتتاحيات أو بيانات تاريخية صحيحة.")
    }
    "تمت الاستعادة بنجاح: ${customers.size} عميل، ${suppliers.size} مورد، ${inventory.size} صنف، ${invoices.size} فاتورة.\n${reconciliation}"
  }

  private fun JSONObject.requiredArray(key: String): JSONArray =
    optJSONArray(key) ?: throw IllegalArgumentException("النسخة الاحتياطية لا تحتوي على قسم $key")

  private inline fun <T> JSONArray.toList(parse: (JSONObject) -> T): List<T> =
    (0 until length()).map { index -> parse(optJSONObject(index) ?: error("سجل غير صالح في النسخة الاحتياطية")) }

  private fun Double.boundedMoney(label: String, allowNegative: Boolean): Double {
    require(isFinite()) { "$label يحتوي على قيمة غير صالحة" }
    require(abs(this) <= MAX_MONETARY) { "$label يتجاوز الحد المحاسبي المسموح" }
    if (!allowNegative) {
      require(this >= 0.0) { "$label لا يمكن أن يكون سالباً" }
    }
    return GroceryViewModel.roundMoney(this)
  }

  private fun Double.boundedUnitPrice(label: String): Double {
    require(isFinite()) { "$label يحتوي على قيمة غير صالحة" }
    require(abs(this) <= MAX_MONETARY) { "$label يتجاوز الحد المحاسبي المسموح" }
    require(this >= 0.0) { "$label لا يمكن أن يكون سالباً" }
    return GroceryViewModel.roundUnitPrice(this)
  }

  private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
