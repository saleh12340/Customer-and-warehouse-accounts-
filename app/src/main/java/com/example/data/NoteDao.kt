package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface NoteDao {
  @Query("SELECT * FROM notes ORDER BY updatedAt DESC, id DESC")
  suspend fun getNotesSnapshot(): List<NoteEntity>

  @Query("SELECT * FROM notes WHERE id = :id LIMIT 1")
  suspend fun getNoteById(id: Long): NoteEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertNote(note: NoteEntity): Long

  @Update
  suspend fun updateNote(note: NoteEntity)

  @Query("DELETE FROM notes WHERE id = :id")
  suspend fun deleteNoteById(id: Long)

  @Query("DELETE FROM notes")
  suspend fun clearNotes()

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun restoreNotes(items: List<NoteEntity>)
}
