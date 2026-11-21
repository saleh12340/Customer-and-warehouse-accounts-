package com.example.utils

import android.content.Context

data class StoreSettings(
  // 1. General
  val storeName: String = "بقالة العزي للمواد الغذائية",
  val storePhone: String = "",
  val storeAddress: String = "",
  val currency: String = "ر.ي",
  val uiTextScale: String = "NORMAL",
  val compactLists: Boolean = false,
  // 2. Invoices
  val defaultInvoiceType: String = "CASH",
  val autoFillCashPaid: Boolean = true,
  val confirmBeforeDeleteInvoice: Boolean = true,
  val quickPartyCount: Int = 5,
  // 3. Inventory
  val lowStockThreshold: Int = 10,
  val defaultInventorySort: String = "NAME",
  val defaultInventoryUnit: String = "حبة",
  val defaultInventoryCategory: String = "مواد غذائية",
  // 4. Customers & Suppliers
  val defaultCustomerSort: String = "NAME",
  val promptWhatsAppAfterTx: Boolean = true,
  val showZeroBalanceParties: Boolean = true,
  // 5. Reports
  val defaultReportRange: String = "ALL",
  val showUncostedProfitWarning: Boolean = true,
  // 6. Printing & Sharing
  val printerWidthMm: Int = 58,
  val defaultPrinterAddress: String = "",
  val defaultPrinterName: String = "",
  val requireThermalPreview: Boolean = true,
  val autoOpenPdfAfterExport: Boolean = true,
  val preferWhatsAppDirect: Boolean = true,
  val autoSaveThermalImage: Boolean = false,
  // 7. Backup & Downloads Folder
  val linkedFolderUri: String = ""
)

object StorePreferences {
  private const val PREFS_NAME = "alaziy_store_prefs"

  private const val KEY_STORE_NAME = "store_name"
  private const val KEY_STORE_PHONE = "store_phone"
  private const val KEY_STORE_ADDRESS = "store_address"
  private const val KEY_CURRENCY = "currency"
  private const val KEY_UI_TEXT_SCALE = "ui_text_scale"
  private const val KEY_COMPACT_LISTS = "compact_lists"

  private const val KEY_DEFAULT_INVOICE_TYPE = "default_invoice_type"
  private const val KEY_AUTO_FILL_CASH_PAID = "auto_fill_cash_paid"
  private const val KEY_CONFIRM_DELETE_INVOICE = "confirm_delete_invoice"
  private const val KEY_QUICK_PARTY_COUNT = "quick_party_count"

  private const val KEY_LOW_STOCK_THRESHOLD = "low_stock_threshold"
  private const val KEY_DEFAULT_INVENTORY_SORT = "default_inventory_sort"
  private const val KEY_DEFAULT_INVENTORY_UNIT = "default_inventory_unit"
  private const val KEY_DEFAULT_INVENTORY_CATEGORY = "default_inventory_category"

  private const val KEY_DEFAULT_CUSTOMER_SORT = "default_customer_sort"
  private const val KEY_PROMPT_WA_AFTER_TX = "prompt_wa_after_tx"
  private const val KEY_SHOW_ZERO_BALANCE = "show_zero_balance_parties"

  private const val KEY_DEFAULT_REPORT_RANGE = "default_report_range"
  private const val KEY_SHOW_UNCOSTED_WARNING = "show_uncosted_profit_warning"

  private const val KEY_PRINTER_WIDTH = "printer_width_mm"
  private const val KEY_PRINTER_ADDRESS = "default_printer_address"
  private const val KEY_PRINTER_NAME = "default_printer_name"
  private const val KEY_REQUIRE_THERMAL_PREVIEW = "require_thermal_preview"
  private const val KEY_AUTO_OPEN_PDF = "auto_open_pdf"
  private const val KEY_PREFER_WA_DIRECT = "prefer_wa_direct"
  private const val KEY_AUTO_SAVE_THERMAL_IMAGE = "auto_save_thermal_image"

  private const val KEY_LINKED_FOLDER_URI = "linked_folder_uri"

