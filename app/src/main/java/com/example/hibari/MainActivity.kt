package com.example.hibari

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.hibari.browser.HibariWebViewClient
import com.example.hibari.browser.TabInfo
import com.example.hibari.browser.WebViewFactory
import com.example.hibari.data.HistoryEntity
import com.example.hibari.databinding.ActivityMainBinding
import com.example.hibari.ui.BrowserViewModel
import com.example.hibari.ui.SettingsActivity
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: BrowserViewModel by viewModels()

    private var currentWebView: WebView? = null
    private var currentClient: HibariWebViewClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(false)
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupAddressBar()
        setupButtons()
        setupSuggestions()
        setupBackHandler()
        observeViewModel()

        if (viewModel.uiState.value.tabs.isEmpty()) {
            viewModel.createTab()
        }
        mountWebView()
    }

    override fun onPause() {
        super.onPause()
        currentWebView?.onPause()
    }

    override fun onResume() {
        super.onResume()
        currentWebView?.onResume()
    }

    override fun onDestroy() {
        super.onDestroy()
        currentWebView?.destroy()
        currentWebView = null
    }

    // ── Back handler ─────────────────────────────────────────────────────────

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentWebView?.canGoBack() == true) {
                    currentWebView?.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    // ── Address bar ──────────────────────────────────────────────────────────

    private fun setupAddressBar() {
        binding.etAddress.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                navigateToInput(binding.etAddress.text.toString())
                true
            } else false
        }

        binding.etAddress.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                viewModel.onAddressBarTextChanged(s?.toString() ?: "")
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        binding.etAddress.setOnFocusChangeListener { _, focused ->
            if (!focused) viewModel.clearSuggestions()
        }
    }

    private fun navigateToInput(input: String) {
        val url = viewModel.navigate(input)
        currentWebView?.loadUrl(url)
        binding.etAddress.clearFocus()
        hideKeyboard()
    }

    // ── Toolbar buttons ──────────────────────────────────────────────────────

    private fun setupButtons() {
        binding.btnBack.setOnClickListener { currentWebView?.goBack() }
        binding.btnForward.setOnClickListener { currentWebView?.goForward() }
        binding.btnStopRefresh.setOnClickListener {
            if (viewModel.uiState.value.isLoading) currentWebView?.stopLoading()
            else currentWebView?.reload()
        }
        binding.btnBookmark.setOnClickListener { viewModel.toggleBookmark() }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    // ── Suggestions RecyclerView ─────────────────────────────────────────────

    private fun setupSuggestions() {
        val adapter = SuggestionAdapter { suggestion ->
            navigateToInput(suggestion.url)
        }
        binding.rvSuggestions.adapter = adapter
        binding.rvSuggestions.layoutManager = LinearLayoutManager(this)
    }

    // ── ViewModel observation ────────────────────────────────────────────────

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    // Address bar (only update when not focused)
                    if (!binding.etAddress.isFocused) {
                        binding.etAddress.setText(state.displayUrl)
                    }

                    // Nav buttons
                    binding.btnBack.isEnabled = state.canGoBack
                    binding.btnForward.isEnabled = state.canGoForward

                    // Progress bar
                    if (state.isLoading && state.loadProgress < 100) {
                        binding.progressBar.visibility = View.VISIBLE
                        binding.progressBar.progress = state.loadProgress
                    } else {
                        binding.progressBar.visibility = View.INVISIBLE
                    }

                    // Stop / Refresh icon
                    binding.btnStopRefresh.setImageResource(
                        if (state.isLoading) R.drawable.ic_stop else R.drawable.ic_refresh,
                    )

                    // Bookmark icon
                    binding.btnBookmark.setImageResource(
                        if (state.isCurrentPageBookmarked) R.drawable.ic_bookmark
                        else R.drawable.ic_bookmark_border,
                    )

                    // Tabs
                    renderTabBar(state.tabs, state.activeTabId)

                    // Suggestions
                    (binding.rvSuggestions.adapter as SuggestionAdapter).submitList(state.suggestions)
                    binding.rvSuggestions.visibility =
                        if (state.suggestions.isNotEmpty()) View.VISIBLE else View.GONE
                }
            }
        }

        // Re-apply settings to active WebView when settings change
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.settingsState.collect { s ->
                    currentClient?.updateHttpsOnly(s.httpsOnly)
                    currentWebView?.let { WebViewFactory.applyThirdPartyCookies(it, s.blockThirdPartyCookies) }
                }
            }
        }
    }

    // ── Tab bar ──────────────────────────────────────────────────────────────

    private fun renderTabBar(tabs: List<TabInfo>, activeId: String) {
        binding.tabContainer.removeAllViews()

        tabs.forEach { tab ->
            val chip = LayoutInflater.from(this)
                .inflate(R.layout.item_tab, binding.tabContainer, false)
            chip.findViewById<TextView>(R.id.tabTitle).text =
                tab.title.ifBlank { tab.url.take(30) }
            chip.setBackgroundColor(
                if (tab.id == activeId)
                    getColor(R.color.purple_200)
                else
                    getColor(android.R.color.transparent),
            )
            chip.setOnClickListener { onTabSelected(tab.id) }
            chip.findViewById<ImageButton>(R.id.btnCloseTab)
                .setOnClickListener { onTabClosed(tab.id) }
            binding.tabContainer.addView(chip)
        }

        // "+" button
        val size = (28 * resources.displayMetrics.density).toInt()
        val addBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_add)
            setBackgroundResource(selectableItemBgBorderless())
            contentDescription = getString(R.string.menu_new_tab)
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginStart = 4 }
            setOnClickListener {
                viewModel.createTab()
                mountWebView()
            }
        }
        binding.tabContainer.addView(addBtn)
    }

    private fun selectableItemBgBorderless(): Int {
        val ta = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
        val res = ta.getResourceId(0, 0)
        ta.recycle()
        return res
    }

    // ── Tab operations ───────────────────────────────────────────────────────

    private fun onTabSelected(tabId: String) {
        if (tabId == viewModel.uiState.value.activeTabId) return
        suspendCurrentWebView()
        viewModel.switchTab(tabId)
        mountWebView()
    }

    private fun onTabClosed(tabId: String) {
        val isActive = tabId == viewModel.uiState.value.activeTabId
        val isPrivate = viewModel.uiState.value.tabs.find { it.id == tabId }?.isPrivate ?: false

        if (isActive) suspendCurrentWebView(clearPrivate = isPrivate)

        viewModel.closeTab(tabId)

        if (viewModel.uiState.value.tabs.isEmpty()) {
            viewModel.createTab()
        }
        if (isActive) mountWebView()
    }

    private fun suspendCurrentWebView(clearPrivate: Boolean = false) {
        currentWebView?.let { wv ->
            val bundle = Bundle()
            wv.saveState(bundle)
            viewModel.onWebViewSuspending(viewModel.uiState.value.activeTabId, bundle)
            if (clearPrivate) viewModel.clearPrivateTabData()
            binding.webViewContainer.removeAllViews()
            wv.destroy()
        }
        currentWebView = null
        currentClient = null
    }

    // ── WebView mount ────────────────────────────────────────────────────────

    private fun mountWebView() {
        val settings = viewModel.settingsState.value
        val activeTab = viewModel.uiState.value.run {
            tabs.find { it.id == activeTabId }
        } ?: return

        val client = HibariWebViewClient(
            onPageStarted = { url, _ -> viewModel.onPageStarted(url) },
            onPageFinished = { url -> viewModel.onPageFinished(url) },
            onNavigationStateChanged = { b, f -> viewModel.onNavigationStateChanged(b, f) },
            httpsOnly = settings.httpsOnly,
        )
        currentClient = client

        val wv = WebViewFactory.create(this, client, settings.blockThirdPartyCookies)
        wv.webChromeClient = buildChromeClient()
        wv.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
            showDownloadDialog(url, mimeType ?: "*/*", name)
        }

        currentWebView = wv
        binding.webViewContainer.addView(
            wv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        val saved = viewModel.consumeSavedState(activeTab.id)
        if (saved != null) wv.restoreState(saved)
        else if (activeTab.url.isNotBlank()) wv.loadUrl(activeTab.url)
    }

    private fun buildChromeClient() = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) =
            viewModel.onProgressChanged(newProgress)

        override fun onReceivedTitle(view: WebView?, title: String?) =
            viewModel.onTitleReceived(title)

        override fun onPermissionRequest(request: PermissionRequest) = request.deny()

        override fun onGeolocationPermissionsShowPrompt(
            origin: String?,
            callback: GeolocationPermissions.Callback?,
        ) = callback?.invoke(origin, false, false) ?: Unit
    }

    // ── Download dialog ──────────────────────────────────────────────────────

    private fun showDownloadDialog(url: String, mimeType: String, fileName: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.download_title)
            .setMessage(getString(R.string.download_confirm, fileName))
            .setPositiveButton(R.string.download_ok) { _, _ -> startDownload(url, mimeType, fileName) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startDownload(url: String, mimeType: String, fileName: String) {
        val req = DownloadManager.Request(android.net.Uri.parse(url)).apply {
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, fileName)
            setMimeType(mimeType)
            addRequestHeader("User-Agent", android.webkit.WebSettings.getDefaultUserAgent(this@MainActivity))
        }
        (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
    }

    // ── Keyboard helper ──────────────────────────────────────────────────────

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(binding.root.windowToken, 0)
    }
}

// ── Suggestion adapter ────────────────────────────────────────────────────────

private class SuggestionAdapter(
    private val onClick: (HistoryEntity) -> Unit,
) : ListAdapter<HistoryEntity, SuggestionAdapter.VH>(DIFF) {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.tvTitle)
        val url: TextView = v.findViewById(R.id.tvUrl)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_suggestion, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.title.text = item.title
        holder.url.text = item.url
        holder.itemView.setOnClickListener { onClick(item) }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<HistoryEntity>() {
            override fun areItemsTheSame(a: HistoryEntity, b: HistoryEntity) = a.id == b.id
            override fun areContentsTheSame(a: HistoryEntity, b: HistoryEntity) = a == b
        }
    }
}
