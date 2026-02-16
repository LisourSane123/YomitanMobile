package com.yomitanmobile.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "dictionaries")
data class DictionaryInfo(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "version")
    val version: String = "",

    @ColumnInfo(name = "revision")
    val revision: String = "",

    @ColumnInfo(name = "entry_count")
    val entryCount: Int = 0,

    @ColumnInfo(name = "enabled")
    val enabled: Boolean = true,

    @ColumnInfo(name = "priority")
    val priority: Int = 0,

    @ColumnInfo(name = "import_date")
    val importDate: Long = System.currentTimeMillis()
)
