package com.example.utils

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

object BluetoothPrinterManager {

  private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
  private var bluetoothSocket: BluetoothSocket? = null
  private var outputStream: OutputStream? = null

  @SuppressLint("MissingPermission")
  fun getPairedDevices(): Set<BluetoothDevice> {
    return try {
      val adapter = BluetoothAdapter.getDefaultAdapter()
      adapter?.bondedDevices ?: emptySet()
    } catch (_: SecurityException) {
      // The user may deny Bluetooth permission; opening Settings must not crash.
      emptySet()
    }
  }

  @SuppressLint("MissingPermission")
  fun connect(device: BluetoothDevice): Boolean {
    try {
      disconnect()
      val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
      socket.connect()
      bluetoothSocket = socket
      outputStream = socket.outputStream
      return true
    } catch (e: Exception) {
      e.printStackTrace()
      try {
        bluetoothSocket?.close()
      } catch (_: Exception) {}
      bluetoothSocket = null
      outputStream = null
      return false
    }
  }

  fun disconnect() {
    try {
      outputStream?.close()
      bluetoothSocket?.close()
    } catch (_: Exception) {}
    outputStream = null
    bluetoothSocket = null
  }

  fun isConnected(): Boolean {
    return bluetoothSocket?.isConnected == true && outputStream != null
  }

  fun printReceiptText(title: String, details: List<String>, footer: String): Boolean {
    // Render as a bitmap so Arabic shaping and RTL order do not depend on the
    // thermal printer's configured legacy character code page.
    val receipt = buildString {
      appendLine("بقالة العزي للمواد الغذائية")
      appendLine(title)
      appendLine("================================")
      details.forEach { appendLine(it) }
      appendLine("--------------------------------")
      appendLine(footer)
      appendLine("شكراً لزيارتكم")
    }
    return printReceiptBitmap(receipt)
  }

  /**
   * Generates a thermal bitmap for Arabic receipts and prints it as a raster image via ESC/POS.
   */
  fun printReceiptBitmap(receiptText: String): Boolean {
    val out = outputStream ?: return false
    try {
      val width = 384 // standard 58mm printer dot width
      val textPaint = TextPaint().apply {
        color = Color.BLACK
        textSize = 20f
        isAntiAlias = true
        typeface = Typeface.DEFAULT
      }

      val staticLayout = StaticLayout.Builder.obtain(
        receiptText, 0, receiptText.length, textPaint, width
      )
        .setAlignment(Layout.Alignment.ALIGN_CENTER)
        .setTextDirection(TextDirectionHeuristics.RTL)
        .setIncludePad(false)
        .setLineSpacing(0f, 1.2f)
        .build()

      val height = staticLayout.height + 40
      val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
      val canvas = Canvas(bitmap)
      canvas.drawColor(Color.WHITE)
      canvas.translate(0f, 20f)
      staticLayout.draw(canvas)

      // Convert bitmap to ESC/POS raster bit image and send
      val bytes = convertBitmapToEscPos(bitmap)
      out.write(byteArrayOf(0x1B, 0x40)) // Init
      out.write(bytes)
      out.write(byteArrayOf(0x1D, 0x56, 0x42, 0x00)) // Cut
      out.flush()
      return true
    } catch (e: Exception) {
      e.printStackTrace()
      return false
    }
  }

  private fun convertBitmapToEscPos(bitmap: Bitmap): ByteArray {
    val width = bitmap.width
    val height = bitmap.height
    val bytesWidth = (width + 7) / 8
    val command = mutableListOf<Byte>()

    // GS v 0 command for raster bit image
    command.add(0x1D.toByte())
    command.add(0x76.toByte())
    command.add(0x30.toByte())
    command.add(0x00.toByte())
    command.add((bytesWidth and 0xFF).toByte())
    command.add(((bytesWidth shr 8) and 0xFF).toByte())
    command.add((height and 0xFF).toByte())
    command.add(((height shr 8) and 0xFF).toByte())

    for (y in 0 until height) {
      for (xBytes in 0 until bytesWidth) {
        var byteVal = 0
        for (bit in 0..7) {
          val x = xBytes * 8 + bit
          if (x < width) {
            val pixel = bitmap.getPixel(x, y)
            val red = Color.red(pixel)
            val green = Color.green(pixel)
            val blue = Color.blue(pixel)
            val luminance = (0.299 * red + 0.587 * green + 0.114 * blue).toInt()
            if (luminance < 128) {
              byteVal = byteVal or (1 shl (7 - bit))
            }
          }
        }
        command.add(byteVal.toByte())
      }
    }
    return command.toByteArray()
  }
}
