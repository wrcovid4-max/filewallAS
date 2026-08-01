package com.filewall.data.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * The seek arithmetic behind [VaultCrypto.cipherAt].
 *
 * Pure JVM — no Keystore, no Android. Worth pinning down precisely, because a carry bug
 * here would not crash: it would hand ExoPlayer plausible-looking garbage part-way into a
 * video, which is a miserable thing to debug from a bug report.
 */
class CtrCounterTest {

    private fun counter(vararg lastBytes: Int): ByteArray {
        val iv = ByteArray(16)
        lastBytes.forEachIndexed { offset, value ->
            iv[iv.size - lastBytes.size + offset] = value.toByte()
        }
        return iv
    }

    @Test
    fun `zero block index leaves the IV alone`() {
        val iv = counter(0x00, 0x00, 0x01, 0x02)
        assertArrayEquals(iv, VaultCrypto.counterFor(iv, 0))
    }

    @Test
    fun `small increments touch only the last byte`() {
        val iv = counter(0, 0, 0, 1)
        assertArrayEquals(counter(0, 0, 0, 6), VaultCrypto.counterFor(iv, 5))
    }

    @Test
    fun `carry ripples out of the last byte`() {
        val iv = counter(0, 0, 0, 0xFF)
        assertArrayEquals(counter(0, 0, 1, 0x00), VaultCrypto.counterFor(iv, 1))
    }

    @Test
    fun `carry ripples across several bytes`() {
        val iv = counter(0xFF, 0xFF, 0xFF, 0xFF)
        assertArrayEquals(counter(0, 0, 0, 0), VaultCrypto.counterFor(iv, 1).copyOfRange(12, 16))
        // The carry must have propagated further left, not been dropped.
        assertArrayEquals(byteArrayOf(1), VaultCrypto.counterFor(iv, 1).copyOfRange(11, 12))
    }

    @Test
    fun `a multi-byte block index is added across positions`() {
        val iv = counter(0, 0, 0, 0)
        // 0x01_02_03_04 blocks in — the value must land big-endian, not byte-reversed.
        assertArrayEquals(
            counter(0x01, 0x02, 0x03, 0x04),
            VaultCrypto.counterFor(iv, 0x01020304L),
        )
    }

    @Test
    fun `an offset a long way into a large file still lines up`() {
        // 8 GiB in: block index 0x2000_0000, which is more than a 32-bit lane can hold.
        val blocks = (8L * 1024 * 1024 * 1024) / 16
        val result = VaultCrypto.counterFor(counter(0, 0, 0, 0), blocks)
        assertArrayEquals(counter(0x20, 0x00, 0x00, 0x00), result)
    }

    @Test
    fun `the original IV is never mutated in place`() {
        val iv = counter(0, 0, 0, 7)
        val snapshot = iv.copyOf()
        VaultCrypto.counterFor(iv, 1234)
        assertArrayEquals(snapshot, iv)
    }
}
