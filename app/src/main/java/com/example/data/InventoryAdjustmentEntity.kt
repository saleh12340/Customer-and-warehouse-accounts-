package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "inventory_adjustments", indices = [Index(value = ["itemId", "date"])])
data class InventoryAdjustmentEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0L,
  val itemId: Long,
  val itemName: String,
  val previousQuantity: Int,
  val newQuantity: Int,
  val deltaQuantity: Int,
  val reason: String,
  val date: Long
)
