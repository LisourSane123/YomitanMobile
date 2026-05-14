package com.yomitanmobile.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import com.yomitanmobile.data.local.entity.LookupCount

@Dao
interface LookupCountDao {

    /**
     * Atomic increment-or-insert. Implemented as an UPSERT-style
     * statement so two near-simultaneous detail opens (e.g. back/forward
     * navigation) collapse into a single increment per call without a
     * read-then-write race. SQLite's `INSERT ... ON CONFLICT ... DO
     * UPDATE` is supported on every Android version the app targets
     * (FTS4 is older than ON CONFLICT-DO-UPDATE was; we're safe).
     */
    @Query(
        """
        INSERT INTO lookup_counts (expression, reading, lookup_count, first_lookup, last_lookup)
        VALUES (:expression, :reading, 1, :now, :now)
        ON CONFLICT(expression, reading) DO UPDATE SET
            lookup_count = lookup_count + 1,
            last_lookup = :now
        """
    )
    suspend fun incrementOrInsert(expression: String, reading: String, now: Long)

    @Query(
        """
        SELECT lookup_count FROM lookup_counts
        WHERE expression = :expression AND reading = :reading
        """
    )
    suspend fun getCount(expression: String, reading: String): Int?

    /** Full row for any future stats UI that wants the timestamps. */
    @Query(
        """
        SELECT * FROM lookup_counts
        WHERE expression = :expression AND reading = :reading
        """
    )
    suspend fun get(expression: String, reading: String): LookupCount?

    /**
     * Top N most-looked-up words, useful for a future "frequently
     * looked up" surface. Defined now so the audit's "no Room
     * migration needed for read-only surface additions" promise holds.
     */
    @Query(
        """
        SELECT * FROM lookup_counts
        ORDER BY lookup_count DESC, last_lookup DESC
        LIMIT :limit
        """
    )
    suspend fun getTopLookedUp(limit: Int = 20): List<LookupCount>
}
