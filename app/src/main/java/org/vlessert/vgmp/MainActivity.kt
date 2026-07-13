package org.vlessert.vgmp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
    
    // Auto-hide for main screen
    private var lastInteractionTime = System.currentTimeMillis()
    private val autoHideHandler = Handler(Looper.getMainLooper())
    private var autoHideRunnable: Runnable? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? VgmServiceBinder ?: return
            val svc = localBinder.getService()
            playbackService = svc
            serviceBound = true
            lifecycleScope.launch {
                svc.playbackInfo.collectLatest {
                    updateMiniPlayer()
                }
            }
            supportFragmentManager.fragments.filterIsInstance<NowPlayingFragment>()
                .forEach { it.onServiceConnected(playbackService!!) }
            
            // Start observing spectrum for kaleidoscope
            startSpectrumObserver()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            binding.bottomNavigation.post { showTab(binding.bottomNavigation.selectedItemId) }
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
        
        // Setup auto-hide for main screen
        setupMainAutoHide()

        // Handle back button for library minimization
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val browser = supportFragmentManager.findFragmentByTag("tab_browse") as? BrowserFragment
                if (currentTabId == R.id.nav_browse && browser?.navigateUp() == true) return
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
    
    private fun setupMainAutoHide() {
        // Track touches on main content
        binding.mainContent.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                lastInteractionTime = System.currentTimeMillis()
                if (isAnalyzerVisible) {
                    hideAnalyzer()
                }
            }
            false
        }
        binding.mainContent.isClickable = true
        
        // Start auto-hide check
        autoHideRunnable = object : Runnable {
            override fun run() {
                val timeout = SettingsManager.getFadeTimeout(this@MainActivity) * 1000L
                val isPlayerOpen = currentTabId == R.id.nav_player
                val isSettingsOpen = supportFragmentManager.findFragmentByTag("settings")?.isVisible == true
                val isPlaying = playbackService?.playing == true
                
                // Only trigger kaleidoscope if:
                // - Timeout is set (> 0)
                // - Kaleidoscope not already visible
                // - No dialogs are open (player or settings)
                // - Music is actually playing
                // - Inactivity timeout reached
                val anyDialogOpen = isPlayerOpen || isSettingsOpen
                if (timeout > 0 && !isAnalyzerVisible && !anyDialogOpen && isPlaying && System.currentTimeMillis() - lastInteractionTime >= timeout) {
                    showAnalyzer()
                }
                autoHideHandler.postDelayed(this, 1000)
            }
        }
        autoHideHandler.postDelayed(autoHideRunnable!!, 1000)
    }
    
    override fun onUserInteraction() {
        super.onUserInteraction()
        lastInteractionTime = System.currentTimeMillis()
    }
    
    private fun startSpectrumObserver() {
        val svc = playbackService ?: return
        lifecycleScope.launch {
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
    
    private fun showAnalyzer() {
        // Only show if enabled and music is playing
        if (!SettingsManager.isAnalyzerEnabled(this)) return
        if (playbackService?.playing != true) return
        
        isAnalyzerVisible = true
        
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
        
        // Restore system UI
        showSystemUI()
        
        lastInteractionTime = System.currentTimeMillis()
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
    
    fun resetAutoHideTimer() {
        lastInteractionTime = System.currentTimeMillis()
    }

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
        startForegroundService(intent)
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
        updateContentBottomMargin()
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
        val game = svc.currentGame
        binding.miniPlayer.tvMiniTitle.text = track?.title ?: getString(R.string.no_track_playing)
        binding.miniPlayer.tvMiniGame.text = game?.name ?: ""
        val isPlaying = svc.playing
        binding.miniPlayer.btnMiniPlayPause.setImageResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
        if (game?.artPath?.isNotEmpty() == true) {
            try {
                val bm = android.graphics.BitmapFactory.decodeFile(game.artPath)
                binding.miniPlayer.ivMiniArt.setImageBitmap(bm)
            } catch (e: Exception) {
                binding.miniPlayer.ivMiniArt.setImageResource(R.drawable.vgmp_logo)
            }
        } else {
            binding.miniPlayer.ivMiniArt.setImageResource(R.drawable.vgmp_logo)
        }
    }

    fun getService() = playbackService
    
    fun refreshLibrary() {
        // Refresh playback service's game list
        playbackService?.refreshGames()
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
}

class VgmServiceBinder(private val service: VgmPlaybackService) : android.os.Binder() {
    fun getService() = service
}
