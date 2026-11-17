package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
  @Query("SELECT * FROM transactions ORDER BY date DESC")
  fun getAllTransactions(): Flow<List<TransactionEntity>>

  @Query("SELECT * FROM transactions WHERE partyType = :partyType AND partyId = :partyId ORDER BY date DESC")
  fun getTransactionsForParty(partyType: String, partyId: Long): Flow<List<TransactionEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertTransaction(transaction: TransactionEntity): Long

  @Delete
  suspend fun deleteTransaction(transaction: TransactionEntity)
}
