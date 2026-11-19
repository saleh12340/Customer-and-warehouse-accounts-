package com.example

import android.Manifest
import android.app.AlertDialog
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.bridge.AndroidBridge
import com.example.utils.AppDownloadsFolderManager
import com.example.utils.BackupRestoreManager
import com.example.utils.StorePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

  private lateinit var webView: WebView
  private lateinit var bridge: AndroidBridge

  private val bluetoothPermissionLauncher =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
      val granted = permissions.values.all { it }
      if (!granted) {
        Toast.makeText(this, "أذونات البلوتوث مطلوبة للاتصال بالطابعة الحرارية", Toast.LENGTH_SHORT).show()
      }
    }

  private val exportBackupLauncher =
    registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
      if (uri == null) return@registerForActivityResult
      lifecycleScope.launch {
        try {
          val msg = BackupRestoreManager.exportToUri(this@MainActivity, uri)
          bridge.onBackupOperationComplete(true, msg)
        } catch (e: Exception) {
          bridge.onBackupOperationComplete(false, e.message ?: "فشل إنشاء النسخة الاحتياطية")
        }
      }
    }

  private val importBackupLauncher =
    registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri == null) return@registerForActivityResult
      lifecycleScope.launch {
        try {
          val msg = BackupRestoreManager.restoreFromUri(this@MainActivity, uri)
          bridge.onBackupOperationComplete(true, msg)
        } catch (e: Exception) {
          bridge.onBackupOperationComplete(false, e.message ?: "فشلت استعادة النسخة الاحتياطية")
        }
      }
    }

  private val pickCustomerContactLauncher =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      val contactUri = result.data?.data ?: return@registerForActivityResult
      val projection = arrayOf(
        ContactsContract.Contacts.DISPLAY_NAME,
        ContactsContract.CommonDataKinds.Phone.NUMBER
      )
      try {
        contentResolver.query(contactUri, projection, null, null, null)?.use { cursor ->
          if (cursor.moveToFirst()) {
            val name = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME)).orEmpty()
            val phone = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)).orEmpty()
            bridge.onContactPicked(name, phone)
          } else {
            Toast.makeText(this, "تعذر قراءة جهة الاتصال المحددة", Toast.LENGTH_SHORT).show()
          }
        } ?: Toast.makeText(this, "تعذر فتح جهة الاتصال المحددة", Toast.LENGTH_SHORT).show()
      } catch (e: Exception) {
        Toast.makeText(this, "تعذر استيراد جهة الاتصال: ${e.message ?: ""}", Toast.LENGTH_SHORT).show()
      }
    }

  private val linkFolderLauncher =
    registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
      if (uri == null) return@registerForActivityResult
      val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
      runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
      StorePreferences.saveLinkedFolderUri(this, uri.toString())
      bridge.onFolderLinkResult(true, "تم ربط مجلد التنزيلات (بقاله العزي) بنجاح ✓")
    }

  @SuppressLint("SetJavaScriptEnabled")
  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    checkBluetoothPermissions()
    lifecycleScope.launch(Dispatchers.IO) {
      AppDownloadsFolderManager.ensureFolderInitialized(this@MainActivity)
    }

    val rootContainer = FrameLayout(this).apply {
      setBackgroundColor(Color.parseColor("#0F4C9C"))
    }

    webView = WebView(this)
    bridge = AndroidBridge(
      activity = this,
      webView = webView,
      onRequestBackupExport = {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        exportBackupLauncher.launch("alaziy_backup_$stamp.json")
      },
      onRequestBackupImport = {
        importBackupLauncher.launch(arrayOf("application/json", "*/*"))
      },
      onRequestLinkFolder = {
        linkFolderLauncher.launch(AppDownloadsFolderManager.buildSuggestedDownloadsInitialUri())
      },
      onRequestPickContact = {
        pickCustomerContactLauncher.launch(
          Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
        )
      }
    )

    webView.apply {
      settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        @Suppress("DEPRECATION")
        allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        allowUniversalAccessFromFileURLs = false
        cacheMode = WebSettings.LOAD_DEFAULT
      }
      webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
          val url = request?.url?.toString() ?: return true
          return !url.startsWith("file:///android_asset/www/")
        }
      }
      addJavascriptInterface(bridge, "Android")
      loadUrl("file:///android_asset/www/index.html")
    }

    rootContainer.addView(
      webView,
      FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT
      )
    )

    ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { view, windowInsets ->
      val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
      view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
      WindowInsetsCompat.CONSUMED
    }

    setContentView(rootContainer)

    val backCallback = object : OnBackPressedCallback(true) {
      override fun handleOnBackPressed() {
        webView.evaluateJavascript(
          "(function(){ return window.handleAndroidBack ? window.handleAndroidBack() : 'EXIT'; })()"
        ) { result ->
          val cleaned = result?.trim('"')
          if (cleaned == "EXIT") {
            AlertDialog.Builder(this@MainActivity)
              .setTitle("تأكيد الخروج")
              .setMessage("هل تريد الخروج من تطبيق العزي؟")
              .setPositiveButton("خروج") { _, _ -> finish() }
              .setNegativeButton("البقاء في التطبيق", null)
              .setCancelable(true)
              .show()
          } else if (cleaned != "HANDLED") {
            // Fail safe: do not silently close the app on an unexpected WebView response.
            Toast.makeText(this@MainActivity, "تعذر الرجوع داخل التطبيق", Toast.LENGTH_SHORT).show()
          }
        }
      }
    }
    onBackPressedDispatcher.addCallback(this, backCallback)
  }

  override fun onDestroy() {
    if (::webView.isInitialized) {
      webView.removeJavascriptInterface("Android")
      webView.destroy()
    }
    super.onDestroy()
  }

  private fun checkBluetoothPermissions() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      val scan = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
      val connect = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
      if (scan != PackageManager.PERMISSION_GRANTED || connect != PackageManager.PERMISSION_GRANTED) {
        bluetoothPermissionLauncher.launch(
          arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        )
      }
    }
  }
}
