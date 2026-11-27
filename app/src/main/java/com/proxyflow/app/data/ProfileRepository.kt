package com.proxyflow.app.data

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ProfileRepository(private val dao: ProfileDao) {

    val profiles: Flow<List<ProxyProfile>> = dao.observeAll().map { list -> list.map { it.toProfile() } }

    suspend fun all(): List<ProxyProfile> = dao.getAll().map { it.toProfile() }

    suspend fun get(id: Long): ProxyProfile? = dao.get(id)?.toProfile()

    suspend fun save(profile: ProxyProfile): Long {
        val entity = profile.toEntity()
        return if (profile.id == 0L) {
            dao.insert(entity)
        } else {
            val existing = dao.get(profile.id)
            dao.update(entity.copy(createdAt = existing?.createdAt ?: System.currentTimeMillis()))
            profile.id
        }
    }

    suspend fun duplicate(id: Long): Long? {
        val p = get(id) ?: return null
        return dao.insert(p.copy(id = 0, name = "${p.name} (copy)").toEntity())
    }

    suspend fun rename(id: Long, name: String) = dao.rename(id, name)
    suspend fun delete(id: Long) = dao.delete(id)
    suspend fun setTestResult(id: Long, latency: Int, error: String?) =
        dao.setTestResult(id, latency, error, System.currentTimeMillis())

    private fun ProxyProfile.toEntity() = ProfileEntity(
        id = id,
        name = name,
        protocol = protocol.name,
        host = host,
        port = port,
        publicConfig = publicJson(),
        secrets = Crypto.encrypt(secretJson()),
        lastLatencyMs = lastLatencyMs,
        lastTestError = lastTestError,
        lastTestAt = lastTestAt,
    )

    private fun ProfileEntity.toProfile(): ProxyProfile {
        val secretsJson = try {
            Crypto.decrypt(secrets)
        } catch (e: Exception) {
            // Never log the payload itself.
            Log.w("ProxyFlow", "Could not decrypt credentials for profile $id: ${e.javaClass.simpleName}")
            "{}"
        }
        return ProxyProfile(
            id = id, name = name, protocol = Protocol.from(protocol), host = host, port = port,
            lastLatencyMs = lastLatencyMs, lastTestError = lastTestError, lastTestAt = lastTestAt,
        ).withPublicJson(publicConfig).withSecretJson(secretsJson)
    }
}
