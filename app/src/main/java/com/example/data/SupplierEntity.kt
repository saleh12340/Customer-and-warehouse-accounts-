package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "suppliers")
data class SupplierEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0L,
  val name: String,
  val phone: String,
  val company: String,
  val balance: Double = 0.0
)
