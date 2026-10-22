package edu.xaut.xilicheckin.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import edu.xaut.xilicheckin.data.crypto.PasswordCipher
import edu.xaut.xilicheckin.data.local.AccountDao
import edu.xaut.xilicheckin.data.local.AppDatabase
import edu.xaut.xilicheckin.data.local.RecordDao
import edu.xaut.xilicheckin.data.repo.AccountRepository
import edu.xaut.xilicheckin.data.repo.ConfigRepository
import edu.xaut.xilicheckin.data.repo.RecordRepository
import edu.xaut.xilicheckin.domain.SignEngine
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext ctx: Context): AppDatabase = AppDatabase.build(ctx)

    @Provides
    fun accountDao(db: AppDatabase): AccountDao = db.accountDao()

    @Provides
    fun recordDao(db: AppDatabase): RecordDao = db.recordDao()

    @Provides
    @Singleton
    fun passwordCipher(): PasswordCipher = PasswordCipher()

    @Provides
    @Singleton
    fun accountRepository(dao: AccountDao, cipher: PasswordCipher): AccountRepository {
        val repo = AccountRepository(dao, cipher)
        // 把「登录时顺手拿到的 7 天免登录票据」接到落库：SignEngine 是 object，
        // 没有构造注入，用这个钩子把 repository 传进去，避免给每个调用点加参数。
        SignEngine.onCastgc = { account, castgc -> repo.saveCastgc(account, castgc) }
        return repo
    }

    @Provides
    @Singleton
    fun configRepository(@ApplicationContext ctx: Context): ConfigRepository = ConfigRepository(ctx)

    @Provides
    @Singleton
    fun recordRepository(dao: RecordDao): RecordRepository = RecordRepository(dao)
}
