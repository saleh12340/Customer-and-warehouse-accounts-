package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "customers")
data class CustomerEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0L,
  val name: String,
  val phone: String,
  val address: String,
  val balance: Double = 0.0 // Positive: customer owes us (عليه), Negative: we owe customer (له)
)
