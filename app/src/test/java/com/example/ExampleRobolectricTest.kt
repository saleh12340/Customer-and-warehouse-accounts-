package com.example

import android.content.Context
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.example.utils.StorePreferences
import com.example.utils.StoreSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// Robolectric 4.16.1 supports API 35 reliably; API 36 is not available
// in this test runtime and causes DefaultSdkProvider to throw.
@Config(sdk = [35])
class ExampleRobolectricTest {

  @Test
  fun `read app name from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("العزي | الحسابات والمخزون", appName)
  }

  @Test
  fun `advanced settings persist clamp values and reset while preserving printer and linked folder`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    StorePreferences.saveDefaultPrinter(context, "00:11:22:33:44:55", "Xprinter-58")
    StorePreferences.saveLinkedFolderUri(context, "content://com.android.externalstorage.documents/tree/primary%3ADownload")

    StorePreferences.saveAdvancedSettings(
      context,
      StoreSettings(
        storeName = "بقالة العزي الحديثة",
        storePhone = "777437514",
        storeAddress = "صنعاء - شارع تعز",
        currency = "ريال",
        uiTextScale = "LARGE",
        compactLists = true,
        defaultInvoiceType = "CREDIT",
        autoFillCashPaid = false,
        confirmBeforeDeleteInvoice = false,
        quickPartyCount = 99, // must clamp to 12
        lowStockThreshold = 0, // must clamp to 1
        defaultInventorySort = "QTY_ASC",
        defaultInventoryUnit = "كرتون",
        defaultInventoryCategory = "مشروبات",
        defaultCustomerSort = "BALANCE_DESC",
        promptWhatsAppAfterTx = false,
        showZeroBalanceParties = false,
        defaultReportRange = "MONTH",
        showUncostedProfitWarning = false,
        printerWidthMm = 80,
        requireThermalPreview = false,
        autoOpenPdfAfterExport = false,
        preferWhatsAppDirect = false,
        autoSaveThermalImage = true
      )
    )

    val saved = StorePreferences.getSettings(context)
    assertEquals("بقالة العزي الحديثة", saved.storeName)
    assertEquals("LARGE", saved.uiTextScale)
    assertTrue(saved.compactLists)
    assertEquals("CREDIT", saved.defaultInvoiceType)
    assertFalse(saved.autoFillCashPaid)
    assertFalse(saved.confirmBeforeDeleteInvoice)
    assertEquals(12, saved.quickPartyCount)
    assertEquals(1, saved.lowStockThreshold)
    assertEquals("QTY_ASC", saved.defaultInventorySort)
    assertEquals("كرتون", saved.defaultInventoryUnit)
    assertEquals("مشروبات", saved.defaultInventoryCategory)
    assertEquals("BALANCE_DESC", saved.defaultCustomerSort)
    assertFalse(saved.promptWhatsAppAfterTx)
    assertFalse(saved.showZeroBalanceParties)
    assertEquals("MONTH", saved.defaultReportRange)
    assertFalse(saved.showUncostedProfitWarning)
    assertEquals(80, saved.printerWidthMm)
    assertFalse(saved.requireThermalPreview)
    assertFalse(saved.autoOpenPdfAfterExport)
    assertFalse(saved.preferWhatsAppDirect)
    assertTrue(saved.autoSaveThermalImage)
    assertEquals("00:11:22:33:44:55", saved.defaultPrinterAddress)
    assertEquals("Xprinter-58", saved.defaultPrinterName)

    StorePreferences.resetAdvancedSettings(context)
    val afterReset = StorePreferences.getSettings(context)
    assertEquals("بقالة العزي للمواد الغذائية", afterReset.storeName)
    assertEquals("NORMAL", afterReset.uiTextScale)
    assertFalse(afterReset.compactLists)
    assertEquals("CASH", afterReset.defaultInvoiceType)
    assertTrue(afterReset.autoFillCashPaid)
    assertEquals(5, afterReset.quickPartyCount)
    assertEquals(10, afterReset.lowStockThreshold)
    assertEquals(58, afterReset.printerWidthMm)
    assertTrue(afterReset.requireThermalPreview)
    assertEquals("00:11:22:33:44:55", afterReset.defaultPrinterAddress)
    assertEquals("content://com.android.externalstorage.documents/tree/primary%3ADownload", afterReset.linkedFolderUri)
  }

  @Test
  fun `main activity does not set FLAG_SECURE and initializes WebView cleanly`() {
    val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
    val activity = controller.get()
    val flags = activity.window.attributes.flags
    assertEquals(0, flags and WindowManager.LayoutParams.FLAG_SECURE)
    controller.pause().stop().destroy()
  }
}
