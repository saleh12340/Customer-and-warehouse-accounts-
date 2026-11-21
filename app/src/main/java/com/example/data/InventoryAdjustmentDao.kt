package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface InventoryAdjustmentDao {
  @Query("SELECT * FROM inventory_adjustments ORDER BY date DESC, id DESC")
  suspend fun getAllAdjustmentsSnapshot(): List<InventoryAdjustmentEntity>

  @Query("SELECT * FROM inventory_adjustments WHERE itemId = :itemId ORDER BY date DESC, id DESC")
  suspend fun getAdjustmentsForItem(itemId: Long): List<InventoryAdjustmentEntity>

  @Query("DELETE FROM inventory_adjustments")
  suspend fun clearAdjustments()

  @Query("DELETE FROM inventory_adjustments WHERE itemId = :itemId")
  suspend fun deleteAdjustmentsForItem(itemId: Long): Int

  @Query("SELECT * FROM inventory_adjustments WHERE id = :adjustmentId LIMIT 1")
  suspend fun getAdjustmentById(adjustmentId: Long): InventoryAdjustmentEntity?

  @Update
  suspend fun updateAdjustment(adjustment: InventoryAdjustmentEntity): Int

  @Query("DELETE FROM inventory_adjustments WHERE id = :adjustmentId")
  suspend fun deleteAdjustmentById(adjustmentId: Long): Int

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun restoreAdjustments(items: List<InventoryAdjustmentEntity>)

  @Insert
  suspend fun insertAdjustment(item: InventoryAdjustmentEntity): Long
}
