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
    TransactionEntity::class,
    InventoryAdjustmentEntity::class,
    NoteEntity::class
  ],
  version = 5,
  exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
  abstract fun customerDao(): CustomerDao
  abstract fun supplierDao(): SupplierDao
  abstract fun inventoryDao(): InventoryDao
  abstract fun invoiceDao(): InvoiceDao
  abstract fun transactionDao(): TransactionDao
  abstract fun inventoryAdjustmentDao(): InventoryAdjustmentDao
  abstract fun noteDao(): NoteDao

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

    val MIGRATION_2_3 = object : Migration(2, 3) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE invoice_items ADD COLUMN stockQuantity INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE invoice_items ADD COLUMN unitLabel TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE invoice_items ADD COLUMN unitFactor INTEGER NOT NULL DEFAULT 1")
      }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
          CREATE TABLE IF NOT EXISTS notes (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            createdAt INTEGER NOT NULL,
            updatedAt INTEGER NOT NULL,
            rightRowsJson TEXT NOT NULL,
            leftRowsJson TEXT NOT NULL
          )
        """.trimIndent())
      }
    }

    val MIGRATION_4_5 = object : Migration(4, 5) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE invoice_items ADD COLUMN costPriceAtSale REAL")
        db.execSQL("ALTER TABLE transactions ADD COLUMN invoiceId INTEGER")
        db.execSQL("""
          CREATE TABLE IF NOT EXISTS inventory_adjustments (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            itemId INTEGER NOT NULL,
            itemName TEXT NOT NULL,
            previousQuantity INTEGER NOT NULL,
            newQuantity INTEGER NOT NULL,
            deltaQuantity INTEGER NOT NULL,
            reason TEXT NOT NULL,
            date INTEGER NOT NULL
          )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_inventory_adjustments_itemId_date ON inventory_adjustments(itemId, date)")
        db.execSQL("""
          UPDATE transactions
          SET invoiceId = (
            SELECT invoices.id FROM invoices
            WHERE invoices.partyId = transactions.partyId
              AND (
                (transactions.partyType = 'CUSTOMER' AND invoices.type IN ('SALE', 'CASH', 'CREDIT')
                  AND transactions.description = 'فاتورة مبيعات رقم ' || invoices.invoiceNumber)
                OR
                (transactions.partyType = 'SUPPLIER' AND invoices.type = 'PURCHASE'
                  AND transactions.description = 'فاتورة مشتريات رقم ' || invoices.invoiceNumber)
              )
            ORDER BY invoices.id DESC LIMIT 1
          )
          WHERE invoiceId IS NULL
        """.trimIndent())
      }
    }

    fun getDatabase(context: Context): AppDatabase {
      return INSTANCE ?: synchronized(this) {
        INSTANCE ?: Room.databaseBuilder(
          context.applicationContext,
          AppDatabase::class.java,
          "alaziy_grocery.db"
        )
          .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
          .build()
          .also { INSTANCE = it }
      }
    }
  }
}
