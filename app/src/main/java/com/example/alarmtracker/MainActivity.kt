package com.example.alarmtracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import com.example.alarmtracker.databinding.ActivityMainBinding
import com.example.alarmtracker.ui.alarms.AlarmsFragment
import com.example.alarmtracker.ui.onboarding.OnboardingActivity
import com.example.alarmtracker.ui.common.TabMenuHost
import com.example.alarmtracker.ui.stats.StatsFragment
import com.example.alarmtracker.util.Prefs
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* banner reflects state */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Left/right insets (landscape 3-button nav, display cutouts) are applied here;
        // top/bottom are left untouched so the app bars (fitsSystemWindows) and the
        // BottomNavigationView keep handling them themselves.
        // Applied to the content column, not the DrawerLayout root — padding the root would inset
        // the sidebar itself and leave a gap down the edge of the screen.
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, v.paddingTop, bars.right, v.paddingBottom)
            insets
        }

        // Swipeable tabs: a ViewPager2 hosts the 5 destinations; the bottom nav and swipe stay in
        // sync. All pages are kept alive (offscreenPageLimit) so state survives like the old
        // add/show/hide did. Swipe-to-delete on the list pages is preserved because those
        // RecyclerViews claim horizontal drags that start on a row (see AlarmsFragment/TimerFragment).
        binding.pager.adapter = TabAdapter(this)
        binding.pager.offscreenPageLimit = TABS.size - 1
        // No page transformer. The old cross-fade left every off-screen page at 0.35 alpha, and
        // tapping a tab jumps without a scroll — so nothing ever ran to restore it and the page
        // you landed on stayed visibly greyed out. Swiping looked fine only because the drag
        // animates the alpha back to 1 on the way.

        binding.bottomNav.setOnItemSelectedListener { item ->
            val index = TABS.indexOfFirst { it == item.itemId }
            if (index >= 0 && binding.pager.currentItem != index) {
                binding.pager.setCurrentItem(index, false)
            }
            true
        }
        binding.pager.registerOnPageChangeCallback(object :
            androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                binding.bottomNav.menu.getItem(position).isChecked = true
                binding.navDrawer.setCheckedItem(TABS[position])
                updateTopBar(position)
            }
        })

        setupDrawer()
        setupTopBar()

        // First launch: run the guided onboarding (which handles reliability permissions itself);
        // otherwise ask for the notification permission the usual way.
        if (!Prefs.onboardingDone(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        } else {
            requestNotificationPermissionIfNeeded()
        }
    }

    /**
     * The one app bar shared by every tab. It sits in the activity, above the pager, so swiping
     * moves only the content underneath it — previously each fragment carried its own toolbar and
     * the whole bar slid off with the page.
     */
    private fun setupTopBar() {
        binding.topBar.setNavigationOnClickListener { binding.drawerLayout.open() }
        binding.topBar.setOnMenuItemClickListener { item ->
            (currentTabFragment() as? TabMenuHost)?.onTabMenuItemSelected(item.itemId) == true
        }
        updateTopBar(binding.pager.currentItem)
    }

    /** Retitles the bar and mounts whichever menu the newly-visible tab declares. */
    private fun updateTopBar(position: Int) {
        binding.topBar.setTitle(TAB_TITLES.getOrElse(position) { R.string.app_name })
        binding.topBar.menu.clear()
        // The fragment may not be created yet on the very first layout pass; onResume re-runs this.
        val menuRes = (currentTabFragment() as? TabMenuHost)?.tabMenuRes ?: 0
        if (menuRes != 0) binding.topBar.inflateMenu(menuRes)
    }

    /**
     * FragmentStateAdapter tags its pages "f<itemId>", and our item ids are positions — so this is
     * how the activity reaches the tab that is currently on screen.
     */
    private fun currentTabFragment(): Fragment? =
        supportFragmentManager.findFragmentByTag("f${binding.pager.currentItem}")

    /**
     * The sidebar. Bottom navigation is capped at five items, so the tabs stay where they are and
     * everything that doesn't fit — Friends, plus the set-up-once screens — lives in the drawer.
     * Drawer entries that correspond to a tab just move the pager; the rest open their activity.
     */
    private fun setupDrawer() {
        binding.navDrawer.setCheckedItem(TABS[binding.pager.currentItem])
        binding.navDrawer.setNavigationItemSelectedListener { item ->
            binding.drawerLayout.close()
            val tabIndex = TABS.indexOfFirst { it == item.itemId }
            if (tabIndex >= 0) {
                binding.pager.setCurrentItem(tabIndex, false)
                return@setNavigationItemSelectedListener true
            }
            val destination = when (item.itemId) {
                R.id.nav_friends -> com.example.alarmtracker.ui.friends.FriendsActivity::class.java
                R.id.nav_settings -> com.example.alarmtracker.ui.settings.SettingsActivity::class.java
                R.id.nav_connections -> com.example.alarmtracker.ui.connections.ConnectionsActivity::class.java
                R.id.nav_health -> com.example.alarmtracker.ui.health.HealthCheckActivity::class.java
                R.id.nav_recycle -> com.example.alarmtracker.ui.recycle.RecycleBinActivity::class.java
                R.id.nav_help -> com.example.alarmtracker.ui.help.HelpActivity::class.java
                else -> null
            } ?: return@setNavigationItemSelectedListener false
            startActivity(Intent(this, destination))
            // These are side trips, not destinations — keep the tab underneath selected.
            binding.navDrawer.setCheckedItem(TABS[binding.pager.currentItem])
            true
        }
        onBackPressedDispatcher.addCallback(this) {
            if (binding.drawerLayout.isOpen) {
                binding.drawerLayout.close()
            } else {
                // Step out of the way for one press, then re-arm — leaving it disabled would mean
                // back stopped closing the drawer after the first time the user exited a screen.
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Fragments are restored after onCreate, so the first updateTopBar may have found none.
        updateTopBar(binding.pager.currentItem)
        recordEveningSignalIfNeeded()
    }

    /**
     * Zero-permission bedtime proxy (feature 6): if the user opens the app in the
     * evening, note it as an "app activity" signal — a stand-in for "still up / heading
     * to bed" used to estimate last night's sleep opportunity. Throttled so at most one
     * evening signal is stored per session and a manual bedtime tap is never overwritten.
     */
    private fun recordEveningSignalIfNeeded() {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val isEvening = hour >= 20 || hour < 4
        if (!isEvening) return
        val appCtx = applicationContext
        lifecycleScope.launch {
            val repo = com.example.alarmtracker.data.AlarmRepository.get(appCtx)
            val latest = repo.latestSleepSignal()
            val now = System.currentTimeMillis()
            if (latest == null || now - latest.occurredAt > EVENING_SIGNAL_THROTTLE_MS) {
                repo.recordSleepSignal(
                    com.example.alarmtracker.data.SleepSignal.SOURCE_APP_EVENING, now
                )
            }
        }
    }

    /** The 5 swipeable tabs, in order, keyed by their bottom-nav menu id. Settings is NOT one of
     * them any more — it lives in the sidebar, which freed its slot for the world clock. */
    private class TabAdapter(activity: androidx.fragment.app.FragmentActivity) :
        androidx.viewpager2.adapter.FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = TABS.size
        override fun createFragment(position: Int): Fragment = when (TABS[position]) {
            R.id.nav_world_clock -> com.example.alarmtracker.ui.worldclock.WorldClockFragment()
            R.id.nav_stopwatch -> com.example.alarmtracker.ui.stopwatch.StopwatchFragment()
            R.id.nav_timer -> com.example.alarmtracker.ui.timer.TimerFragment()
            R.id.nav_stats -> StatsFragment()
            else -> AlarmsFragment()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private companion object {
        /** Tab order — must match the bottom-nav menu order (Alarms, Stopwatch, Timer, Stats, Settings). */
        val TABS = intArrayOf(
            R.id.nav_alarms, R.id.nav_world_clock, R.id.nav_stopwatch, R.id.nav_timer, R.id.nav_stats
        )

        /** Shared top-bar title per tab; same order as [TABS]. */
        val TAB_TITLES = intArrayOf(
            R.string.title_alarms, R.string.title_world_clock, R.string.title_stopwatch,
            R.string.title_timer, R.string.title_stats
        )
        const val EVENING_SIGNAL_THROTTLE_MS = 4L * 60 * 60 * 1000
    }
}
