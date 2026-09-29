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
    /** "POINTS" | "CROSSING" | "ON_SITE" */
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

/** 터널 등 공백 구간 때문에 자동 확정하지 않고 사용자 확인을 기다리는 지역 */
@Entity(tableName = "needs_review")
data class NeedsReviewEntity(
    @PrimaryKey val code: String,
    val name: String,
    val sido: String,
    val sgg: String,
    val flaggedAt: Long,
)

/**
 * 기록 시작~정지 한 구간(여행/출퇴근 등). "차량 가동률"이 아니라
 * "평소 출퇴근 패턴에서 벗어난 주행(=진짜 여행)"을 가리는 데 쓴다.
 */
@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTime: Long,
    val endTime: Long,
    val distanceMeters: Double,
    /** 이 구간에서 새로 달성한 읍면동 수(0이면 늘 다니던 길일 가능성이 높음) */
    val newRegionCount: Int,
    /** 요일(Calendar.SUNDAY=1 ~ SATURDAY=7) */
    val dayOfWeek: Int,
    /** 출퇴근성 판정 결과. null=판정 보류(기준 데이터 부족) */
    val isRoutine: Boolean?,
)

/** "직접 방문" 지역에 남긴 추억 사진. 파일은 앱 내부 저장소에 보관한다. */
@Entity(tableName = "photos")
data class PhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val code: String,
    /** 앱 내부 저장소(filesDir) 기준 절대 경로 */
    val filePath: String,
    val takenAt: Long,
)

@Dao
abstract class VisitDao {
    @Query("SELECT * FROM achieved ORDER BY firstVisitedAt DESC")
    abstract fun observeAchieved(): Flow<List<AchievedEntity>>

    @Query("SELECT * FROM achieved")
    abstract suspend fun allAchieved(): List<AchievedEntity>

    @Query("SELECT * FROM achieved WHERE code = :code")
    abstract suspend fun getAchieved(code: String): AchievedEntity?

    @Query("UPDATE achieved SET method = :method WHERE code = :code")
    abstract suspend fun updateMethod(code: String, method: String)

    @Query("SELECT * FROM hits")
    abstract suspend fun allHits(): List<HitEntity>

    @Query("SELECT * FROM last_point WHERE id = 0")
    abstract suspend fun lastPoint(): LastPointEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertAchieved(items: List<AchievedEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAchievedOne(item: AchievedEntity)

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

    @Query("SELECT * FROM needs_review ORDER BY flaggedAt DESC")
    abstract fun observeNeedsReview(): Flow<List<NeedsReviewEntity>>

    @Query("SELECT * FROM needs_review")
    abstract suspend fun allNeedsReview(): List<NeedsReviewEntity>

    @Upsert
    abstract suspend fun upsertNeedsReview(items: List<NeedsReviewEntity>)

    @Query("DELETE FROM needs_review WHERE code IN (:codes)")
    abstract suspend fun deleteNeedsReview(codes: List<String>)

    @Query("DELETE FROM needs_review")
    abstract suspend fun clearNeedsReview()

    @Insert
    abstract suspend fun insertTrip(trip: TripEntity): Long

    @Query("SELECT * FROM trips ORDER BY startTime DESC LIMIT :limit")
    abstract suspend fun recentTrips(limit: Int): List<TripEntity>

    @Query("SELECT * FROM trips ORDER BY startTime DESC")
    abstract fun observeTrips(): Flow<List<TripEntity>>

    @Query("DELETE FROM trips")
    abstract suspend fun clearTrips()

    @Insert
    abstract suspend fun insertPhoto(photo: PhotoEntity): Long

    @Query("SELECT * FROM photos WHERE code = :code ORDER BY takenAt DESC")
    abstract fun observePhotos(code: String): Flow<List<PhotoEntity>>

    /** 사진이 하나 이상 있는 지역 코드 목록(지도 깃발 표시용) */
    @Query("SELECT DISTINCT code FROM photos")
    abstract fun observePhotoCodes(): Flow<List<String>>

    @Query("SELECT * FROM photos WHERE id = :id")
    abstract suspend fun getPhoto(id: Long): PhotoEntity?

    @Query("DELETE FROM photos WHERE id = :id")
    abstract suspend fun deletePhoto(id: Long)

    @Query("DELETE FROM photos")
    abstract suspend fun clearPhotos()

    @Transaction
    open suspend fun saveStep(
        newAchieved: List<AchievedEntity>,
        hits: List<HitEntity>,
        removedHits: List<String>,
        needsReview: List<NeedsReviewEntity>,
        resolvedReview: List<String>,
        last: LastPointEntity?,
    ) {
        if (newAchieved.isNotEmpty()) insertAchieved(newAchieved)
        if (removedHits.isNotEmpty()) deleteHits(removedHits)
        if (hits.isNotEmpty()) upsertHits(hits)
        if (needsReview.isNotEmpty()) upsertNeedsReview(needsReview)
        if (resolvedReview.isNotEmpty()) deleteNeedsReview(resolvedReview)
        if (last != null) upsertLastPoint(last) else clearLastPoint()
    }

    @Transaction
    open suspend fun confirmReview(achieved: AchievedEntity) {
        insertAchievedOne(achieved)
        deleteNeedsReview(listOf(achieved.code))
    }

    @Transaction
    open suspend fun clearAll() {
        clearAchieved(); clearHits(); clearLastPoint(); clearNeedsReview(); clearTrips(); clearPhotos()
    }
}

@Database(
    entities = [
        AchievedEntity::class, HitEntity::class, LastPointEntity::class,
        NeedsReviewEntity::class, TripEntity::class, PhotoEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun visitDao(): VisitDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "dongmyeon.db")
                // 아직 초기 개발 단계라 스키마가 바뀌면 로컬 DB를 새로 만든다.
                // (달성 기록은 설정 탭의 JSON 백업/복원으로 보존 예정 — 3단계)
                .fallbackToDestructiveMigration()
                .build()
    }
}
