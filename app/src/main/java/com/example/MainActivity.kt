package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.bridge.AndroidBridge

class MainActivity : ComponentActivity() {

  private lateinit var webView: WebView

  private val bluetoothPermissionLauncher =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
      val granted = permissions.values.all { it }
      if (!granted) {
        Toast.makeText(this, "أذونات البلوتوث مطلوبة للطباعة", Toast.LENGTH_SHORT).show()
      }
    }

  @SuppressLint("SetJavaScriptEnabled")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    checkBluetoothPermissions()

    webView = WebView(this).apply {
      settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = true
        databaseEnabled = true
        setCacheMode(WebSettings.LOAD_DEFAULT)
      }
      addJavascriptInterface(AndroidBridge(this@MainActivity, this), "Android")
      loadUrl("file:///android_asset/www/index.html")
    }

    setContentView(webView)
  }

  override fun onBackPressed() {
    if (webView.canGoBack()) {
      webView.goBack()
    } else {
      super.onBackPressed()
    }
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
