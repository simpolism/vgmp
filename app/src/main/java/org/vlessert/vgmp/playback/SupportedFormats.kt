package org.vlessert.vgmp.playback

object SupportedFormats {
    val extensions = setOf(
        "vgm", "vgz", "nsf", "nsfe", "gbs", "gym", "hes", "ay", "sap", "spc",
        "kss", "mgs", "bgm", "opx", "mpk", "mbm",
        "mod", "xm", "s3m", "it", "mptm", "stm", "far", "ult", "med", "mtm",
        "psm", "amf", "okt", "dsm", "dtm", "umx",
        "mid", "midi", "rmi", "smf", "mus", "lmp",
        "psf", "psf1", "psf2", "minipsf", "minipsf1", "minipsf2"
    )

    fun supports(displayName: String): Boolean =
        displayName.substringAfterLast('.', "").trim().lowercase() in extensions
}