  fun getSettings(context: Context): StoreSettings {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val scale = prefs.getString(KEY_UI_TEXT_SCALE, "NORMAL")?.uppercase().let {
      if (it in setOf("SMALL", "NORMAL", "LARGE")) it!! else "NORMAL"
    }
    val defInvType = prefs.getString(KEY_DEFAULT_INVOICE_TYPE, "CASH")?.uppercase().let {
      if (it in setOf("CASH", "CREDIT", "PURCHASE")) it!! else "CASH"
    }
    val defInvSort = prefs.getString(KEY_DEFAULT_INVENTORY_SORT, "NAME")?.uppercase().let {
      if (it in setOf("NAME", "QTY_ASC", "QTY_DESC", "VALUE_DESC")) it!! else "NAME"
    }
    val defCustSort = prefs.getString(KEY_DEFAULT_CUSTOMER_SORT, "NAME")?.uppercase().let {
      if (it in setOf("NAME", "BALANCE_DESC", "BALANCE_ASC")) it!! else "NAME"
    }
    val defRepRange = prefs.getString(KEY_DEFAULT_REPORT_RANGE, "ALL")?.uppercase().let {
      if (it in setOf("ALL", "TODAY", "WEEK", "MONTH")) it!! else "ALL"
    }
    return StoreSettings(
      storeName = prefs.getString(KEY_STORE_NAME, "بقالة العزي للمواد الغذائية") ?: "بقالة العزي للمواد الغذائية",
      storePhone = prefs.getString(KEY_STORE_PHONE, "") ?: "",
      storeAddress = prefs.getString(KEY_STORE_ADDRESS, "") ?: "",
      currency = prefs.getString(KEY_CURRENCY, "ر.ي") ?: "ر.ي",
      uiTextScale = scale,
      compactLists = prefs.getBoolean(KEY_COMPACT_LISTS, false),
      defaultInvoiceType = defInvType,
      autoFillCashPaid = prefs.getBoolean(KEY_AUTO_FILL_CASH_PAID, true),
      confirmBeforeDeleteInvoice = prefs.getBoolean(KEY_CONFIRM_DELETE_INVOICE, true),
      quickPartyCount = prefs.getInt(KEY_QUICK_PARTY_COUNT, 5).coerceIn(3, 12),
      lowStockThreshold = prefs.getInt(KEY_LOW_STOCK_THRESHOLD, 10).coerceIn(1, 500),
      defaultInventorySort = defInvSort,
      defaultInventoryUnit = prefs.getString(KEY_DEFAULT_INVENTORY_UNIT, "حبة")?.ifBlank { "حبة" } ?: "حبة",
      defaultInventoryCategory = prefs.getString(KEY_DEFAULT_INVENTORY_CATEGORY, "مواد غذائية")?.ifBlank { "مواد غذائية" } ?: "مواد غذائية",
      defaultCustomerSort = defCustSort,
      promptWhatsAppAfterTx = prefs.getBoolean(KEY_PROMPT_WA_AFTER_TX, true),
      showZeroBalanceParties = prefs.getBoolean(KEY_SHOW_ZERO_BALANCE, true),
      defaultReportRange = defRepRange,
      showUncostedProfitWarning = prefs.getBoolean(KEY_SHOW_UNCOSTED_WARNING, true),
      printerWidthMm = prefs.getInt(KEY_PRINTER_WIDTH, 58).let { if (it == 80) 80 else 58 },
      defaultPrinterAddress = prefs.getString(KEY_PRINTER_ADDRESS, "") ?: "",
      defaultPrinterName = prefs.getString(KEY_PRINTER_NAME, "") ?: "",
      requireThermalPreview = prefs.getBoolean(KEY_REQUIRE_THERMAL_PREVIEW, true),
      autoOpenPdfAfterExport = prefs.getBoolean(KEY_AUTO_OPEN_PDF, true),
      preferWhatsAppDirect = prefs.getBoolean(KEY_PREFER_WA_DIRECT, true),
      autoSaveThermalImage = prefs.getBoolean(KEY_AUTO_SAVE_THERMAL_IMAGE, false),
      linkedFolderUri = prefs.getString(KEY_LINKED_FOLDER_URI, "") ?: ""
    )
  }

