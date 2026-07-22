package net.raquezha.nuecagram.db

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import org.mindrot.jbcrypt.BCrypt

data class StoredCredential(
    val digest: ByteArray,
    val hash: String,
)

object CredentialCodec {
    private const val TOKEN_BYTES = 32
    private const val BCRYPT_ROUNDS = 12
    private val secureRandom = SecureRandom()

    fun issueCredential(): Pair<String, StoredCredential> {
        val raw = ByteArray(TOKEN_BYTES).also(secureRandom::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
        return token to StoredCredential(
            digest = digest(token),
            hash = BCrypt.hashpw(token, BCrypt.gensalt(BCRYPT_ROUNDS)),
        )
    }

    fun matches(
        raw: String,
        digest: ByteArray,
        hash: String,
    ): Boolean {
        val candidateDigest = digest(raw)
        return MessageDigest.isEqual(candidateDigest, digest) && BCrypt.checkpw(raw, hash)
    }

    fun digest(value: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
}
