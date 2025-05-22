package com.example.hothot.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey val uri: String,
    val fileName: String,
    val title: String,
    val artist: String
)
