package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "notes")
data class NoteEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val createdAt: Long,
  val updatedAt: Long,
  val rightRowsJson: String,
  val leftRowsJson: String
)
