package com.kimoitv

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.api.Log
import com.lagradost.cloudstream3.CommonActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume


object cloudflare {

    val running = AtomicBoolean(false)

    private var pending: CompletableDeferred<Boolean>? = null

    var cookies: String = K.EMPTY
    var userAgent: String = K.EMPTY

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(K.PREFS, Context.MODE_PRIVATE)
        cookies = prefs.getString(K.KEY_COOKIES, K.EMPTY).orEmpty()
        userAgent = prefs.getString(K.KEY_USER_AGENT, K.EMPTY).orEmpty()
        Log.d(K.TAG, "loaded clearance=${hasClearance()} ua=${userAgent.take(K.UA_LOG)}")
    }

    fun save(context: Context, cookies: String, userAgent: String) {
        this.cookies = cookies
        this.userAgent = userAgent
        context.getSharedPreferences(K.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(K.KEY_COOKIES, cookies)
            .putString(K.KEY_USER_AGENT, userAgent)
            .apply()
    }

    fun hasClearance(): Boolean = cookies.contains(K.COOKIE_CLEARANCE)

    fun isChallengeTitle(title: String): Boolean =
        K.CHALLENGE_TITLES.any { title.lowercase().contains(it) }

    fun isBlocked(code: Int, text: String): Boolean =
        code == K.HTTP_FORBIDDEN || code == K.HTTP_UNAVAILABLE ||
            K.CHALLENGE_PHRASES.any { text.lowercase().contains(it) }

    fun bypassUrl(): String = K.BASE_URL + K.PATH_BYPASS

    suspend fun solve(target: String = bypassUrl()): Boolean {
        if (hasClearance()) return true
        pending?.let { return it.await() }
        val deferred = CompletableDeferred<Boolean>()
        pending = deferred
        return try {
            val solved = show(target)
            deferred.complete(solved)
            solved
        } finally {
            if (pending === deferred) pending = null
        }
    }

    private suspend fun show(target: String): Boolean = withContext(Dispatchers.Main) {
        if (!running.compareAndSet(false, true)) return@withContext false
        try {
            suspendCancellableCoroutine { continuation ->
                val activity = CommonActivity.activity as? AppCompatActivity
                if (activity == null || activity.isFinishing || activity.isDestroyed) {
                    Log.e(K.TAG, "no activity to show bypass")
                    continuation.resume(false)
                    return@suspendCancellableCoroutine
                }
                var resumed = false
                val finish = { success: Boolean ->
                    if (!resumed) {
                        resumed = true
                        continuation.resume(success)
                    }
                }
                val dialog = bypassdialog(target, finish)
                continuation.invokeOnCancellation {
                    activity.runOnUiThread { runCatching { dialog.dismissAllowingStateLoss() } }
                }
                dialog.show(activity.supportFragmentManager, K.TAG)
            }
        } finally {
            running.set(false)
        }
    }

    fun show(activity: AppCompatActivity) {
        if (!running.compareAndSet(false, true)) return
        bypassdialog(bypassUrl()) { running.set(false) }
            .show(activity.supportFragmentManager, K.TAG)
    }
}

