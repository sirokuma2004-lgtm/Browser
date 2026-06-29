package com.example.hibari.ui

import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.example.hibari.R
import com.example.hibari.bookmarks.BookmarkRepository
import com.example.hibari.bookmarks.NetscapeHtmlImporter
import com.example.hibari.data.AppDatabase
import com.example.hibari.data.BrowserPreferences
import com.example.hibari.databinding.ActivitySettingsBinding
import com.example.hibari.history.HistoryRepository
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.settingsToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settingsContainer, HibariPreferenceFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

class HibariPreferenceFragment : PreferenceFragmentCompat() {

    private lateinit var bookmarkRepo: BookmarkRepository
    private lateinit var historyRepo: HistoryRepository

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? -> uri?.let { handleImport(it) } }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val ctx = requireContext()
        val db = AppDatabase.getInstance(ctx)
        bookmarkRepo = BookmarkRepository(db.bookmarkDao())
        historyRepo = HistoryRepository(db.historyDao())

        preferenceManager.sharedPreferencesName = BrowserPreferences.PREFS_NAME
        setPreferencesFromResource(R.xml.preferences, rootKey)

        // Show current DoH URL as summary
        findPreference<ListPreference>(BrowserPreferences.KEY_DOH_PROVIDER_URL)
            ?.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()

        findPreference<Preference>("import_bookmarks")?.setOnPreferenceClickListener {
            importLauncher.launch("text/html")
            true
        }

        findPreference<Preference>("clear_browsing_data")?.setOnPreferenceClickListener {
            confirmClearData()
            true
        }
    }

    private fun handleImport(uri: Uri) {
        lifecycleScope.launch {
            val message = try {
                val stream = requireContext().contentResolver.openInputStream(uri)
                    ?: return@launch showResult("ファイルを開けませんでした")
                val result = NetscapeHtmlImporter().import(stream, bookmarkRepo)
                "インポート完了: ${result.imported} 件（スキップ: ${result.skipped} 件）"
            } catch (e: Exception) {
                "インポート失敗: ${e.message}"
            }
            showResult(message)
        }
    }

    private fun confirmClearData() {
        AlertDialog.Builder(requireContext())
            .setTitle("閲覧データを削除")
            .setMessage("履歴・Cookie・キャッシュをすべて削除しますか？")
            .setPositiveButton("削除") { _, _ ->
                lifecycleScope.launch { historyRepo.deleteAll() }
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun showResult(message: String) {
        if (!isAdded) return
        AlertDialog.Builder(requireContext())
            .setTitle("インポート結果")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }
}
