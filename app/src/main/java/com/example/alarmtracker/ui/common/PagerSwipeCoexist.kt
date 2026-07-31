package com.example.alarmtracker.ui.common

import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/**
 * Lets swipe-to-delete coexist with a swipeable ViewPager2 host WITHOUT accidental deletes, by
 * splitting the two gestures by DIRECTION:
 *  - swipe LEFT / vertical scroll on a row → released to the pager (change tab) or the list scroll;
 *  - swipe RIGHT on a row → claimed for the RecyclerView's ItemTouchHelper (delete).
 *
 * The direction is decided only once the drag passes the device's own touch slop, so thresholds
 * scale with screen density/size across phones. Pair with an ItemTouchHelper limited to RIGHT.
 */
object PagerSwipeCoexist {

    fun attach(recyclerView: RecyclerView) {
        val slop = ViewConfiguration.get(recyclerView.context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var onRow = false
        var decided = false

        recyclerView.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.x
                        downY = e.y
                        onRow = rv.findChildViewUnder(e.x, e.y) != null
                        decided = false
                        // Claim the gesture up front so the pager can't steal it before we know
                        // which way the finger is going.
                        rv.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    MotionEvent.ACTION_MOVE -> if (!decided) {
                        val dx = e.x - downX
                        val dy = e.y - downY
                        if (abs(dx) > slop || abs(dy) > slop) {
                            decided = true
                            // Only a rightward drag that starts on a row is a delete; keep claiming
                            // it for ItemTouchHelper. Everything else (left = next tab, vertical =
                            // scroll) is released back to the pager / list.
                            val isDelete = onRow && abs(dx) > abs(dy) && dx > 0
                            rv.parent?.requestDisallowInterceptTouchEvent(isDelete)
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        rv.parent?.requestDisallowInterceptTouchEvent(false)
                }
                return false
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {}
            override fun onRequestDisallowInterceptTouchEvent(disallow: Boolean) {}
        })
    }
}
