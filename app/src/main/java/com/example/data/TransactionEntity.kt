package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
  tableName = "transactions",
  indices = [Index(value = ["partyType", "partyId", "date"])]
)
data class TransactionEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0L,
  val partyType: String, // CUSTOMER, SUPPLIER
  val partyId: Long,
  val partyName: String,
  val type: String, // DEBIT (عليه), CREDIT (له)
  val amount: Double,
  val description: String,
  val date: Long,
  val invoiceId: Long? = null
)
