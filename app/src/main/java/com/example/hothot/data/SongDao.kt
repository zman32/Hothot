package com.example.hothot.data

import androidx.room.*

@Dao
interface SongDao {
    @Query("SELECT * FROM songs")
    suspend fun getAll(): List<SongEntity>

    @Query("SELECT * FROM songs ORDER BY title COLLATE NOCASE ASC")
    suspend fun getAllAlphabetical(): List<SongEntity>

    //might so alpha by artist later
    @Query("SELECT * FROM songs ORDER BY artist COLLATE NOCASE ASC")
    suspend fun getAllAlphabeticalArtist(): List<SongEntity>

  //  @Query("SELECT * FROM songs ORDER BY dateAdded ASC") // Add dateAdded field to your entity
   // fun getAllByDateAdded(): List<SongEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(songs: List<SongEntity>)

    @Query("DELETE FROM songs")
    suspend fun clearAll()
}
