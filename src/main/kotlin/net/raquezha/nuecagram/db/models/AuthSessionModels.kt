package net.raquezha.nuecagram.db.models

import java.util.UUID

data class IssuedCredential(val id: UUID, val installationId: UUID, val raw: String)
data class VerifiedSecret(val secretId: UUID, val installationId: UUID)
data class ConsumedManagementLink(val linkId: UUID, val installationId: UUID)

data class IssuedManagementSession(
    val sessionId: UUID,
    val installationId: UUID,
    val raw: String,
    val csrf: String,
)

data class ManagementSessionContext(
    val sessionId: UUID,
    val installationId: UUID,
    val csrfDigest: ByteArray?,
    val csrfHash: String?,
)

data class IssuedPlatformAdminSession(val id: UUID, val raw: String, val csrf: String)
data class PlatformAdminSessionContext(val id: UUID, val csrfDigest: ByteArray, val csrfHash: String)

data class IssuedWebAppSession(
    val sessionId: UUID,
    val telegramUserId: Long,
    val telegramChatId: Long?,
    val telegramTopicId: Long?,
    val raw: String,
    val csrf: String,
)

data class WebAppSessionContext(
    val sessionId: UUID,
    val telegramUserId: Long,
    val telegramChatId: Long?,
    val telegramTopicId: Long?,
    val username: String?,
    val firstName: String?,
    val csrfDigest: ByteArray,
    val csrfHash: String,
)

data class LaunchNonceContext(
    val id: UUID,
    val telegramChatId: Long,
    val telegramTopicId: Long?,
    val telegramUserId: Long,
)
