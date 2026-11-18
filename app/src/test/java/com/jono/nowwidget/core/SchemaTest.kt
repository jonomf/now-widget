package com.jono.nowwidget.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The real proof is tools/test_migration.py (it runs this SQL in SQLite); these are shape checks. */
class SchemaTest {
    @Test
    fun oneStatementPerString() {
        (Schema.create() + Schema.upgradeFrom1() + Schema.upgradeFrom2()).forEach { s ->
            assertFalse(s, s.contains(";"))
            assertTrue(s, s.startsWith("CREATE") || s.startsWith("INSERT") || s.startsWith("ALTER"))
        }
    }

    @Test
    fun bothPathsMakeTheSameNewTables() {
        assertEquals(Schema.NEW_IN_V2, Schema.create().filter { it in Schema.NEW_IN_V2 })
        assertEquals(Schema.NEW_IN_V2, Schema.upgradeFrom1().take(Schema.NEW_IN_V2.size))
        assertEquals(Schema.NEW_IN_V3, Schema.create().filter { it in Schema.NEW_IN_V3 })
        assertEquals(Schema.NEW_IN_V3, Schema.upgradeFrom2())
        assertEquals(3, Schema.VERSION)
    }

    @Test
    fun upgradeNeverDropsOrRewritesOldData() {
        (Schema.upgradeFrom1() + Schema.upgradeFrom2()).forEach { s ->
            val u = s.uppercase()
            assertFalse(s, u.startsWith("DROP") || u.startsWith("DELETE") || u.startsWith("UPDATE"))
            assertFalse(s, u.startsWith("INSERT INTO PERSON") || u.startsWith("INSERT INTO ENTRY "))
        }
    }
}