class bypassdialog(
    private val targetUrl: String,
    private val onFinished: (Boolean) -> Unit,
) : BottomSheetDialogFragment() {

    private val handler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var status: TextView? = null
    private var progress: ProgressBar? = null
    private var saved = false
    private var elapsed = 0L

    private val origin: String by lazy {
        runCatching { Uri.parse(targetUrl).let { "${it.scheme}://${it.host}" } }.getOrDefault(targetUrl)
    }

    private val poll = object : Runnable {
        override fun run() {
            if (saved || !isAdded) return
            val current = CookieManager.getInstance().let {
                it.flush()
                it.getCookie(origin).orEmpty()
            }
            when {
                current.contains(K.COOKIE_CLEARANCE) -> store(current)
                elapsed >= K.POLL_TIMEOUT_MS -> status(K.STATUS_TIMEOUT)
                else -> schedule()
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_COLLAPSED
            skipCollapsed = false
            peekHeight = 0
        }
        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
            sheet.layoutParams = sheet.layoutParams.apply {
                height = ViewGroup.LayoutParams.MATCH_PARENT
            }
            sheet.requestLayout()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val height =
            (requireContext().resources.displayMetrics.heightPixels * K.WEBVIEW_HEIGHT_RATIO).toInt()
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
            setBackgroundColor(Color.parseColor(K.COLOR_BACKGROUND))
        }
        root.addView(label(K.NAME + K.LABEL_BYPASS, 18f, Color.WHITE))
        status = label(K.STATUS_LOADING, 13f, Color.parseColor(K.STATUS_PENDING))
        root.addView(status)
        root.addView(label(K.HINT_BYPASS, 11f, Color.parseColor(K.COLOR_HINT)))
        progress = ProgressBar(
            requireContext(),
            null,
            android.R.attr.progressBarStyleHorizontal,
        ).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.bottomMargin = 12 }
        }
        root.addView(progress)
        val built = build()
        webView = built
        root.addView(
            FrameLayout(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    height,
                )
                addView(
                    built,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        )
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val manager = CookieManager.getInstance()
        manager.setAcceptCookie(true)
        webView?.let { manager.setAcceptThirdPartyCookies(it, true) }
        manager.flush()
        webView?.loadUrl(targetUrl)
        handler.postDelayed(poll, K.POLL_INTERVAL_MS)
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        if (!saved) {
            handler.removeCallbacks(poll)
            onFinished(false)
        }
    }

    override fun onDestroyView() {
        handler.removeCallbacks(poll)
        webView?.apply {
            stopLoading()
            destroy()
        }
        webView = null
        super.onDestroyView()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun build(): WebView = WebView(requireContext()).apply {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowContentAccess = true
            allowFileAccess = true
            loadsImagesAutomatically = true
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, value: Int) {
                super.onProgressChanged(view, value)
                if (!saved) status("loading $value%")
            }
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean = false

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (saved) return
                if (cloudflare.isChallengeTitle(view?.title.orEmpty())) {
                    status(K.STATUS_CHALLENGE)
                    return
                }
                status(K.STATUS_CHECKING)
                val manager = CookieManager.getInstance()
                manager.flush()
                val fromOrigin = manager.getCookie(origin).orEmpty()
                val fromUrl = url.orEmpty().let {
                    runCatching {
                        Uri.parse(it).let { host -> manager.getCookie("${host.scheme}://${host.host}") }
                    }.getOrNull().orEmpty()
                }
                when {
                    fromOrigin.contains(K.COOKIE_CLEARANCE) -> store(fromOrigin)
                    fromUrl.contains(K.COOKIE_CLEARANCE) -> store(fromUrl)
                }
            }
        }
    }

    private fun label(text: String, size: Float, color: Int): TextView = TextView(requireContext()).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        setPadding(0, 0, 0, 8)
    }

    private fun schedule() {
        elapsed += K.POLL_INTERVAL_MS
        status("waiting ${elapsed / 1000}s")
        if (elapsed >= K.POLL_INTERVAL_MS) {
            (dialog as? BottomSheetDialog)?.behavior?.apply {
                skipCollapsed = true
                peekHeight = WindowManager.LayoutParams.MATCH_PARENT
                state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
        handler.postDelayed(poll, K.POLL_INTERVAL_MS)
    }

    private fun store(cookies: String) {
        if (saved) return
        saved = true
        handler.removeCallbacks(poll)
        val ua = webView?.settings?.userAgentString.orEmpty()
        val context = context ?: return
        cloudflare.save(context, cookies, ua)
        Log.d(K.TAG, "clearance saved ua=${ua.take(K.UA_LOG)}")
        status(K.STATUS_DONE)
        webView?.postDelayed({
            if (isAdded) {
                onFinished(true)
                dismissAllowingStateLoss()
            }
        }, K.DISMISS_DELAY_MS)
    }

    private fun status(text: String) {
        activity?.runOnUiThread {
            status?.text = text
            val done = text == K.STATUS_DONE
            progress?.visibility = if (done) View.GONE else View.VISIBLE
            status?.setTextColor(Color.parseColor(if (done) K.STATUS_OK else K.STATUS_PENDING))
        }
    }
}

object bypass : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val builder = original.newBuilder()
            .removeHeader(K.HEADER_REQUESTED_WITH)
            .header(K.HEADER_SEC_CH_UA_MOBILE, K.VALUE_MOBILE)
            .header(K.HEADER_SEC_CH_UA_PLATFORM, K.VALUE_PLATFORM)
        val userAgent = cloudflare.userAgent
        if (userAgent.isNotBlank()) builder.header(K.HEADER_USER_AGENT, userAgent)
        val saved = cloudflare.cookies
        if (saved.isNotBlank()) {
            val existing = original.header(K.HEADER_COOKIE).orEmpty()
            val base = existing.split(K.COOKIE_SEPARATOR)
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.startsWith(K.COOKIE_CLEARANCE_PREFIX) }
            val fresh = saved.split(K.COOKIE_SEPARATOR).map { it.trim() }.filter { it.isNotBlank() }
            builder.header(K.HEADER_COOKIE, (base + fresh).distinct().joinToString(K.COOKIE_JOIN))
        }
        return chain.proceed(builder.build())
    }
}