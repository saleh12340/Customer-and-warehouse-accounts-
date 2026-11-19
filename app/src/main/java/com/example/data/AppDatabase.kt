package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
  entities = [
    CustomerEntity::class,
    SupplierEntity::class,
    InventoryItemEntity::class,
    InvoiceEntity::class,
    InvoiceItemEntity::class,
    TransactionEntity::class
  ],
  version = 3,
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

    val MIGRATION_1_2 = object : Migration(1, 2) {
      override fun migrate(db: SupportSQLiteDatabase) {
        // Preserve any legacy rows non-destructively before adding unique index on invoiceNumber.
        db.execSQL(
          """
          UPDATE invoices
          SET invoiceNumber = invoiceNumber || '-DUP-' || id
          WHERE id NOT IN (
            SELECT MIN(id) FROM invoices GROUP BY invoiceNumber
          )
          """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_invoices_invoiceNumber` ON `invoices` (`invoiceNumber`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_invoice_items_invoiceId` ON `invoice_items` (`invoiceId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_partyType_partyId_date` ON `transactions` (`partyType`, `partyId`, `date`)")
      }
    }

    fun getDatabase(context: Context): AppDatabase {
      return INSTANCE ?: synchronized(this) {
        INSTANCE ?: Room.databaseBuilder(
          context.applicationContext,
          AppDatabase::class.java,
          "alaziy_grocery.db"
        )
          .addMigrations(MIGRATION_1_2)
          .build()
          .also { INSTANCE = it }
      }
    }
  }
}
