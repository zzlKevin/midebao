package com.smilelight.midebao.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ActionCatalog] 的单元测试。
 *
 * 验证：
 * 1. 17 个动作全部注册（F3×7 + F4×1 + F5×7 + F6×1 + F7×1 = 17）。
 * 2. F3 和 F5 的 targetEnergy 随档位单调递增。
 * 3. speedableCodes 集合包含 F3 和 F5。
 * 4. 每个动作的 actionCode 和 speedLevel 正确对应。
 */
class ActionCatalogTest {

    @Test
    fun `catalog has exactly 17 actions`() {
        assertEquals(17, ActionCatalog.all.size)
    }

    @Test
    fun `F3 has 7 speed levels`() {
        val f3Actions = ActionCatalog.all.filter { it.actionCode == "F3" }
        assertEquals(7, f3Actions.size)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), f3Actions.map { it.speedLevel })
    }

    @Test
    fun `F5 has 7 speed levels`() {
        val f5Actions = ActionCatalog.all.filter { it.actionCode == "F5" }
        assertEquals(7, f5Actions.size)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), f5Actions.map { it.speedLevel })
    }

    @Test
    fun `F4 F6 F7 each have exactly one action`() {
        assertEquals(1, ActionCatalog.all.count { it.actionCode == "F4" })
        assertEquals(1, ActionCatalog.all.count { it.actionCode == "F6" })
        assertEquals(1, ActionCatalog.all.count { it.actionCode == "F7" })
    }

    @Test
    fun `F3 targetEnergy increases monotonically with speed level`() {
        val f3Actions = ActionCatalog.all
            .filter { it.actionCode == "F3" }
            .sortedBy { it.speedLevel }
        val energies = f3Actions.map { it.targetEnergy }
        for (i in 1 until energies.size) {
            assertTrue(
                "F3 level ${i + 1} energy (${energies[i]}) should be >= level $i energy (${energies[i - 1]})",
                energies[i] >= energies[i - 1]
            )
        }
    }

    @Test
    fun `F5 targetEnergy increases monotonically with speed level`() {
        val f5Actions = ActionCatalog.all
            .filter { it.actionCode == "F5" }
            .sortedBy { it.speedLevel }
        val energies = f5Actions.map { it.targetEnergy }
        for (i in 1 until energies.size) {
            assertTrue(
                "F5 level ${i + 1} energy (${energies[i]}) should be >= level $i energy (${energies[i - 1]})",
                energies[i] >= energies[i - 1]
            )
        }
    }

    @Test
    fun `speedableCodes contains F3 and F5`() {
        assertTrue("F3 should be speedable", "F3" in ActionCatalog.speedableCodes)
        assertTrue("F5 should be speedable", "F5" in ActionCatalog.speedableCodes)
    }

    @Test
    fun `all targetComplexity values are in range minus 1 to 1`() {
        ActionCatalog.all.forEach { action ->
            assertTrue(
                "${action.actionCode} L${action.speedLevel} targetComplexity ${action.targetComplexity} out of range",
                action.targetComplexity in -1.0..1.0
            )
        }
    }

    @Test
    fun `all periodSec values are positive`() {
        ActionCatalog.all.forEach { action ->
            assertTrue(
                "${action.actionCode} L${action.speedLevel} periodSec ${action.periodSec} must be positive",
                action.periodSec > 0.0
            )
        }
    }

    @Test
    fun `F3 period decreases with speed level`() {
        val f3Actions = ActionCatalog.all
            .filter { it.actionCode == "F3" }
            .sortedBy { it.speedLevel }
        val periods = f3Actions.map { it.periodSec }
        for (i in 1 until periods.size) {
            assertTrue(
                "F3 level ${i + 1} period (${periods[i]}) should be <= level $i period (${periods[i - 1]})",
                periods[i] <= periods[i - 1]
            )
        }
    }

    @Test
    fun `F5 period decreases with speed level`() {
        val f5Actions = ActionCatalog.all
            .filter { it.actionCode == "F5" }
            .sortedBy { it.speedLevel }
        val periods = f5Actions.map { it.periodSec }
        for (i in 1 until periods.size) {
            assertTrue(
                "F5 level ${i + 1} period (${periods[i]}) should be <= level $i period (${periods[i - 1]})",
                periods[i] <= periods[i - 1]
            )
        }
    }

    @Test
    fun `find returns correct action for valid code and speed`() {
        val action = ActionCatalog.find("F3", 3)
        assertTrue("find should return non-null for F3 L3", action != null)
        assertEquals("F3", action?.actionCode)
        assertEquals(3, action?.speedLevel)
    }

    @Test
    fun `find returns null for invalid code`() {
        val action = ActionCatalog.find("F99", 1)
        assertTrue("find should return null for invalid code", action == null)
    }
}
