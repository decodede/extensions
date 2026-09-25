package com.reelfren

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object ReelFrenCf {
    private const val SOLVE_TIMEOUT_MS = 90_000L
    private const val POLL_MS = 1_200L

    private val indicators = listOf(
        "just a moment",
        "checking your browser",
        "cf-challenge",
        "cf_chl_opt",
        "cf_chl_prog",
        "enable javascript and cookies to continue",
        "attention required! | cloudflare",
        "challenges.cloudflare.com"
    )

    fun isChallenge(body: String): Boolean {
        val lower = body.lowercase()
        return indicators.any { lower.contains(it) }
    }

    fun host(url: String): String {
        val match = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://([^/?#|]+)").find(url)
            ?: return ""
        return match.groupValues[1].substringAfter("@").substringBefore(":")
    }

    fun setContext(context: Context) {
        appContext = context.applicationContext
    }

    @Volatile private var appContext: Context? = null

    fun activity(): Activity? {
        runCatching {
            val cls = Class.forName("com.lagradost.cloudstream3.CommonActivity")
            val field = cls.getDeclaredField("INSTANCE").apply { isAccessible = true }
            val instance = field.get(null)
            if (instance is Activity) return instance
            runCatching { cls.getMethod("getActivity").invoke(instance) }
                .getOrNull()
                ?.let { if (it is Activity) return it }
        }
        var ctx: Context? = appContext
        var depth = 0
        while (ctx != null && depth < 12) {
            if (ctx is Activity) return ctx
            ctx = (ctx as? ContextWrapper)?.baseContext
            depth++
        }
        return null
    }

    suspend fun solve(url: String): Boolean = withContext(Dispatchers.Main) {
        val host = host(url)
        val activity = activity() ?: return@withContext false
        val cookie = awaitCookie(activity, url) ?: return@withContext false
        if (cookie.contains("cf_clearance") && host.isNotEmpty()) {
            ReelFrenStore.saveCookie(host, cookie)
        }
        !cookie.isBlank()
    }

    @SuppressLint("SetJavaScriptEnabled", "SetTextI18n")
    private suspend fun awaitCookie(activity: Activity, url: String): String? {
        if (activity.isFinishing) return null
        val deferred = CompletableDeferred<String?>()
        val handler = Handler(Looper.getMainLooper())
        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        var tick: () -> Unit = {}
        val web = WebView(activity).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.userAgentString = ReelFrenClient.UA
            setBackgroundColor(Color.BLACK)
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, finished: String?) {
                    handler.postDelayed(Runnable { tick() }, 900L)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    if (request?.isForMainFrame == true) {
                        handler.postDelayed(Runnable { tick() }, 1_500L)
                    }
                }
            }
        }

        tick = {
            if (!deferred.isCompleted) {
                val cookie = runCatching { CookieManager.getInstance().getCookie(url) }
                    .getOrNull().orEmpty()
                val challenge = isChallenge(runCatching { web.title }.getOrNull().orEmpty())
                if (cookie.isNotBlank() && !challenge) deferred.complete(cookie)
            }
        }

        val root = FrameLayout(activity).apply {
            setBackgroundColor(Color.BLACK)
            addView(web, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#10151F"))
                addView(TextView(activity).apply {
                    text = "Solving Cloudflare…"
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(36, 24, 36, 8)
                })
                addView(TextView(activity).apply {
                    text = "If a checkbox appears, tap it. This is only needed once."
                    setTextColor(Color.parseColor("#8A9BB0"))
                    textSize = 11f
                    setPadding(36, 0, 36, 24)
                })
            }, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            ))
        }

        dialog.setContentView(root)
        dialog.window?.let {
            it.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
            it.setBackgroundDrawableResource(android.R.color.black)
        }
        dialog.setOnDismissListener {
            runCatching { web.stopLoading() }
            runCatching { web.destroy() }
            if (!deferred.isCompleted) deferred.complete(null)
        }
        CookieManager.getInstance().setAcceptCookie(true)
        runCatching { CookieManager.getInstance().setAcceptThirdPartyCookies(web, true) }
        dialog.show()
        web.loadUrl(url)
        val poller = object : Runnable {
            override fun run() {
                if (deferred.isCompleted) return
                tick()
                handler.postDelayed(this, POLL_MS)
            }
        }
        handler.postDelayed(poller, POLL_MS)
        val result = withTimeoutOrNull(SOLVE_TIMEOUT_MS) { deferred.await() }
        handler.removeCallbacks(poller)
        runCatching { if (dialog.isShowing) dialog.dismiss() }
        return result
    }
}
