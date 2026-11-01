package com.lencodigitexer.printables

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lencodigitexer.printables.api.PrintablesApiClient
import com.lencodigitexer.printables.data.FavoritesManager
import com.lencodigitexer.printables.data.PrintableModel
import com.lencodigitexer.printables.data.SearchOrdering
import com.lencodigitexer.printables.databinding.ActivityMainBinding
import com.lencodigitexer.printables.ui.ModelAdapter
import com.lencodigitexer.printables.ui.ModelDetailBottomSheet
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val apiClient = PrintablesApiClient()
    private lateinit var favoritesManager: FavoritesManager
    private lateinit var adapter: ModelAdapter

    private var currentQuery: String = ""
    private var currentOrdering: SearchOrdering = SearchOrdering.BEST_MATCH
    private var isViewingSaved: Boolean = false
    private var searchJob: Job? = null
    private var currentModels: List<PrintableModel> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        favoritesManager = FavoritesManager(this)

        setupRecyclerView()
        setupSearch()
        setupTabs()
        setupCategories()
        setupSort()
        setupExport()

        binding.swipeRefresh.setOnRefreshListener {
            loadData(showLoading = false)
        }

        binding.btnResetSearch.setOnClickListener {
            binding.etSearch.setText("")
            binding.chipAll.isChecked = true
            currentQuery = ""
            loadData()
        }

        // Initial load
        loadData()
    }

    private fun setupRecyclerView() {
        val spanCount = resources.getInteger(R.integer.grid_span_count)
        val layoutManager = GridLayoutManager(this, spanCount)
        binding.rvModels.layoutManager = layoutManager

        adapter = ModelAdapter(
            scope = lifecycleScope,
            onModelClick = { model ->
                showModelDetails(model)
            },
            onBookmarkToggle = { model ->
                val newState = favoritesManager.toggleBookmark(model)
                val msg = if (newState) "Saved ${model.name}" else "Removed from Saved"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                if (isViewingSaved) {
                    loadSavedModels()
                }
            }
        )
        binding.rvModels.adapter = adapter
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val text = s?.toString()?.trim() ?: ""
                binding.btnClearSearch.visibility = if (text.isNotEmpty()) View.VISIBLE else View.GONE

                if (!isViewingSaved) {
                    searchJob?.cancel()
                    searchJob = lifecycleScope.launch {
                        delay(350)
                        currentQuery = text
                        loadData()
                    }
                } else {
                    filterSavedModels(text)
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard()
                searchJob?.cancel()
                currentQuery = binding.etSearch.text?.toString()?.trim() ?: ""
                loadData()
                true
            } else {
                false
            }
        }

        binding.btnClearSearch.setOnClickListener {
            binding.etSearch.setText("")
            currentQuery = ""
            loadData()
        }
    }

    private fun setupTabs() {
        binding.toggleTabs.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.tabExplore -> {
                    isViewingSaved = false
                    binding.btnSort.visibility = View.VISIBLE
                    binding.chipGroupCategories.visibility = View.VISIBLE
                    loadData()
                }
                R.id.tabSaved -> {
                    isViewingSaved = true
                    binding.btnSort.visibility = View.GONE
                    binding.chipGroupCategories.visibility = View.GONE
                    loadSavedModels()
                }
            }
        }
    }

    private fun setupCategories() {
        binding.chipGroupCategories.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty() || isViewingSaved) return@setOnCheckedStateChangeListener
            val checkedId = checkedIds.first()

            val query = when (checkedId) {
                R.id.chipBenchy -> "benchy"
                R.id.chipGridfinity -> "gridfinity"
                R.id.chipPrusa -> "prusa"
                R.id.chipTools -> "tool"
                R.id.chipArticulated -> "articulated"
                R.id.chipGadgets -> "desk"
                else -> ""
            }

            currentQuery = query
            binding.etSearch.setText(query)
            if (query.isNotEmpty()) {
                binding.etSearch.setSelection(query.length)
            }
            loadData()
        }
    }

    private fun setupSort() {
        binding.btnSort.setOnClickListener {
            val orderings = SearchOrdering.entries.toTypedArray()
            val labels = orderings.map { it.displayName }.toTypedArray()
            val currentIdx = orderings.indexOf(currentOrdering)

            MaterialAlertDialogBuilder(this)
                .setTitle("Sort Models")
                .setSingleChoiceItems(labels, currentIdx) { dialog, which ->
                    currentOrdering = orderings[which]
                    binding.btnSort.text = currentOrdering.displayName
                    dialog.dismiss()
                    loadData()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun setupExport() {
        binding.btnExportAllJson.setOnClickListener {
            if (currentModels.isEmpty()) {
                Toast.makeText(this, "No models to export", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val rootJson = JSONObject()
            for (m in currentModels) {
                rootJson.put(m.id, m.toPrintablesApiJson())
            }
            val jsonString = rootJson.toString(2)

            val options = arrayOf("Copy to Clipboard", "Share via App")
            MaterialAlertDialogBuilder(this)
                .setTitle("Export Printables JSON (${currentModels.size} models)")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> {
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("Printables Models JSON", jsonString)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(this, getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
                        }
                        1 -> {
                            val sendIntent = Intent().apply {
                                action = Intent.ACTION_SEND
                                putExtra(Intent.EXTRA_TEXT, jsonString)
                                type = "application/json"
                            }
                            startActivity(Intent.createChooser(sendIntent, "Export Models JSON"))
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun loadData(showLoading: Boolean = true) {
        if (isViewingSaved) {
            loadSavedModels()
            return
        }

        if (showLoading) {
            binding.progressBar.visibility = View.VISIBLE
            binding.layoutEmptyView.visibility = View.GONE
        }

        lifecycleScope.launch {
            try {
                val results = apiClient.searchModels(
                    query = currentQuery,
                    limit = 24,
                    ordering = currentOrdering.apiValue
                )

                // Sync with local bookmark states
                for (m in results) {
                    m.isBookmarked = favoritesManager.isBookmarked(m.id)
                }

                currentModels = results
                adapter.submitList(results)

                binding.progressBar.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false

                if (results.isEmpty()) {
                    binding.layoutEmptyView.visibility = View.VISIBLE
                    binding.tvEmptyTitle.text = getString(R.string.no_models_found)
                    binding.tvEmptySubtitle.text = "No models match '$currentQuery'. Try searching for Benchy or Gridfinity."
                } else {
                    binding.layoutEmptyView.visibility = View.GONE
                }
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
                Toast.makeText(this@MainActivity, "Network error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadSavedModels() {
        val saved = favoritesManager.getBookmarkedModels()
        currentModels = saved
        adapter.submitList(saved)
        binding.progressBar.visibility = View.GONE
        binding.swipeRefresh.isRefreshing = false

        if (saved.isEmpty()) {
            binding.layoutEmptyView.visibility = View.VISIBLE
            binding.tvEmptyTitle.text = "No Saved Models"
            binding.tvEmptySubtitle.text = "Tap the bookmark icon on any 3D model to save it here for quick access."
        } else {
            binding.layoutEmptyView.visibility = View.GONE
        }
    }

    private fun filterSavedModels(filterText: String) {
        val allSaved = favoritesManager.getBookmarkedModels()
        val filtered = if (filterText.isBlank()) {
            allSaved
        } else {
            allSaved.filter {
                it.name.contains(filterText, ignoreCase = true) ||
                        (it.author?.contains(filterText, ignoreCase = true) == true)
            }
        }
        currentModels = filtered
        adapter.submitList(filtered)
        binding.layoutEmptyView.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showModelDetails(model: PrintableModel) {
        val sheet = ModelDetailBottomSheet.newInstance(model)
        sheet.onBookmarkChanged = { updatedModel ->
            adapter.notifyDataSetChanged()
            if (isViewingSaved) {
                loadSavedModels()
            }
        }
        sheet.show(supportFragmentManager, "ModelDetailBottomSheet")
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
    }
}