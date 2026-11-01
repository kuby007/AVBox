package com.github.avbox.osc.ui.base

import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.KV
import com.github.avbox.osc.util.KVKeys
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.DisplayMetrics
import android.view.View
import android.view.ViewGroup

import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.PermissionChecker
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle

import com.github.avbox.osc.R
import com.github.avbox.osc.ui.WindowSize
import com.github.avbox.osc.ui.settings.UiSettingsState
import com.github.avbox.osc.util.ActivityStack
import com.github.avbox.osc.util.LanguageManager
import com.github.avbox.osc.util.Probe
import com.github.avbox.osc.util.SpiderStorageNotice

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

import me.jessyan.autosize.AutoSizeCompat
import me.jessyan.autosize.AutoSizeConfig
import me.jessyan.autosize.internal.CustomAdapt
import com.github.avbox.osc.util.CutoutUtil

abstract class BaseActivity : AppCompatActivity(), CustomAdapt {

    @JvmField
    protected var hostContext: Context? = null

    private var orientationPolicy = Int.MIN_VALUE

    private val refreshAutoSizeRunnable = Runnable {
        if (shouldRefreshAutoSize()) {
            refreshAutoSize()
        }
    }

    private val hideSysBarRunnable = Runnable {
        hideSysBar()
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguageManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            if (screenRatio < 0) {
                val dm = DisplayMetrics()
                windowManager.defaultDisplay.getMetrics(dm)
                updateScreenRatio(dm)
            }
        } catch (th: Throwable) {
            AppLog.e("BaseActivity", th)
        }
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setNavigationBarContrastEnforced(false)
            window.setStatusBarContrastEnforced(false)
        }
        setContentView(ComposeView(this).apply {
            id = R.id.compose_view
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        })
        hostContext = this
        initSystemUiListener()
        observeHideStatusBar()
        CutoutUtil.adaptCutoutAboveAndroidP(this, true)
        ActivityStack.getInstance().addActivity(this)
        applyOrientationPolicy()
        init()
    }

    override fun onResume() {
        Probe.measure(this, "onResume") {
            super.onResume()
            SpiderStorageNotice.onHostResumed(this)
            applyOrientationPolicy()
            applyHideStatusBarPref()
            hideSysBar()
            if (shouldRefreshAutoSize()) {
                refreshAutoSize()
                scheduleRefreshAutoSize()
            }
        }
    }

    internal fun applyHideStatusBarPref() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (UiSettingsState.config.hideStatusBar || keepStatusBarHidden()) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
    }

    private fun observeHideStatusBar() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                snapshotFlow { UiSettingsState.config.hideStatusBar }
                    .distinctUntilChanged()
                    .collect { applyHideStatusBarPref() }
            }
        }
    }

    protected open fun keepStatusBarHidden(): Boolean = false

    open fun hideSysBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            var uiOptions = window.decorView.systemUiVisibility
            uiOptions = uiOptions or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            uiOptions = uiOptions or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            uiOptions = uiOptions or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            uiOptions = uiOptions or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            uiOptions = uiOptions or View.SYSTEM_UI_FLAG_FULLSCREEN
            uiOptions = uiOptions or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            window.decorView.systemUiVisibility = uiOptions
        }
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun initSystemUiListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            val decorView = window.decorView
            decorView.setOnSystemUiVisibilityChangeListener { visibility ->
                val hiddenBars = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN
                if ((visibility and hiddenBars) != hiddenBars) {
                    decorView.removeCallbacks(hideSysBarRunnable)
                    decorView.postDelayed(hideSysBarRunnable, SYSBAR_REHIDE_DELAY_MS)
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        SpiderStorageNotice.onHostPaused(this)
        window.decorView.removeCallbacks(refreshAutoSizeRunnable)
        window.decorView.removeCallbacks(hideSysBarRunnable)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        Probe.measure(this, "onWindowFocusChanged") {
            super.onWindowFocusChanged(hasFocus)
            if (hasFocus) {
                applyHideStatusBarPref()
                hideSysBar()
                if (shouldRefreshAutoSize()) {
                    scheduleRefreshAutoSize()
                }
            }
        }
    }

    protected open fun shouldRefreshAutoSize(): Boolean {
        return false
    }

    open fun applyOrientationPolicy() {
        try {
            val desired = orientationPolicyValue()
            if (orientationPolicy == desired) {
                return
            }
            orientationPolicy = desired
            requestedOrientation = desired
        } catch (th: Throwable) {
            AppLog.e("BaseActivity", th)
        }
    }

    open fun orientationPolicyValue(): Int {
        try {
            val follow = KV.get(
                KVKeys.ORIENTATION_FOLLOW_SYSTEM,
                KVKeys.ORIENTATION_FOLLOW_SYSTEM_DEFAULT
            )
            if (follow) return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            val configuration = super.getResources().configuration
            return if (WindowSize.shouldLockPortrait(configuration.smallestScreenWidthDp))
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } catch (th: Throwable) {
            AppLog.e("BaseActivity", th)
            return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientationPolicy()
    }

    private fun scheduleRefreshAutoSize() {
        val decorView = window.decorView
        decorView.removeCallbacks(refreshAutoSizeRunnable)
        decorView.postDelayed(refreshAutoSizeRunnable, 300)
    }

    private fun refreshAutoSize() {
        try {
            val dm = DisplayMetrics()
            windowManager.defaultDisplay.getMetrics(dm)
            if (dm.widthPixels <= 0 || dm.heightPixels <= 0) {
                return
            }
            updateScreenRatio(dm)
            AutoSizeConfig.getInstance()
                .setScreenWidth(dm.widthPixels)
                .setScreenHeight(dm.heightPixels)
            AutoSizeCompat.autoConvertDensityOfCustomAdapt(super.getResources(), this)
            window.decorView.requestLayout()
        } catch (th: Throwable) {
            AppLog.e("BaseActivity", th)
        }
    }

    private fun updateScreenRatio(dm: DisplayMetrics) {
        val screenWidth = dm.widthPixels
        val screenHeight = dm.heightPixels
        val min = Math.min(screenWidth, screenHeight)
        if (min > 0) {
            screenRatio = Math.max(screenWidth, screenHeight).toFloat() / min.toFloat()
        }
    }

    override fun getResources(): Resources {
        if (Looper.myLooper() === Looper.getMainLooper()) {
            AutoSizeCompat.autoConvertDensityOfCustomAdapt(super.getResources(), this)
        }
        return super.getResources()
    }

    fun hasPermission(permission: String): Boolean {
        var has = true
        try {
            has = PermissionChecker.checkSelfPermission(this, permission) == PermissionChecker.PERMISSION_GRANTED
        } catch (e: Exception) {
            AppLog.e("BaseActivity", e)
        }
        return has
    }

    protected abstract fun init()

    override fun onDestroy() {
        super.onDestroy()
        ActivityStack.getInstance().finishActivity(this)
    }

    protected fun assetText(fileName: String): String {
        val stringBuilder = StringBuilder()
        try {
            val assets = assets
            val bf = BufferedReader(InputStreamReader(assets.open(fileName)))
            var line = bf.readLine()
            while (line != null) {
                stringBuilder.append(line)
                line = bf.readLine()
            }
            return stringBuilder.toString()
        } catch (e: IOException) {
            AppLog.e("BaseActivity", e)
        }
        return ""
    }

    override fun getSizeInDp(): Float {
        return if (isBaseOnWidth()) 1280f else 720f
    }

    override fun isBaseOnWidth(): Boolean {
        return !(screenRatio >= 4.0f)
    }

    companion object {
        private const val SYSBAR_REHIDE_DELAY_MS = 100L

        private var screenRatio = -100.0f
    }
}
