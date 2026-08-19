package net.raquezha.nuecagram.telegram

import com.google.common.truth.Truth.assertThat
import net.raquezha.nuecagram.testing.TelegramWebAppTestUtils.buildTestInitData
import org.junit.Test

class TelegramWebAppAuthTest {
    @Test
    fun verifiesValidTelegramWebAppInitDataHmac() {
        val botToken = "123456789:ABCdefGHIjklMNOpqrsTUVwxyz"
        val initData = buildTestInitData(botToken)
        val verified = TelegramWebAppAuth.verifyInitData(initData, botToken)
        assertThat(verified).isNotNull()
        assertThat(verified?.user?.id).isEqualTo(12345L)
    }

    @Test
    fun rejectsTamperedTelegramWebAppInitDataHash() {
        val botToken = "123456789:ABCdefGHIjklMNOpqrsTUVwxyz"
        val initData = buildTestInitData(botToken) + "&tampered=true"
        val verified = TelegramWebAppAuth.verifyInitData(initData, botToken)
        assertThat(verified).isNull()
    }

    @Test
    fun rejectsExpiredAuthDate() {
        val botToken = "123456789:ABCdefGHIjklMNOpqrsTUVwxyz"
        val oldAuthDate = (System.currentTimeMillis() / 1000) - 90000 // > 24 hrs
        val initData = buildTestInitData(botToken, authDate = oldAuthDate)
        val verified = TelegramWebAppAuth.verifyInitData(initData, botToken)
        assertThat(verified).isNull()
    }
}
