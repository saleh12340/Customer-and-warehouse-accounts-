package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

data class InvoiceWithItems(
  @Embedded val invoice: InvoiceEntity,
  @Relation(parentColumn = "id", entityColumn = "invoiceId")
  val items: List<InvoiceItemEntity>
)

@Dao
interface InvoiceDao {
  @Query("SELECT * FROM invoices ORDER BY date DESC")
  fun getAllInvoices(): Flow<List<InvoiceEntity>>

  @Query("SELECT * FROM invoices")
  suspend fun getInvoicesSnapshot(): List<InvoiceEntity>

  @Query("SELECT * FROM invoice_items")
  suspend fun getInvoiceItemsSnapshot(): List<InvoiceItemEntity>

  @Query("DELETE FROM invoice_items")
  suspend fun clearInvoiceItems()

  @Query("DELETE FROM invoices")
  suspend fun clearInvoices()

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun restoreInvoices(items: List<InvoiceEntity>)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun restoreInvoiceItems(items: List<InvoiceItemEntity>)

  @Query("SELECT * FROM invoices WHERE invoiceNumber = :invoiceNumber LIMIT 1")
  suspend fun getInvoiceByNumber(invoiceNumber: String): InvoiceEntity?

  @Transaction
  @Query("SELECT * FROM invoices WHERE id = :invoiceId")
  suspend fun getInvoiceWithItems(invoiceId: Long): InvoiceWithItems?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertInvoice(invoice: InvoiceEntity): Long

  @Update
  suspend fun updateInvoice(invoice: InvoiceEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertItems(items: List<InvoiceItemEntity>)

  @Delete
  suspend fun deleteInvoice(invoice: InvoiceEntity)

  @Query("DELETE FROM invoice_items WHERE invoiceId = :invoiceId")
  suspend fun deleteItemsForInvoice(invoiceId: Long)
}
