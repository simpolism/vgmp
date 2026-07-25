package org.vlessert.vgmp.playback

/** Locale-neutral, case-insensitive natural ordering with stable leading-zero handling. */
object NaturalSort {
    val names: Comparator<String> = Comparator(::compare)

    fun compare(left: String, right: String): Int {
        var a = 0
        var b = 0
        while (a < left.length && b < right.length) {
            val ac = left[a]
            val bc = right[b]
            if (ac.isDigit() && bc.isDigit()) {
                // Game-music sets commonly number tracks in hexadecimal
                // (0000, 000a, 000f, 0010). Treat a digit-led run of hex
                // characters as one numeric token. Decimal-only names retain
                // the same ordering because magnitude ordering is identical.
                val aEnd = left.indexOfFirstFrom(a) { !it.isHexDigit() }
                val bEnd = right.indexOfFirstFrom(b) { !it.isHexDigit() }
                val aToken = left.substring(a, aEnd)
                val bToken = right.substring(b, bEnd)
                val aValue = aToken.trimStart('0').ifEmpty { "0" }
                val bValue = bToken.trimStart('0').ifEmpty { "0" }
                val numeric = aValue.length.compareTo(bValue.length)
                    .takeIf { it != 0 } ?: aValue.compareTo(bValue, ignoreCase = true)
                if (numeric != 0) return numeric
                if (aToken.length != bToken.length) return aToken.length.compareTo(bToken.length)
                a = aEnd
                b = bEnd
            } else {
                val compared = ac.lowercaseChar().compareTo(bc.lowercaseChar())
                if (compared != 0) return compared
                a++
                b++
            }
        }
        return (left.length - a).compareTo(right.length - b)
            .takeIf { it != 0 } ?: left.compareTo(right)
    }

    private fun Char.isHexDigit(): Boolean = isDigit() || lowercaseChar() in 'a'..'f'

    private inline fun String.indexOfFirstFrom(start: Int, predicate: (Char) -> Boolean): Int {
        for (index in start until length) if (predicate(this[index])) return index
        return length
    }
}
