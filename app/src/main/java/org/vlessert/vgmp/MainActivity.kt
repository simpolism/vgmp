package org.vlessert.vgmp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.graphics.Color
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.vlessert.vgmp.databinding.ActivityMainBinding
import org.vlessert.vgmp.engine.VgmEngine
import org.vlessert.vgmp.service.VgmPlaybackService
import org.vlessert.vgmp.ui.BrowserFragment
import org.vlessert.vgmp.ui.NowPlayingFragment
import org.vlessert.vgmp.ui.PlaylistsFragment
import org.vlessert.vgmp.settings.SettingsManager

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var playbackService: VgmPlaybackService? = null
    private var serviceBound = false
    private var isAnalyzerVisible = false
    private var currentTabId = R.id.nav_browse
    private var navigationInsetBottom = 0
    private var restoreAnalyzerWhenReady = false
    private var playbackInfoJob: Job? = null
    private var miniProgressJob: Job? = null
    private var spectrumJob: Job? = null
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? VgmServiceBinder ?: return
            val svc = localBinder.getService()
            playbackService = svc
            serviceBound = true
            playbackInfoJob?.cancel()
            playbackInfoJob = lifecycleScope.launch {
                svc.playbackInfo.collectLatest { info ->
                    updateMiniPlayer()
                    if (restoreAnalyzerWhenReady && info.track != null) {
                        restoreAnalyzerWhenReady = false
                        showAnalyzer()
                    }
                }
            }
            miniProgressJob?.cancel()
            miniProgressJob = lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    while (isActive) {
                        if (currentTabId != R.id.nav_player && svc.playing) updateMiniPlayer()
                        delay(MINI_PROGRESS_INTERVAL_MS)
                    }
                }
            }
            supportFragmentManager.fragments.filterIsInstance<NowPlayingFragment>()
                .forEach { it.onServiceConnected(playbackService!!) }
            supportFragmentManager.fragments.filterIsInstance<BrowserFragment>()
                .forEach { it.onServiceConnected(svc) }
            supportFragmentManager.fragments.filterIsInstance<PlaylistsFragment>()
                .forEach { it.onServiceConnected(svc) }
            svc.setVisualizerActive(isAnalyzerVisible && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
            
            // Start observing spectrum for kaleidoscope
            startSpectrumObserver()
            if (restoreAnalyzerWhenReady && svc.currentTrack != null) {
                restoreAnalyzerWhenReady = false
                showAnalyzer()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackInfoJob?.cancel()
            miniProgressJob?.cancel()
            spectrumJob?.cancel()
            playbackService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentTabId = savedInstanceState?.getInt(STATE_TAB, R.id.nav_browse) ?: R.id.nav_browse
        restoreAnalyzerWhenReady = savedInstanceState?.getBoolean(STATE_ANALYZER, false) == true
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()
        setSupportActionBar(binding.toolbar)
        requestPermissionsIfNeeded()
        startPlaybackService()

        binding.bottomNavigation.setOnItemSelectedListener { item -> showTab(item.itemId); true }
        if (savedInstanceState == null) {
            binding.bottomNavigation.selectedItemId = R.id.nav_browse
        } else {
            binding.bottomNavigation.selectedItemId = currentTabId
            binding.bottomNavigation.post { showTab(currentTabId) }
        }

        binding.miniPlayer.root.setOnClickListener {
            selectPlayerTab()
        }
        binding.miniPlayer.btnMiniPrev.setOnClickListener {
            playbackService?.previousTrack()
        }
        binding.miniPlayer.btnMiniPlayPause.setOnClickListener {
            val svc = playbackService ?: return@setOnClickListener
            if (svc.playing) svc.getMediaSession().controller.transportControls.pause()
            else svc.getMediaSession().controller.transportControls.play()
        }
        binding.miniPlayer.btnMiniNext.setOnClickListener {
            playbackService?.nextTrack()
        }
        
        // Setup analyzer touch listener
        binding.kaleidoscopeView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                if (isAnalyzerVisible) {
                    hideAnalyzer()
                    true
                } else {
                    false
                }
            } else {
                false
            }
        }
        binding.spectrumBarsView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                if (isAnalyzerVisible) {
                    hideAnalyzer()
                    true
                } else {
                    false
                }
            } else {
                false
            }
        }
        
        // Back unwinds overlays and tabs before navigating the selected tree or backgrounding.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isAnalyzerVisible) {
                    hideAnalyzer()
                    return
                }
                if (currentTabId != R.id.nav_browse) {
                    binding.bottomNavigation.selectedItemId = R.id.nav_browse
                    return
                }
                val browser = supportFragmentManager.findFragmentByTag("tab_browse") as? BrowserFragment
                if (browser?.navigateUp() == true) return
                moveTaskToBack(true)
            }
        })
    }

    private fun applySystemBarInsets() {
        val toolbarBaseHeight = binding.toolbar.layoutParams.height
        val toolbarBasePaddingTop = binding.toolbar.paddingTop
        val contentBaseTopMargin =
            (binding.fragmentContainer.layoutParams as ViewGroup.MarginLayoutParams).topMargin
        val navigationBaseHeight = binding.bottomNavigation.layoutParams.height
        val navigationBasePaddingBottom = binding.bottomNavigation.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val statusBarTop = windowInsets
                .getInsets(WindowInsetsCompat.Type.statusBars())
                .top

            binding.toolbar.updateLayoutParams {
                height = toolbarBaseHeight + statusBarTop
            }
            binding.toolbar.updatePadding(top = toolbarBasePaddingTop + statusBarTop)
            binding.fragmentContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = contentBaseTopMargin + statusBarTop
            }
            val navigationBottom = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            navigationInsetBottom = navigationBottom
            binding.bottomNavigation.updateLayoutParams { height = navigationBaseHeight + navigationBottom }
            binding.bottomNavigation.updatePadding(bottom = navigationBasePaddingBottom + navigationBottom)
            binding.miniPlayer.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = 64.dp + navigationBottom
            }
            updateContentBottomMargin()

            windowInsets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }
    
    private fun startSpectrumObserver() {
        val svc = playbackService ?: return
        spectrumJob?.cancel()
        spectrumJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                svc.spectrum.collect { magnitudes ->
                    if (isAnalyzerVisible) {
                        // Hide analyzer if music stopped
                        if (!svc.playing) {
                            hideAnalyzer()
                        } else {
                            when (SettingsManager.getAnalyzerStyle(this@MainActivity)) {
                                SettingsManager.ANALYZER_STYLE_BARS -> binding.spectrumBarsView.updateFFT(magnitudes)
                                else -> binding.kaleidoscopeView.updateFFT(magnitudes)
                            }
                        }
                    }
                }
            }
        }
    }
    
    fun showAnalyzer() {
        // Fullscreen visualizers are opened explicitly from the player.
        if (playbackService?.currentTrack == null) return
        
        isAnalyzerVisible = true
        playbackService?.setVisualizerActive(true)
        
        // Hide system UI for true fullscreen
        hideSystemUI()
        
        val style = SettingsManager.getAnalyzerStyle(this)
        val showKaleidoscope = style == SettingsManager.ANALYZER_STYLE_KALEIDOSCOPE
        binding.kaleidoscopeView.visibility = if (showKaleidoscope) View.VISIBLE else View.GONE
        binding.spectrumBarsView.visibility = if (showKaleidoscope) View.GONE else View.VISIBLE

        val targetView = if (showKaleidoscope) binding.kaleidoscopeView else binding.spectrumBarsView
        targetView.alpha = 0f
        targetView.animate().alpha(1f).setDuration(500).start()
        // Hide main content
        binding.mainContent.animate()
            .alpha(0f)
            .setDuration(500)
            .withEndAction {
                binding.mainContent.visibility = View.GONE
            }
            .start()
    }
    
    private fun hideAnalyzer() {
        if (!isAnalyzerVisible) return
        isAnalyzerVisible = false
        playbackService?.setVisualizerActive(false)
        
        // Restore system UI
        showSystemUI()
        
        binding.mainContent.visibility = View.VISIBLE
        binding.mainContent.alpha = 0f
        binding.mainContent.animate()
            .alpha(1f)
            .setDuration(500)
            .start()
        binding.kaleidoscopeView.animate()
            .alpha(0f)
            .setDuration(500)
            .withEndAction { binding.kaleidoscopeView.visibility = View.GONE }
            .start()
        binding.spectrumBarsView.animate()
            .alpha(0f)
            .setDuration(500)
            .withEndAction { binding.spectrumBarsView.visibility = View.GONE }
            .start()
    }
    
    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        }
    }
    
    private fun showSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }
    
    fun isKaleidoscopeShowing() = isAnalyzerVisible
    
    private fun requestPermissionsIfNeeded() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        val notGranted = perms.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, notGranted.toTypedArray(), 42)
        }
    }

    private fun startPlaybackService() {
        val intent = Intent(this, VgmPlaybackService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    }

    private fun showTab(itemId: Int) {
        currentTabId = itemId
        val (fragment, tag, title) = when (itemId) {
            R.id.nav_playlists -> Triple(PlaylistsFragment.newInstance(), "tab_playlists", "Playlists")
            R.id.nav_player -> Triple(NowPlayingFragment.newInstance(), "tab_player", "Now Playing")
            else -> Triple(BrowserFragment.newInstance(), "tab_browse", "Browse")
        }
        supportActionBar?.title = title
        val target = supportFragmentManager.findFragmentByTag(tag) ?: fragment
        val transaction = supportFragmentManager.beginTransaction()
        supportFragmentManager.fragments
            .filter { it.id == R.id.fragment_container && it != target }
            .forEach(transaction::hide)
        if (target.isAdded) transaction.show(target) else transaction.add(R.id.fragment_container, target, tag)
        transaction.commit()
        val playerTab = itemId == R.id.nav_player
        binding.miniPlayer.root.visibility = if (playerTab) View.GONE else View.VISIBLE
        binding.miniProgress.visibility = if (playerTab) View.GONE else binding.miniProgress.visibility
        updateContentBottomMargin()
        if (playbackService != null) updateMiniPlayer()
    }

    private fun updateContentBottomMargin() {
        val playerTab = currentTabId == R.id.nav_player
        binding.fragmentContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = (if (playerTab) 64.dp else 136.dp) + navigationInsetBottom
        }
    }

    fun selectPlayerTab() {
        binding.bottomNavigation.selectedItemId = R.id.nav_player
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density + 0.5f).toInt()

    fun updateMiniPlayer() {
        val svc = playbackService ?: return
        val track = svc.currentTrack
        binding.miniPlayer.tvMiniTitle.text = track?.title.orEmpty()
        binding.miniPlayer.tvMiniGame.text = if (track == null) "" else svc.getCurrentTags().displayGame
        val hasQueue = svc.queueTracks.value.isNotEmpty()
        binding.miniPlayer.btnMiniPrev.isEnabled = hasQueue
        binding.miniPlayer.btnMiniPlayPause.isEnabled = hasQueue
        binding.miniPlayer.btnMiniNext.isEnabled = hasQueue
        val duration = svc.playbackInfo.value.durationMs
        binding.miniProgress.visibility = if (
            currentTabId != R.id.nav_player && track != null && duration > 0
        ) View.VISIBLE else View.GONE
        binding.miniProgress.progress = if (duration > 0) {
            (svc.getCurrentPositionMs() * 1000L / duration).toInt().coerceIn(0, 1000)
        } else 0
        val isPlaying = svc.playing
        binding.miniPlayer.btnMiniPlayPause.setImageResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
        svc.artwork.value?.let(binding.miniPlayer.ivMiniArt::setImageBitmap)
            ?: binding.miniPlayer.ivMiniArt.setImageResource(R.drawable.vgmp_logo)
    }

    fun getService() = playbackService

    override fun onStart() {
        super.onStart()
        if (isAnalyzerVisible) playbackService?.setVisualizerActive(true)
    }

    override fun onStop() {
        playbackService?.setVisualizerActive(false)
        super.onStop()
    }
    
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                org.vlessert.vgmp.ui.SettingsDialogFragment.newInstance()
                    .show(supportFragmentManager, "settings")
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_TAB, currentTabId)
        outState.putBoolean(STATE_ANALYZER, isAnalyzerVisible || restoreAnalyzerWhenReady)
        super.onSaveInstanceState(outState)
    }

    companion object {
        private const val STATE_TAB = "selected_tab"
        private const val STATE_ANALYZER = "analyzer_visible"
        private const val MINI_PROGRESS_INTERVAL_MS = 500L
    }
}

class VgmServiceBinder(private val service: VgmPlaybackService) : android.os.Binder() {
    fun getService() = service
}
