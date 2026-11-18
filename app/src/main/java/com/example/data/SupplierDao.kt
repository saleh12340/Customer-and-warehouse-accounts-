package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SupplierDao {
  @Query("SELECT * FROM suppliers ORDER BY name ASC")
  fun getAllSuppliers(): Flow<List<SupplierEntity>>

  @Query("SELECT * FROM suppliers ORDER BY id DESC")
  suspend fun getSuppliersSnapshot(): List<SupplierEntity>

  @Query("SELECT * FROM suppliers WHERE LOWER(TRIM(name)) = LOWER(TRIM(:name)) LIMIT 1")
  suspend fun getSupplierByName(name: String): SupplierEntity?

  @Query("DELETE FROM suppliers")
  suspend fun clearSuppliers()

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun restoreSuppliers(items: List<SupplierEntity>)

  @Query("SELECT * FROM suppliers WHERE id = :id")
  suspend fun getSupplierById(id: Long): SupplierEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertSupplier(supplier: SupplierEntity): Long

  @Update
  suspend fun updateSupplier(supplier: SupplierEntity)

  @Delete
  suspend fun deleteSupplier(supplier: SupplierEntity)

  @Query("UPDATE suppliers SET balance = balance + :amount WHERE id = :supplierId")
  suspend fun updateBalance(supplierId: Long, amount: Double): Int
}
