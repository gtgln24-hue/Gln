package com.sshvpn.repository

import com.sshvpn.db.ProfileDao
import com.sshvpn.model.ConnectionProfile
import com.sshvpn.utils.CryptoUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CRUD operations for [ConnectionProfile] with transparent encrypt/decrypt
 * of sensitive fields (password, private key) via [CryptoUtils].
 */
@Singleton
class ProfileRepository @Inject constructor(
    private val dao: ProfileDao,
    private val crypto: CryptoUtils
) {
    /** Observe all profiles, decrypting sensitive fields on the fly. */
    fun observeAll(): Flow<List<ConnectionProfile>> =
        dao.observeAll().map { list -> list.map { decrypt(it) } }

    suspend fun getProfileById(id: Long): ConnectionProfile? =
        dao.getById(id)?.let { decrypt(it) }

    suspend fun getDefaultProfile(): ConnectionProfile? =
        dao.getDefault()?.let { decrypt(it) }

    suspend fun insert(profile: ConnectionProfile): Long =
        dao.insert(encrypt(profile))

    suspend fun update(profile: ConnectionProfile) =
        dao.update(encrypt(profile))

    suspend fun delete(profile: ConnectionProfile) =
        dao.delete(profile)

    suspend fun setDefault(profileId: Long) {
        dao.clearDefault()
        dao.setDefault(profileId)
    }

    suspend fun updateLastUsed(profileId: Long) =
        dao.updateLastUsed(profileId, System.currentTimeMillis())

    // ── Crypto helpers ────────────────────────────────────────────

    private fun encrypt(p: ConnectionProfile) = p.copy(
        password = if (p.password.isNotBlank()) crypto.encrypt(p.password) else "",
        privateKey = if (p.privateKey.isNotBlank()) crypto.encrypt(p.privateKey) else "",
        privateKeyPassphrase = if (p.privateKeyPassphrase.isNotBlank())
            crypto.encrypt(p.privateKeyPassphrase) else ""
    )

    private fun decrypt(p: ConnectionProfile) = p.copy(
        password = if (p.password.isNotBlank()) crypto.decrypt(p.password) else "",
        privateKey = if (p.privateKey.isNotBlank()) crypto.decrypt(p.privateKey) else "",
        privateKeyPassphrase = if (p.privateKeyPassphrase.isNotBlank())
            crypto.decrypt(p.privateKeyPassphrase) else ""
    )
}
