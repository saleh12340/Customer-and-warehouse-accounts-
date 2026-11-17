package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
        val instance = Room.databaseBuilder(
          context.applicationContext,
          AppDatabase::class.java,
          "alaziy_grocery.db"
        )
          .addCallback(DatabaseCallback())
          .build()
        INSTANCE = instance
        instance
      }
    }

    private class DatabaseCallback : RoomDatabase.Callback() {
      override fun onCreate(db: SupportSQLiteDatabase) {
        super.onCreate(db)
        INSTANCE?.let { database ->
          CoroutineScope(Dispatchers.IO).launch {
            populateInitialData(database)
          }
        }
      }

      suspend fun populateInitialData(db: AppDatabase) {
        val customerDao = db.customerDao()
        val supplierDao = db.supplierDao()
        val inventoryDao = db.inventoryDao()

        // Customers
        customerDao.insertCustomer(CustomerEntity(name = "محمد أحمد العزي", phone = "771234567", address = "صنعاء - السبعين", balance = 15500.0))
        customerDao.insertCustomer(CustomerEntity(name = "عبدالله صالح القاضي", phone = "733987654", address = "صنعاء - حدة", balance = 5200.0))
        customerDao.insertCustomer(CustomerEntity(name = "مطعم الشيباني", phone = "711223344", address = "صنعاء - الدائرة", balance = 0.0))

        // Suppliers
        supplierDao.insertSupplier(SupplierEntity(name = "شركة الأهدل للتجارة", phone = "700112233", company = "مواد غذائية جملة", balance = 45000.0))
        supplierDao.insertSupplier(SupplierEntity(name = "مؤسسة الضلعي للألبان", phone = "733445566", company = "ألبان ومشروبات", balance = 12000.0))

        // Inventory Items
        inventoryDao.insertItem(InventoryItemEntity(name = "أرز السعيد 10 كيلو", category = "حبوب", quantity = 45, purchasePrice = 14000.0, sellingPrice = 16000.0, unit = "كيس"))
        inventoryDao.insertItem(InventoryItemEntity(name = "سكر السعيد 50 كيلو", category = "سكريات", quantity = 15, purchasePrice = 38000.0, sellingPrice = 42000.0, unit = "كيس"))
        inventoryDao.insertItem(InventoryItemEntity(name = "زيت شربين 1.5 لتر", category = "زيوت", quantity = 60, purchasePrice = 2200.0, sellingPrice = 2500.0, unit = "حبة"))
        inventoryDao.insertItem(InventoryItemEntity(name = "شاي ربیع 200 جرام", category = "مشروبات", quantity = 120, purchasePrice = 1100.0, sellingPrice = 1300.0, unit = "حبة"))
        inventoryDao.insertItem(InventoryItemEntity(name = "حليب المراعي لتر", category = "ألبان", quantity = 8, purchasePrice = 750.0, sellingPrice = 900.0, unit = "حبة"))
      }
    }
  }
}
