package com.filewall.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The storage figures are the most visible numbers in the app, and the original's rendering
 * is specific: base 1024, one decimal above bytes, none at byte scale.
 */
class FormattersTest {

    @Test
    fun `bytes render without a decimal place`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("0 B", formatBytes(-1))
        assertEquals("512 B", formatBytes(512))
        assertEquals("1023 B", formatBytes(1023))
    }

    @Test
    fun `larger units keep one decimal place`() {
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("131.0 KB", formatBytes(134_144))
        assertEquals("97.4 KB", formatBytes(99_738))
        assertEquals("269.4 MB", formatBytes(282_477_363))
    }

    @Test
    fun `scale stops climbing at the largest known unit`() {
        assertEquals("1.0 TB", formatBytes(1024L * 1024 * 1024 * 1024))
        assertEquals("1024.0 TB", formatBytes(1024L * 1024 * 1024 * 1024 * 1024))
    }

    @Test
    fun `durations read as words for the auto-lock hint`() {
        assertEquals("15 seconds", formatDuration(15))
        assertEquals("30 seconds", formatDuration(30))
        assertEquals("1 minute", formatDuration(60))
        assertEquals("5 minutes", formatDuration(300))
    }
}
