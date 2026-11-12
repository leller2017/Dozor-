package ru.dozor.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class EmployeeTest {

    private fun emp(first: LocalDate, daysOff: Int) =
        Employee(1, "Иванов", "Иван", "Иванович", first.toEpochDay(), daysOff, 0)

    @Test
    fun oneThroughThreeRepeatsEveryFourDays() {
        val e = emp(LocalDate.of(2026, 11, 1), 3)
        assertTrue(e.worksOn(LocalDate.of(2026, 11, 1)))
        assertFalse(e.worksOn(LocalDate.of(2026, 11, 2)))
        assertTrue(e.worksOn(LocalDate.of(2026, 11, 5)))
        assertFalse(e.worksOn(LocalDate.of(2026, 12, 1))) // через 30 дней: 30 % 4 = 2
        assertTrue(e.worksOn(LocalDate.of(2026, 12, 3)))  // через 32 дня
    }

    @Test
    fun worksBeforeReferenceDateToo() {
        val e = emp(LocalDate.of(2026, 11, 13), 2)
        assertTrue(e.worksOn(LocalDate.of(2026, 11, 10)))
        assertFalse(e.worksOn(LocalDate.of(2026, 11, 11)))
        assertTrue(e.worksOn(LocalDate.of(2026, 11, 7)))
    }

    @Test
    fun shortName() {
        assertEquals("Иванов И.И.", emp(LocalDate.now(), 3).shortName)
        assertEquals("Иван", Employee(2, "", "Иван", "", 0, 3, 0).shortName)
    }
}
