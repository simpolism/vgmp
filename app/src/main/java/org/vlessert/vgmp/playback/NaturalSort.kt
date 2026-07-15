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
                val aEnd = left.indexOfFirstFrom(a) { !it.isDigit() }
                val bEnd = right.indexOfFirstFrom(b) { !it.isDigit() }
                val aDigits = left.substring(a, aEnd)
                val bDigits = right.substring(b, bEnd)
                val aValue = aDigits.trimStart('0').ifEmpty { "0" }
                val bValue = bDigits.trimStart('0').ifEmpty { "0" }
                val numeric = aValue.length.compareTo(bValue.length)
                    .takeIf { it != 0 } ?: aValue.compareTo(bValue)
                if (numeric != 0) return numeric
                if (aDigits.length != bDigits.length) return aDigits.length.compareTo(bDigits.length)
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

    private inline fun String.indexOfFirstFrom(start: Int, predicate: (Char) -> Boolean): Int {
        for (index in start until length) if (predicate(this[index])) return index
        return length
    }
}
