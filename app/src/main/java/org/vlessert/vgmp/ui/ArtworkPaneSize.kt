package org.vlessert.vgmp.ui

internal data class ArtworkPaneSize(val width: Int, val height: Int)

internal fun fitArtworkPane(
    artworkWidth: Int,
    artworkHeight: Int,
    maxWidth: Int,
    maxHeight: Int,
    padding: Int
): ArtworkPaneSize {
    val safeArtworkWidth = artworkWidth.coerceAtLeast(1)
    val safeArtworkHeight = artworkHeight.coerceAtLeast(1)
    val contentMaxWidth = (maxWidth - padding * 2).coerceAtLeast(1)
    val contentMaxHeight = (maxHeight - padding * 2).coerceAtLeast(1)
    val artworkAspect = safeArtworkWidth.toDouble() / safeArtworkHeight
    val boundsAspect = contentMaxWidth.toDouble() / contentMaxHeight

    val contentWidth: Int
    val contentHeight: Int
    if (artworkAspect >= boundsAspect) {
        contentWidth = contentMaxWidth
        contentHeight = (contentWidth / artworkAspect).toInt().coerceAtLeast(1)
    } else {
        contentHeight = contentMaxHeight
        contentWidth = (contentHeight * artworkAspect).toInt().coerceAtLeast(1)
    }
    return ArtworkPaneSize(
        width = contentWidth + padding * 2,
        height = contentHeight + padding * 2
    )
}
