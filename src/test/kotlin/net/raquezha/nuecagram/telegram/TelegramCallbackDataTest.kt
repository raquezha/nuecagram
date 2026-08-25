package net.raquezha.nuecagram.telegram

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TelegramCallbackDataTest {
    @Test
    fun parseReturnsNullForNullEmptyOrWhitespaceInputs() {
        assertThat(TelegramCallbackData.parse(null)).isNull()
        assertThat(TelegramCallbackData.parse("")).isNull()
        assertThat(TelegramCallbackData.parse("   ")).isNull()
        assertThat(TelegramCallbackData.parse("\t\n")).isNull()
    }

    @Test
    fun parseRejectsUnsupportedPrefixes() {
        assertThat(TelegramCallbackData.parse("invalid:mute:123")).isNull()
        assertThat(TelegramCallbackData.parse(":mute:123")).isNull()
        assertThat(TelegramCallbackData.parse("CB:mute:123")).isNull()
        assertThat(TelegramCallbackData.parse("INST:mute:123")).isNull()
        assertThat(TelegramCallbackData.parse("telegram:mute:123")).isNull()
    }

    @Test
    fun parseRejectsMalformedSegmentCounts() {
        assertThat(TelegramCallbackData.parse("cb")).isNull()
        assertThat(TelegramCallbackData.parse("cb:mute")).isNull()
        assertThat(TelegramCallbackData.parse("cb:mute:123:extra")).isNull()
        assertThat(TelegramCallbackData.parse("cb:mute:123:foo:bar")).isNull()
        assertThat(TelegramCallbackData.parse("cb::123")).isNull()
        assertThat(TelegramCallbackData.parse("cb:mute:")).isNull()
        assertThat(TelegramCallbackData.parse("cb::")).isNull()
    }

    @Test
    fun parseRejectsInvalidActionOrTargetIdCharacters() {
        assertThat(TelegramCallbackData.parse("cb:MUTE:123")).isNull()
        assertThat(TelegramCallbackData.parse("cb:mu te:123")).isNull()
        assertThat(TelegramCallbackData.parse("cb:mute:123@abc")).isNull()
        assertThat(TelegramCallbackData.parse("cb:mute:123!#$")).isNull()
        assertThat(TelegramCallbackData.parse("cb:mute:123;DROP TABLE")).isNull()
        assertThat(TelegramCallbackData.parse("cb:mute:123<script>")).isNull()
    }

    @Test
    fun parseParsesValidCbAndInstPrefixesWithLeadingOrTrailingWhitespace() {
        val cbPayload = TelegramCallbackData.parse("cb:mute:a1b2c3d4")
        assertThat(cbPayload).isNotNull()
        assertThat(cbPayload!!.action).isEqualTo("mute")
        assertThat(cbPayload.targetId).isEqualTo("a1b2c3d4")

        val instPayload = TelegramCallbackData.parse("  inst:unmute:550e8400-e29b-41d4-a716-446655440000  ")
        assertThat(instPayload).isNotNull()
        assertThat(instPayload!!.action).isEqualTo("unmute")
        assertThat(instPayload.targetId).isEqualTo("550e8400-e29b-41d4-a716-446655440000")

        val hyphenPayload = TelegramCallbackData.parse("cb:delivery-test:inst_123-abc")
        assertThat(hyphenPayload).isNotNull()
        assertThat(hyphenPayload!!.action).isEqualTo("delivery-test")
        assertThat(hyphenPayload.targetId).isEqualTo("inst_123-abc")
    }

    @Test
    fun formatAndParseRoundtripConsistency() {
        val formatted = TelegramCallbackData.format("mute", "inst-999")
        assertThat(formatted).isEqualTo("cb:mute:inst-999")

        val parsed = TelegramCallbackData.parse(formatted)
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.action).isEqualTo("mute")
        assertThat(parsed.targetId).isEqualTo("inst-999")
    }

    @Test
    fun parsesExtendedInstCallbackPatterns() {
        val listPage = TelegramCallbackData.parse("inst:list:page=1")
        assertThat(listPage).isNotNull()
        assertThat(listPage!!.action).isEqualTo("list")
        assertThat(listPage.targetId).isEqualTo("page=1")

        val menu = TelegramCallbackData.parse("inst:menu:a1b2c3d4")
        assertThat(menu).isNotNull()
        assertThat(menu!!.action).isEqualTo("menu")
        assertThat(menu.targetId).isEqualTo("a1b2c3d4")

        val rotateConfirm = TelegramCallbackData.parse("inst:rotate:confirm:a1b2c3d4")
        assertThat(rotateConfirm).isNotNull()
        assertThat(rotateConfirm!!.action).isEqualTo("rotate:confirm")
        assertThat(rotateConfirm.targetId).isEqualTo("a1b2c3d4")

        val rotateExecute = TelegramCallbackData.parse("inst:rotate:execute:a1b2c3d4")
        assertThat(rotateExecute).isNotNull()
        assertThat(rotateExecute!!.action).isEqualTo("rotate:execute")
        assertThat(rotateExecute.targetId).isEqualTo("a1b2c3d4")

        val back = TelegramCallbackData.parse("inst:back:page=0")
        assertThat(back).isNotNull()
        assertThat(back!!.action).isEqualTo("back")
        assertThat(back.targetId).isEqualTo("page=0")
    }
}
