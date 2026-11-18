package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
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
import com.example.utils.BackupRestoreManager
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

  @SuppressLint("SetJavaScriptEnabled")
  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    checkBluetoothPermissions()

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
          if (cleaned != "HANDLED") {
            isEnabled = false
            onBackPressedDispatcher.onBackPressed()
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
