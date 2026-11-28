package com.maxrave.simpmusic.utils

import com.maxrave.domain.data.model.home.HomeItem

fun HomeItem.isExactQuickPicksShelf(quickPicksTitle: String? = null): Boolean =
    (quickPicksTitle != null && title.equals(quickPicksTitle, ignoreCase = true)) ||
        title.equals("Quick picks", ignoreCase = true)

fun HomeItem.isQuickPicksShelf(quickPicksTitle: String? = null): Boolean =
    itemsPerColumn == 4 || isExactQuickPicksShelf(quickPicksTitle)

fun List<HomeItem>.pinQuickPicksToTop(quickPicksTitle: String? = null): List<HomeItem> {
    val qpIndex = indexOfFirst { it.isExactQuickPicksShelf(quickPicksTitle) }
    return if (qpIndex > 0) {
        val list = toMutableList()
        val qp = list.removeAt(qpIndex)
        list.add(0, qp)
        list
    } else {
        this
    }
}
