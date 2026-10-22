package edu.xaut.xilicheckin.data.local

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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val account: String,
    val passwordCipher: String,
    val name: String = "",
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * 「7天免登录」拿到的 CAS 票据（CASTGC），和密码一样用 Keystore AES-GCM 加密。
     * 服务端给 14 天，过期后自动回落密码登录。
     */
    val castgcCipher: String = "",
    /** 拿到这张票的时间戳（毫秒），用于判断快过期了没有 */
    val castgcAt: Long = 0,
)

@Entity(tableName = "records")
data class RecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val account: String,
    val status: String,
    val detail: String,
    val appId: String,
    val pageId: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts ORDER BY createdAt ASC")
    suspend fun all(): List<AccountEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(a: AccountEntity)

    @Query("DELETE FROM accounts WHERE account = :account")
    suspend fun delete(account: String)

    @Query("UPDATE accounts SET enabled = :enabled WHERE account = :account")
    suspend fun setEnabled(account: String, enabled: Boolean)

    @Query("UPDATE accounts SET castgcCipher = :cipher, castgcAt = :at WHERE account = :account")
    suspend fun setCastgc(account: String, cipher: String, at: Long)

    @Query("UPDATE accounts SET castgcCipher = '', castgcAt = 0 WHERE account = :account")
    suspend fun clearCastgc(account: String)

    @Query("SELECT * FROM accounts WHERE account = :account")
    suspend fun find(account: String): AccountEntity?
}

@Dao
interface RecordDao {
    @Query("SELECT * FROM records ORDER BY createdAt DESC LIMIT 300")
    fun observeAll(): Flow<List<RecordEntity>>

    @Insert
    suspend fun insert(r: RecordEntity)

    @Query("DELETE FROM records")
    suspend fun clear()
}

@Database(entities = [AccountEntity::class, RecordEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun recordDao(): RecordDao

    companion object {
        /**
         * v1 → v2：加「7天免登录」两列。
         * 用 ALTER TABLE 而不是删库重建 —— 账号密码是用户一条条录进来的，
         * 为了加两列把人家账号清空太不礼貌。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE accounts ADD COLUMN castgcCipher TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE accounts ADD COLUMN castgcAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "xili.db")
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration()
                .build()
    }
}
