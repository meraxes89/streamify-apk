package java.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class UuidStubTest {

    @Test
    fun randomUuidGeneratesDistinctNonZeroRfc4122V4Uuids() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        assertNotEquals("00000000-0000-0000-0000-000000000000", a.toString())
        assertNotEquals(a, b)
        assertEquals(36, a.toString().length)
        assertEquals('4', a.toString()[14])
        assertEquals(a, UUID.fromString(a.toString()))
    }
}
