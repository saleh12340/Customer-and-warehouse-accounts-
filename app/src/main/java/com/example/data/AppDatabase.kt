package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
  entities = [
    CustomerEntity::class,
    SupplierEntity::class,
    InventoryItemEntity::class,
    InvoiceEntity::class,
    InvoiceItemEntity::class,
    TransactionEntity::class
  ],
  version = 1,
  exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
  abstract fun customerDao(): CustomerDao
  abstract fun supplierDao(): SupplierDao
  abstract fun inventoryDao(): InventoryDao
  abstract fun invoiceDao(): InvoiceDao
  abstract fun transactionDao(): TransactionDao

  companion object {
    @Volatile private var INSTANCE: AppDatabase? = null

    fun getDatabase(context: Context): AppDatabase {
      return INSTANCE ?: synchronized(this) {
        INSTANCE ?: Room.databaseBuilder(
          context.applicationContext,
          AppDatabase::class.java,
          "alaziy_grocery.db"
        )
          // Keep existing user databases intact. New installations start empty;
          // sample customers, suppliers and stock must never be booked as real data.
          .build()
          .also { INSTANCE = it }
      }
    }
  }
}
