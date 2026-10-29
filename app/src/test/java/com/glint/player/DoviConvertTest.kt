package com.glint.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Profile 7 → 8.1 RPU conversion. Fixtures are real RPUs from a UHD Blu-ray remux (Mars Attacks, MEL)
 * and the conversion a reference implementation (checked against all 99 Dolby Vision films on the NAS) made of them.
 */
class DoviConvertTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** Emulation prevention, as in a real stream: 00 00 followed by 00-03 gets a 03 in between. */
    private fun escape(b: ByteArray): ByteArray {
        val out = ArrayList<Byte>(); var zeros = 0
        for (v in b) {
            if (zeros >= 2 && (v.toInt() and 0xFF) <= 3) { out += 3; zeros = 0 }
            out += v; zeros = if (v.toInt() == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private val samples = listOf(
        "1908090840613650af003ff801ffc00ffc001fffa000001000000d0000008000006800000400000000400000200000200000000400000200000200000000400000200000200000344acc00006bd44acdf3f9d6384acc89940000020000001000000010000000386c4486030c14bc611c0a280000034c7cb5fffe0000000000000000c040fb9c02a28030080041099981010000000402800000011408a009060fa0003205dd00d61164f2b580" to
            "19080908406136506f003ff801ffc00fffd000000800000680000040000034000002000001a2566000035ea2566f9fceb1c256644ca00000100000008000000080000001c36224301860a5e308e0514000001a63e5affff000000000000000060207dce0151430080041099981010000000402800000011408a009060fa0003205dd00d612975e5a80",
        "1908090840613650af003ff801ffc00ffc001fffa000001000000d0000008000006800000400000000400000200000200000000400000200000200000000400000200000200000392b300001af512b37cfe758e12b3226500000080000004000000040000000e1b112180c3052f1847028a000000d31f2d7fff800000000000000030103ee700a8a30080041099981010000000402800000011408a009060fa0003205dd00d635bce8fb80" to
            "19080908406136506f003ff801ffc00fffd000000800000680000040000034000002000001c95980000d7a8959be7f3ac7095991328000004000000200000002000000070d8890c06182978c238145000000698f96bfffc00000000000000018081f7380545030080041099981010000000402800000011408a009060fa0003205dd00d6e398260280",
        "1908090840613650af003ff801ffc00ffc001fffa000001000000d0000008000006800000400000000400000200000200000000400000200000200000000400000200000200000392b300001af512b37cfe758e12b3226500000080000004000000040000000e1b112180c3052f1847028a000000d31f2d7fff800000000000000030103ee700a8a30080041099981010000000402800000011408a009060fa0003205dd00d635bce8fb80" to
            "19080908406136506f003ff801ffc00fffd000000800000680000040000034000002000001c95980000d7a8959be7f3ac7095991328000004000000200000002000000070d8890c06182978c238145000000698f96bfffc00000000000000018081f7380545030080041099981010000000402800000011408a009060fa0003205dd00d6e398260280",
    )

    @Test fun rpuMatchesReference() = samples.forEach { (src, want) ->
        assertArrayEquals(hex(want), DoviConvert.convertRpu(hex(src)))
    }

    @Test fun accessUnitDropsEnhancementLayerAndRewritesRpu() {
        val (src, want) = samples[0]
        val start = byteArrayOf(0, 0, 0, 1)
        val aud = byteArrayOf(0x46, 0x01, 0x50)
        val slice = byteArrayOf(0x02, 0x01, 0x10, 0x20, 0x30)
        val el = byteArrayOf(0x7E, 0x01, 0x11, 0x22)
        val rpuNal = byteArrayOf(0x7C, 0x01) + escape(hex(src))
        val unit = start + aud + start + slice + start + el + start + rpuNal
        val out = DoviConvert.accessUnit(unit, unit.size)
        assertNotNull(out)
        assertEquals((start + aud + start + slice + start + byteArrayOf(0x7C, 0x01) + escape(hex(want))).toList(), out!!.toList())
    }
}
