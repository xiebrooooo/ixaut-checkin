package edu.xaut.xilicheckin.data.repo

import edu.xaut.xilicheckin.data.crypto.PasswordCipher
import edu.xaut.xilicheckin.data.local.AccountDao
import edu.xaut.xilicheckin.data.local.AccountEntity
import edu.xaut.xilicheckin.domain.Account
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AccountRepository(
    private val dao: AccountDao,
    private val cipher: PasswordCipher,
) {

    fun observe(): Flow<List<Account>> = dao.observeAll().map { list ->
        list.mapNotNull { toDomain(it) }
    }

    suspend fun all(): List<Account> = dao.all().mapNotNull { toDomain(it) }

    suspend fun enabled(): List<Account> = all().filter { it.enabled }

    private fun toDomain(e: AccountEntity): Account? = runCatching {
        Account(
            account = e.account,
            password = cipher.decrypt(e.passwordCipher),
            name = e.name,
            enabled = e.enabled,
            castgc = if (e.castgcCipher.isBlank()) "" else cipher.decrypt(e.castgcCipher),
            castgcAt = e.castgcAt,
        )
    }.getOrNull()

    suspend fun upsert(account: String, password: String, name: String = "") {
        val acc = account.trim()
        if (acc.isEmpty()) return
        // 改密码/改备注时不要把已有的免登录票据冲掉：那是另一张独立的授权，
        // 只有服务端真的拒绝了才该清（见 clearCastgc）。
        val existing = dao.find(acc)
        dao.upsert(
            AccountEntity(
                account = acc,
                passwordCipher = cipher.encrypt(password),
                name = name.trim(),
                enabled = existing?.enabled ?: true,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                castgcCipher = existing?.castgcCipher ?: "",
                castgcAt = existing?.castgcAt ?: 0,
            )
        )
    }

    /**
     * 存下「7天免登录」票据。SignEngine 每次密码登录成功后回调这里。
     * 加密方式和密码一致（Keystore AES-256-GCM），数据库里看不到明文。
     */
    suspend fun saveCastgc(account: String, castgc: String) {
        if (account.isBlank() || castgc.isBlank()) return
        runCatching { dao.setCastgc(account, cipher.encrypt(castgc), System.currentTimeMillis()) }
    }

    /** 票据被服务端判过期时清掉，下次老老实实走密码 */
    suspend fun clearCastgc(account: String) = dao.clearCastgc(account)

    suspend fun delete(account: String) = dao.delete(account)

    suspend fun setEnabled(account: String, enabled: Boolean) = dao.setEnabled(account, enabled)

    /**
     * 批量导入：一行一个账号，支持「学号 密码 [备注]」，逗号/空格/制表符分隔都认。
     * @return 成功导入条数
     */
    suspend fun importLines(text: String): Int {
        var n = 0
        text.lineSequence().forEach { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) return@forEach
            val parts = t.split(Regex("[\\s,，\\t]+")).filter { it.isNotEmpty() }
            if (parts.size >= 2) {
                upsert(parts[0], parts[1], parts.getOrElse(2) { "" })
                n++
            }
        }
        return n
    }
}
