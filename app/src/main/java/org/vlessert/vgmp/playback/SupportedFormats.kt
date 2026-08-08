package org.vlessert.vgmp.playback

object SupportedFormats {
    val twoSfFamilyExtensions = setOf("2sf", "mini2sf")
    val usfFamilyExtensions = setOf("usf", "miniusf")
    val gsfFamilyExtensions = setOf("gsf", "minigsf")
    val kssFamilyExtensions = setOf("kss", "mgs", "bgm", "opx", "mpk", "mbm")
    val multiTrackExtensions = setOf("nsf", "nsfe", "gbs", "hes", "sap", "ay") +
        kssFamilyExtensions

    val extensions = setOf(
        "vgm", "vgz", "nsf", "nsfe", "gbs", "gym", "hes", "ay", "sap", "spc",
        "kss", "mgs", "bgm", "opx", "mpk", "mbm",
        "mod", "xm", "s3m", "it", "mptm", "stm", "far", "ult", "med", "mtm",
        "psm", "amf", "okt", "dsm", "dtm", "umx",
        "mid", "midi", "rmi", "smf", "mus", "lmp",
        "psf", "psf1", "psf2", "minipsf", "minipsf1", "minipsf2",
        "gsf", "minigsf", "2sf", "mini2sf", "usf", "miniusf"
    )

    fun supports(displayName: String): Boolean =
        extensionOf(displayName) in extensions

    fun isKssFamily(displayName: String): Boolean = extensionOf(displayName) in kssFamilyExtensions
    fun isGsfFamily(displayName: String): Boolean = extensionOf(displayName) in gsfFamilyExtensions

    fun companionLibraryExtension(displayName: String): String? = when (extensionOf(displayName)) {
        in gsfFamilyExtensions -> "gsflib"
        in twoSfFamilyExtensions -> "2sflib"
        in usfFamilyExtensions -> "usflib"
        else -> null
    }

    fun isMultiTrackContainer(displayName: String): Boolean =
        extensionOf(displayName) in multiTrackExtensions

    private fun extensionOf(displayName: String): String =
        displayName.substringAfterLast('.', "").trim().lowercase()
}
