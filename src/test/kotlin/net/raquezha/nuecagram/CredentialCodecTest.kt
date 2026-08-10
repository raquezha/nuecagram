package net.raquezha.nuecagram

import com.google.common.truth.Truth.assertThat
import de.infix.testBalloon.framework.core.testSuite
import net.raquezha.nuecagram.db.CredentialCodec

val CredentialCodecTests by testSuite {
    test("issues credentials as hashes plus digests") {
        val (raw, stored) = CredentialCodec.issueCredential()

        assertThat(raw).isNotEmpty()
        assertThat(String(stored.digest)).isNotEqualTo(raw)
        assertThat(stored.hash).isNotEqualTo(raw)
        assertThat(CredentialCodec.matches(raw, stored.digest, stored.hash)).isTrue()
        assertThat(CredentialCodec.matches(raw + "-wrong", stored.digest, stored.hash)).isFalse()
    }
}
