package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
  @Query("SELECT * FROM transactions ORDER BY date DESC")
  fun getAllTransactions(): Flow<List<TransactionEntity>>

  @Query("SELECT * FROM transactions")
  suspend fun getTransactionsSnapshot(): List<TransactionEntity>

  @Query("DELETE FROM transactions")
  suspend fun clearTransactions()

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun restoreTransactions(items: List<TransactionEntity>)

  @Query("SELECT * FROM transactions WHERE partyType = :partyType AND partyId = :partyId ORDER BY date DESC, id DESC")
  fun getTransactionsForParty(partyType: String, partyId: Long): Flow<List<TransactionEntity>>

  @Query("SELECT * FROM transactions WHERE partyType = :partyType AND partyId = :partyId ORDER BY date DESC, id DESC")
  suspend fun getTransactionsForPartySnapshot(partyType: String, partyId: Long): List<TransactionEntity>

  @Query("SELECT * FROM transactions WHERE id = :id")
  suspend fun getTransactionById(id: Long): TransactionEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertTransaction(transaction: TransactionEntity): Long

  @Update
  suspend fun updateTransaction(transaction: TransactionEntity)

  @Delete
  suspend fun deleteTransaction(transaction: TransactionEntity)

  @Query("SELECT * FROM transactions WHERE invoiceId = :invoiceId ORDER BY id ASC")
  suspend fun getTransactionsForInvoice(invoiceId: Long): List<TransactionEntity>

  @Query("DELETE FROM transactions WHERE invoiceId = :invoiceId")
  suspend fun deleteByInvoiceId(invoiceId: Long): Int

  @Query("DELETE FROM transactions WHERE partyType = :partyType AND partyId = :partyId")
  suspend fun deleteByParty(partyType: String, partyId: Long): Int
}
