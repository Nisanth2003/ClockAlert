package com.example.alarmtracker.ui.common

import androidx.annotation.MenuRes

/**
 * Implemented by a swipeable tab that wants action items in the app's single shared top bar.
 *
 * The toolbar lives in MainActivity rather than inside each tab, so it can stay put while pages
 * slide underneath it. That means a tab can no longer own its own menu — it declares one here and
 * MainActivity mounts it whenever that tab is the visible page.
 */
interface TabMenuHost {

    /** Menu to show while this tab is visible, or 0 for none. */
    @get:MenuRes
    val tabMenuRes: Int

    /** Handle a click on one of [tabMenuRes]'s items. Return false to let the activity try. */
    fun onTabMenuItemSelected(itemId: Int): Boolean
}
