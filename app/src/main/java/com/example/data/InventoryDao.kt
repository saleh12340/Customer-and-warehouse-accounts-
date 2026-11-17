package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface InventoryDao {
  @Query("SELECT * FROM inventory_items ORDER BY name ASC")
  fun getAllItems(): Flow<List<InventoryItemEntity>>

  @Query("SELECT * FROM inventory_items WHERE id = :id")
  suspend fun getItemById(id: Long): InventoryItemEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertItem(item: InventoryItemEntity): Long

  @Update
  suspend fun updateItem(item: InventoryItemEntity)

  @Delete
  suspend fun deleteItem(item: InventoryItemEntity)

  @Query("UPDATE inventory_items SET quantity = quantity + :delta WHERE id = :itemId")
  suspend fun adjustQuantity(itemId: Long, delta: Int)
}
