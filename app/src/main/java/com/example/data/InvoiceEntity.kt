package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
  tableName = "invoices",
  indices = [Index(value = ["invoiceNumber"], unique = true)]
)
data class InvoiceEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0L,
  val invoiceNumber: String,
  val type: String, // SALE, PURCHASE, CASH, CREDIT
  val partyId: Long,
  val partyName: String,
  val date: Long,
  val totalAmount: Double,
  val paidAmount: Double,
  val remainingAmount: Double,
  val status: String // PAID, UNPAID, PARTIAL
)
