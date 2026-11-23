package com.papercontrol.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class QuadDetectorTest {
    @Test
    fun findsABrightSheetOnADarkTable() {
        val w = 120
        val h = 160
        val lum = IntArray(w * h) { 30 }
        for (y in 30 until 130) for (x in 40 until 90) lum[y * w + x] = 230
        // Some printed text inside the sheet.
        for (y in 50 until 54) for (x in 45 until 80) lum[y * w + x] = 40
        val r = QuadDetector.detect(lum, w, h)
        assertNotNull(r)
        val q = r!!.quad
        assertEquals(40f / w, q.tl.x, 0.02f)
        assertEquals(30f / h, q.tl.y, 0.02f)
        assertEquals(90f / w, q.br.x, 0.02f)
        assertEquals(130f / h, q.br.y, 0.02f)
    }

    @Test
    fun givesUpOnAnEmptyFrame() {
        assertNull(QuadDetector.detect(IntArray(100 * 100) { 128 }, 100, 100))
    }
}
