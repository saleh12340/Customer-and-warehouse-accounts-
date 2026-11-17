package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "invoice_items")
data class InvoiceItemEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0L,
  val invoiceId: Long,
  val itemId: Long,
  val itemName: String,
  val quantity: Int,
  val unitPrice: Double,
  val totalPrice: Double
)
