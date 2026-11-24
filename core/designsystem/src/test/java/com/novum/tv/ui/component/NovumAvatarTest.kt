package com.novum.tv.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 头像占位：无图时首字必须稳定、可读，颜色必须是暗色（白字压得住）。
 */
class NovumAvatarTest {

    /** 中文姓名取前两个字：一排演员里两个字比一个字更好认。 */
    @Test
    fun cjkNameUsesFirstTwoCharacters() {
        assertEquals("张伟", avatarInitials("张伟"))
        assertEquals("小罗", avatarInitials("小罗伯特·唐尼"))
    }

    /** 拉丁姓名取两个词的首字母。 */
    @Test
    fun latinNameUsesTwoInitials() {
        assertEquals("TH", avatarInitials("Tom Hanks"))
        assertEquals("C", avatarInitials("Cher"))
    }

    /** 空白姓名给一个可显示的兜底字符，不能是空字符串（那又变成空白头像了）。 */
    @Test
    fun blankNameFallsBackToQuestionMark() {
        assertEquals("?", avatarInitials(""))
        assertEquals("?", avatarInitials("   "))
    }

    /** 同一姓名颜色稳定；底色压得够暗，白色首字对比度才够。 */
    @Test
    fun placeholderColorIsStableAndDark() {
        val first = avatarPlaceholderColor("Tom Hanks")
        assertEquals(first, avatarPlaceholderColor("Tom Hanks"))
        assertNotEquals(first, avatarPlaceholderColor("张伟"))
        // HSV(V=0.30) 转 sRGB 后各通道都不会亮过 0.35
        assertTrue(first.red <= 0.35f && first.green <= 0.35f && first.blue <= 0.35f)
    }
}
