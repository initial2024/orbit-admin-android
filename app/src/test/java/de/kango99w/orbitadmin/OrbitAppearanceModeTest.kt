package de.kango99w.orbitadmin

import org.junit.Assert.*
import org.junit.Test

class OrbitAppearanceModeTest {
    @Test fun modeParsingIsPersistentAndCorruptionSafe() {
        for(mode in OrbitAppearanceMode.entries)
            assertEquals(mode,OrbitAppearanceMode.parse(mode.name))
        assertEquals(OrbitAppearanceMode.SYSTEM,OrbitAppearanceMode.parse(null))
        assertEquals(OrbitAppearanceMode.SYSTEM,OrbitAppearanceMode.parse("SYSTEM;ignore-config"))
        assertEquals(4,OrbitAppearanceMode.entries.size)
    }
}
