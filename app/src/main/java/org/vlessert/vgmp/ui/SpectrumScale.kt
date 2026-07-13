package org.vlessert.vgmp.ui

import kotlin.math.exp
import kotlin.math.sqrt

internal object SpectrumScale {
    fun level(magnitude: Float, bandFraction: Float): Float {
        val position = bandFraction.coerceIn(0f, 1f)
        val frequencyWeight = 0.32f + 0.98f * sqrt(position)
        val weighted = magnitude.coerceAtLeast(0f) * frequencyWeight
        return ((1f - exp(-weighted / 78f)) * 0.9f).coerceIn(0f, 0.9f)
    }
}
