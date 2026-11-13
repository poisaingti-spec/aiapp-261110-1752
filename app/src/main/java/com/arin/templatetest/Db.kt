package com.arin.templatetest

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "notes")
data class Note(@PrimaryKey(autoGenerate = true) val id: Long = 0, val title: String, val body: String)

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY id DESC") fun all(): Flow<List<Note>>
    @Insert suspend fun add(note: Note): Long
    @Query("DELETE FROM notes WHERE id = :id") suspend fun delete(id: Long)
}

@Database(entities = [Note::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun notes(): NoteDao
    companion object {
        fun open(context: Context): AppDb = Room.databaseBuilder(context, AppDb::class.java, "app.db").build()
    }
}
