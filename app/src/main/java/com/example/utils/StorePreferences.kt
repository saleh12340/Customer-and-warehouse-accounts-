package com.example.utils

import android.content.Context

data class StoreSettings(
  val storeName: String = "بقالة العزي للمواد الغذائية",
  val storePhone: String = "",
  val storeAddress: String = "",
  val currency: String = "ر.ي",
  val printerWidthMm: Int = 58,
  val defaultPrinterAddress: String = "",
  val defaultPrinterName: String = "",
  val linkedFolderUri: String = ""
)

object StorePreferences {
  private const val PREFS_NAME = "alaziy_store_prefs"
  private const val KEY_STORE_NAME = "store_name"
  private const val KEY_STORE_PHONE = "store_phone"
  private const val KEY_STORE_ADDRESS = "store_address"
  private const val KEY_CURRENCY = "currency"
  private const val KEY_PRINTER_WIDTH = "printer_width_mm"
  private const val KEY_PRINTER_ADDRESS = "default_printer_address"
  private const val KEY_PRINTER_NAME = "default_printer_name"
  private const val KEY_LINKED_FOLDER_URI = "linked_folder_uri"

  fun getSettings(context: Context): StoreSettings {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return StoreSettings(
      storeName = prefs.getString(KEY_STORE_NAME, "بقالة العزي للمواد الغذائية") ?: "بقالة العزي للمواد الغذائية",
      storePhone = prefs.getString(KEY_STORE_PHONE, "") ?: "",
      storeAddress = prefs.getString(KEY_STORE_ADDRESS, "") ?: "",
      currency = prefs.getString(KEY_CURRENCY, "ر.ي") ?: "ر.ي",
      printerWidthMm = prefs.getInt(KEY_PRINTER_WIDTH, 58).let { if (it == 80) 80 else 58 },
      defaultPrinterAddress = prefs.getString(KEY_PRINTER_ADDRESS, "") ?: "",
      defaultPrinterName = prefs.getString(KEY_PRINTER_NAME, "") ?: "",
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
