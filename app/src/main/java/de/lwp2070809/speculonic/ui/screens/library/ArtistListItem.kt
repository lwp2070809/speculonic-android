package de.lwp2070809.speculonic.ui.screens.library

import de.lwp2070809.speculonic.network.model.Artist

sealed class ArtistListItem {
    abstract val groupChar: String

    data class Header(
        val char: String,
        val count: Int,
        val isCollapsed: Boolean
    ) : ArtistListItem() {
        override val groupChar: String get() = char
    }

    data class ArtistItem(
        val artist: Artist,
        val char: String
    ) : ArtistListItem() {
        override val groupChar: String get() = char
    }
}
