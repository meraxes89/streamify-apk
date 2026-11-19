package com.streamify.app.viewmodel

enum class SearchResultType { SONG, VIDEO, ARTIST, ALBUM, PLAYLIST }

data class OnlineSearchResult(
    val title: String,
    val uploader: String,
    val url: String,
    val duration: Int,
    val thumbnail: String,
    val type: SearchResultType = SearchResultType.SONG,
    val subtitle: String? = null,
    val year: String? = null,
    val browseId: String? = null,
    val isExplicit: Boolean = false,
    val isVerified: Boolean = false
)