  fun saveStoreInfo(context: Context, storeName: String, storePhone: String, storeAddress: String, currency: String) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit()
      .putString(KEY_STORE_NAME, storeName.trim().ifBlank { "بقالة العزي للمواد الغذائية" })
      .putString(KEY_STORE_PHONE, storePhone.trim())
      .putString(KEY_STORE_ADDRESS, storeAddress.trim())
      .putString(KEY_CURRENCY, currency.trim().ifBlank { "ر.ي" })
      .apply()
  }

  fun saveAdvancedSettings(context: Context, settings: StoreSettings) {
    advancedSettingsEditor(context, settings).apply()
  }

  /** Synchronous persistence for critical backup/restore operations. */
  fun saveAdvancedSettingsChecked(context: Context, settings: StoreSettings): Boolean =
    advancedSettingsEditor(context, settings).commit()

  private fun advancedSettingsEditor(context: Context, settings: StoreSettings): android.content.SharedPreferences.Editor {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return prefs.edit()
      .putString(KEY_STORE_NAME, settings.storeName.trim().ifBlank { "بقالة العزي للمواد الغذائية" })
      .putString(KEY_STORE_PHONE, settings.storePhone.trim())
      .putString(KEY_STORE_ADDRESS, settings.storeAddress.trim())
      .putString(KEY_CURRENCY, settings.currency.trim().ifBlank { "ر.ي" })
      .putString(KEY_UI_TEXT_SCALE, if (settings.uiTextScale in setOf("SMALL", "NORMAL", "LARGE")) settings.uiTextScale else "NORMAL")
      .putBoolean(KEY_COMPACT_LISTS, settings.compactLists)
      .putString(KEY_DEFAULT_INVOICE_TYPE, if (settings.defaultInvoiceType in setOf("CASH", "CREDIT", "PURCHASE")) settings.defaultInvoiceType else "CASH")
      .putBoolean(KEY_AUTO_FILL_CASH_PAID, settings.autoFillCashPaid)
      .putBoolean(KEY_CONFIRM_DELETE_INVOICE, settings.confirmBeforeDeleteInvoice)
      .putInt(KEY_QUICK_PARTY_COUNT, settings.quickPartyCount.coerceIn(3, 12))
      .putInt(KEY_LOW_STOCK_THRESHOLD, settings.lowStockThreshold.coerceIn(1, 500))
      .putString(KEY_DEFAULT_INVENTORY_SORT, if (settings.defaultInventorySort in setOf("NAME", "QTY_ASC", "QTY_DESC", "VALUE_DESC")) settings.defaultInventorySort else "NAME")
      .putString(KEY_DEFAULT_INVENTORY_UNIT, settings.defaultInventoryUnit.trim().ifBlank { "حبة" })
      .putString(KEY_DEFAULT_INVENTORY_CATEGORY, settings.defaultInventoryCategory.trim().ifBlank { "مواد غذائية" })
      .putString(KEY_DEFAULT_CUSTOMER_SORT, if (settings.defaultCustomerSort in setOf("NAME", "BALANCE_DESC", "BALANCE_ASC")) settings.defaultCustomerSort else "NAME")
      .putBoolean(KEY_PROMPT_WA_AFTER_TX, settings.promptWhatsAppAfterTx)
      .putBoolean(KEY_SHOW_ZERO_BALANCE, settings.showZeroBalanceParties)
      .putString(KEY_DEFAULT_REPORT_RANGE, if (settings.defaultReportRange in setOf("ALL", "TODAY", "WEEK", "MONTH")) settings.defaultReportRange else "ALL")
      .putBoolean(KEY_SHOW_UNCOSTED_WARNING, settings.showUncostedProfitWarning)
      .putInt(KEY_PRINTER_WIDTH, if (settings.printerWidthMm == 80) 80 else 58)
      .putBoolean(KEY_REQUIRE_THERMAL_PREVIEW, settings.requireThermalPreview)
      .putBoolean(KEY_AUTO_OPEN_PDF, settings.autoOpenPdfAfterExport)
      .putBoolean(KEY_PREFER_WA_DIRECT, settings.preferWhatsAppDirect)
      .putBoolean(KEY_AUTO_SAVE_THERMAL_IMAGE, settings.autoSaveThermalImage)
  }

  fun resetAdvancedSettings(context: Context) {
    val current = getSettings(context)
    val defaults = StoreSettings(
      defaultPrinterAddress = current.defaultPrinterAddress,
      defaultPrinterName = current.defaultPrinterName,
      linkedFolderUri = current.linkedFolderUri
    )
    saveAdvancedSettings(context, defaults)
  }

  fun savePrinterWidth(context: Context, widthMm: Int) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .edit()
      .putInt(KEY_PRINTER_WIDTH, if (widthMm == 80) 80 else 58)
      .apply()
  }

  fun saveDefaultPrinter(context: Context, address: String, name: String) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .edit()
      .putString(KEY_PRINTER_ADDRESS, address)
      .putString(KEY_PRINTER_NAME, name)
      .apply()
  }

  fun saveDefaultPrinterChecked(context: Context, address: String, name: String): Boolean =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .edit()
      .putString(KEY_PRINTER_ADDRESS, address)
      .putString(KEY_PRINTER_NAME, name)
      .commit()

  fun saveLinkedFolderUri(context: Context, uriString: String) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .edit()
      .putString(KEY_LINKED_FOLDER_URI, uriString.trim())
      .apply()
  }

  fun clearLinkedFolderUri(context: Context) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .edit()
      .remove(KEY_LINKED_FOLDER_URI)
      .apply()
  }
}
