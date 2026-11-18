package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomerDao {
  @Query("SELECT * FROM customers ORDER BY name ASC")
  fun getAllCustomers(): Flow<List<CustomerEntity>>

  @Query("SELECT * FROM customers ORDER BY id DESC")
  suspend fun getCustomersSnapshot(): List<CustomerEntity>

  @Query("SELECT * FROM customers WHERE LOWER(TRIM(name)) = LOWER(TRIM(:name)) LIMIT 1")
  suspend fun getCustomerByName(name: String): CustomerEntity?

  @Query("DELETE FROM customers")
  suspend fun clearCustomers()

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun restoreCustomers(items: List<CustomerEntity>)

  @Query("SELECT * FROM customers WHERE id = :id")
  suspend fun getCustomerById(id: Long): CustomerEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertCustomer(customer: CustomerEntity): Long

  @Update
  suspend fun updateCustomer(customer: CustomerEntity)

  @Delete
  suspend fun deleteCustomer(customer: CustomerEntity)

  @Query("UPDATE customers SET balance = balance + :amount WHERE id = :customerId")
  suspend fun updateBalance(customerId: Long, amount: Double): Int
}
