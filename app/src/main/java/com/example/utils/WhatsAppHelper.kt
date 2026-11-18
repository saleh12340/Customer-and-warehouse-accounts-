package com.example.utils

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast

object WhatsAppHelper {

  /**
   * Converts Arabic-Indic (٠-٩) and Eastern Arabic-Indic (۰-۹) digits to ASCII '0'-'9',
   * then normalizes Yemeni phone numbers to +967 format while preserving valid international numbers.
   */
  fun normalizeYemeniPhone(phone: String): String {
    val trimmed = toAsciiDigits(phone).trim()
    if (trimmed.isEmpty()) return ""
    val hasPlus = trimmed.startsWith("+")
    val digits = trimmed.replace(Regex("[^\\d]"), "")
    if (digits.isEmpty()) return ""

    return when {
      hasPlus -> "+$digits"
      digits.startsWith("00") && digits.length > 4 -> "+${digits.substring(2)}"
      digits.startsWith("967") && digits.length >= 12 -> "+$digits"
      digits.length == 10 && digits.startsWith("07") -> "+967${digits.substring(1)}"
      digits.length == 9 && digits.startsWith("7") -> "+967$digits"
      else -> digits
    }
  }

  private fun toAsciiDigits(input: String): String {
    if (input.isEmpty()) return input
    val sb = StringBuilder(input.length)
    for (ch in input) {
      when (ch) {
        in '\u0660'..'\u0669' -> sb.append(('0'.code + (ch.code - 0x0660)).toChar())
        in '\u06F0'..'\u06F9' -> sb.append(('0'.code + (ch.code - 0x06F0)).toChar())
        else -> sb.append(ch)
      }
    }
    return sb.toString()
  }

  fun findInstalledWhatsAppPackage(context: Context): String? {
    val pm = context.packageManager
    val candidates = listOf("com.whatsapp", "com.whatsapp.w4b")
    for (pkg in candidates) {
      try {
        pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        return pkg
      } catch (_: Throwable) {
        try {
          @Suppress("DEPRECATION")
          pm.getPackageInfo(pkg, 0)
          return pkg
        } catch (_: Throwable) {}
      }
    }
    return null
  }

  fun shareText(context: Context, phone: String, message: String): Boolean {
    val normalizedPhone = normalizeYemeniPhone(phone)
    val whatsAppPkg = findInstalledWhatsAppPackage(context)
    if (whatsAppPkg != null && normalizedPhone.startsWith("+")) {
      try {
        val phoneDigits = normalizedPhone.removePrefix("+")
        val url = "https://api.whatsapp.com/send?phone=$phoneDigits&text=${Uri.encode(message)}"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
          setPackage(whatsAppPkg)
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return true
      } catch (_: Exception) {}
    }

    if (normalizedPhone.startsWith("+")) {
      try {
        val phoneDigits = normalizedPhone.removePrefix("+")
        val url = "https://api.whatsapp.com/send?phone=$phoneDigits&text=${Uri.encode(message)}"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return true
      } catch (_: Exception) {}
    }

    return try {
      val fallback = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, message)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(Intent.createChooser(fallback, "مشاركة عبر").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      })
      true
    } catch (e: Exception) {
      Toast.makeText(context, "تعذر فتح تطبيق المشاركة: ${e.message ?: ""}", Toast.LENGTH_SHORT).show()
      false
    }
  }

  fun shareFile(
    context: Context,
    fileUri: Uri,
    mimeType: String,
    message: String,
    phone: String = "",
    preferWhatsApp: Boolean = true
  ): Boolean {
    val normalizedPhone = normalizeYemeniPhone(phone)
    val whatsAppPkg = if (preferWhatsApp) findInstalledWhatsAppPackage(context) else null

    if (whatsAppPkg != null) {
      try {
        val intent = Intent(Intent.ACTION_SEND).apply {
          type = mimeType
          setPackage(whatsAppPkg)
          putExtra(Intent.EXTRA_STREAM, fileUri)
          clipData = ClipData.newRawUri("", fileUri)
          if (message.isNotBlank()) putExtra(Intent.EXTRA_TEXT, message)
          if (normalizedPhone.startsWith("+")) {
            putExtra("jid", "${normalizedPhone.removePrefix("+")}@s.whatsapp.net")
          }
          addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return true
      } catch (_: Exception) {}
    }

    return try {
      val chooserIntent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, fileUri)
        clipData = ClipData.newRawUri("", fileUri)
        if (message.isNotBlank()) putExtra(Intent.EXTRA_TEXT, message)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(Intent.createChooser(chooserIntent, "مشاركة الملف").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      })
      true
    } catch (e: Exception) {
      Toast.makeText(context, "تعذر مشاركة الملف: ${e.message}", Toast.LENGTH_SHORT).show()
      false
    }
  }

  fun openPdf(context: Context, fileUri: Uri): Boolean {
    return try {
      val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(fileUri, "application/pdf")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(Intent.createChooser(intent, "فتح ملف PDF").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      })
      true
    } catch (e: Exception) {
      Toast.makeText(context, "لا يوجد تطبيق لعرض ملفات PDF", Toast.LENGTH_SHORT).show()
      false
    }
  }
}
