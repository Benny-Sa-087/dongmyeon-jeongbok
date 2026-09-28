package kr.dongmyeon.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** 달성한 읍면동 */
@Entity(tableName = "achieved")
data class AchievedEntity(
    @PrimaryKey val code: String,
    val name: String,
    val sido: String,
    val sgg: String,
    val firstVisitedAt: Long,
    /** "POINTS" | "CROSSING" */
    val method: String,
)

/** 미달성 영역에 찍힌 유효 점 수 */
@Entity(tableName = "hits")
data class HitEntity(
    @PrimaryKey val code: String,
    val count: Int,
    val firstTime: Long,
)

/** 직전 유효 점(한 행만 사용) */
@Entity(tableName = "last_point")
data class LastPointEntity(
    @PrimaryKey val id: Int = 0,
    val lat: Double,
    val lng: Double,
    val accuracyM: Float,
    val timeMillis: Long,
    val regionCode: String?,
    val enteredFromOutside: Boolean,
)

@Dao
abstract class VisitDao {
    @Query("SELECT * FROM achieved ORDER BY firstVisitedAt DESC")
    abstract fun observeAchieved(): Flow<List<AchievedEntity>>

    @Query("SELECT * FROM achieved")
    abstract suspend fun allAchieved(): List<AchievedEntity>

    @Query("SELECT * FROM hits")
    abstract suspend fun allHits(): List<HitEntity>

    @Query("SELECT * FROM last_point WHERE id = 0")
    abstract suspend fun lastPoint(): LastPointEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertAchieved(items: List<AchievedEntity>)

    @Upsert
    abstract suspend fun upsertHits(items: List<HitEntity>)

    @Query("DELETE FROM hits WHERE code IN (:codes)")
    abstract suspend fun deleteHits(codes: List<String>)

    @Upsert
    abstract suspend fun upsertLastPoint(p: LastPointEntity)

    @Query("DELETE FROM last_point")
    abstract suspend fun clearLastPoint()

    @Query("DELETE FROM achieved")
    abstract suspend fun clearAchieved()

    @Query("DELETE FROM hits")
    abstract suspend fun clearHits()

    @Transaction
    open suspend fun saveStep(
        newAchieved: List<AchievedEntity>,
        hits: List<HitEntity>,
        removedHits: List<String>,
        last: LastPointEntity?,
    ) {
        if (newAchieved.isNotEmpty()) insertAchieved(newAchieved)
        if (removedHits.isNotEmpty()) deleteHits(removedHits)
        if (hits.isNotEmpty()) upsertHits(hits)
        if (last != null) upsertLastPoint(last) else clearLastPoint()
    }

    @Transaction
    open suspend fun clearAll() {
        clearAchieved(); clearHits(); clearLastPoint()
    }
}

@Database(
    entities = [AchievedEntity::class, HitEntity::class, LastPointEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun visitDao(): VisitDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "dongmyeon.db").build()
    }
}
