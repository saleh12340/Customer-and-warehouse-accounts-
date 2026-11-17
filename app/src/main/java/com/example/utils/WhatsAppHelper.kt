package com.example.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

object WhatsAppHelper {

  /**
   * Normalizes phone number for Yemen (+967).
   * E.g. "771234567" -> "+967771234567" or "00967771234567" -> "+967771234567"
   */
  fun normalizeYemeniPhone(phone: String): String {
    val cleaned = phone.replace(Regex("[^\\d+]"), "")
    if (cleaned.startsWith("+967")) return cleaned
    if (cleaned.startsWith("00967")) return "+" + cleaned.substring(2)
    if (cleaned.startsWith("967")) return "+$cleaned"
    if (cleaned.length == 9 && cleaned.startsWith("7")) return "+967$cleaned"
    return cleaned
  }

  fun shareText(context: Context, phone: String, message: String) {
    try {
      val normalizedPhone = normalizeYemeniPhone(phone)
      val url = "https://api.whatsapp.com/send?phone=$normalizedPhone&text=${Uri.encode(message)}"
      val intent = Intent(Intent.ACTION_VIEW).apply {
        data = Uri.parse(url)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
    } catch (e: Exception) {
      // Fallback to general share sheet
      val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, message)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(Intent.createChooser(intent, "مشاركة عبر واتساب").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      })
    }
  }

  fun shareFile(context: Context, fileUri: Uri, mimeType: String, message: String) {
    try {
      val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, fileUri)
        putExtra(Intent.EXTRA_TEXT, message)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(Intent.createChooser(intent, "مشاركة الفاتورة").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      })
    } catch (e: Exception) {
      Toast.makeText(context, "تعذر مشاركة الملف: ${e.message}", Toast.LENGTH_SHORT).show()
    }
  }
}
