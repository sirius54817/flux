/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.core.graphics.drawable.toBitmap
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import mozilla.components.browser.state.action.SearchAction
import mozilla.components.browser.state.search.SearchEngine
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.lib.state.ext.flow
import mozilla.components.support.base.log.logger.Logger
import org.mozilla.reference.browser.R
import org.mozilla.reference.browser.ext.requireComponents

/**
 * Fragment that lets the user choose or add a default search engine.
 * Shown from SettingsFragment when tapping "Search engine".
 */
class SearchEngineSettingsFragment : Fragment() {

    private lateinit var store: BrowserStore
    private lateinit var adapter: SearchEngineAdapter

    // ── Preinstalled engine definitions ──────────────────────────────────────

    data class EngineSpec(val name: String, val url: String)

    companion object {
        val PREINSTALLED_ENGINES = listOf(
            EngineSpec("Google",       "https://www.google.com/search?q=%s"),
            EngineSpec("DuckDuckGo",   "https://duckduckgo.com/?q=%s"),
            EngineSpec("Bing",         "https://www.bing.com/search?q=%s"),
            EngineSpec("Yahoo",        "https://search.yahoo.com/search?p=%s"),
            EngineSpec("Yandex",       "https://yandex.com/search/?text=%s"),
            EngineSpec("Ecosia",       "https://www.ecosia.org/search?q=%s"),
            EngineSpec("Brave Search", "https://search.brave.com/search?q=%s"),
            EngineSpec("Startpage",    "https://www.startpage.com/search?q=%s"),
            EngineSpec("Qwant",        "https://www.qwant.com/?q=%s"),
            EngineSpec("Swisscows",    "https://swisscows.com/web?query=%s"),
            EngineSpec("Mojeek",       "https://www.mojeek.com/search?q=%s"),
            EngineSpec("Ask",          "https://www.ask.com/web?q=%s"),
            EngineSpec("AOL Search",   "https://search.aol.com/aol/search?q=%s"),
        )
        private const val PREF_ENGINES_SEEDED = "pref_engines_seeded_v2"
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val root = inflater.inflate(R.layout.fragment_search_engine_settings, container, false)
        store = requireComponents.core.store

        val recycler = root.findViewById<RecyclerView>(R.id.search_engine_list)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.addItemDecoration(
            DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL),
        )

        adapter = SearchEngineAdapter(
            emptyList(),
            store.state.search.userSelectedSearchEngineId
                ?: store.state.search.regionDefaultSearchEngineId,
        ) { engine -> selectEngine(engine) }
        recycler.adapter = adapter

        // FAB to add custom engine
        root.findViewById<FloatingActionButton>(R.id.fab_add_search_engine).setOnClickListener {
            showAddCustomEngineDialog()
        }

        // Seed preinstalled engines once, then optionally prompt for default
        seedPreinstalledEngines()

        // Observe search state changes
        viewLifecycleOwner.lifecycleScope.launch {
            store.flow()
                .map { it.search }
                .distinctUntilChanged()
                .collectLatest { searchState ->
                    val engines = buildEngineList(searchState)
                    val selectedId = searchState.userSelectedSearchEngineId
                        ?: searchState.regionDefaultSearchEngineId
                    adapter.update(engines, selectedId)
                }
        }

        return root
    }

    override fun onResume() {
        super.onResume()
        (activity as? SettingsFragment.ActionBarUpdater)
            ?.updateTitle(R.string.search_engine_settings)

        // If there is still no default, prompt the user to pick one
        val search = store.state.search
        val hasDefault = search.userSelectedSearchEngineId != null ||
            search.regionDefaultSearchEngineId != null
        if (!hasDefault && buildEngineList(search).isNotEmpty()) {
            showPickDefaultDialog()
        }
    }

    // ── Engine seeding ────────────────────────────────────────────────────────

    /**
     * Inserts the [PREINSTALLED_ENGINES] as custom engines exactly once
     * (guarded by a SharedPreferences flag so they are not duplicated on re-opens).
     */
    private fun seedPreinstalledEngines() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        if (prefs.getBoolean(PREF_ENGINES_SEEDED, false)) return

        val searchIcon = requireContext()
            .getDrawable(mozilla.components.ui.icons.R.drawable.mozac_ic_search_24)!!
            .toBitmap()

        PREINSTALLED_ENGINES.forEach { spec ->
            store.dispatch(
                SearchAction.UpdateCustomSearchEngineAction(
                    searchEngine = SearchEngine(
                        id = "preinstalled-${spec.name.lowercase().replace(" ", "-")}",
                        name = spec.name,
                        icon = searchIcon,
                        type = SearchEngine.Type.CUSTOM,
                        resultUrls = listOf(spec.url),
                    ),
                ),
            )
        }

        prefs.edit { putBoolean(PREF_ENGINES_SEEDED, true) }
        Logger.info("Preinstalled search engines seeded (${PREINSTALLED_ENGINES.size} engines)")
    }

    // ── "Pick a default" dialog ───────────────────────────────────────────────

    private fun showPickDefaultDialog() {
        val engines = buildEngineList(store.state.search)
        if (engines.isEmpty()) return

        val names = engines.map { it.name }.toTypedArray()
        // Pre-select Yandex if available, otherwise first
        var checkedIndex = engines.indexOfFirst { it.name.equals("Yandex", ignoreCase = true) }
            .takeIf { it >= 0 } ?: 0

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.search_engine_dialog_title)
            .setSingleChoiceItems(names, checkedIndex) { _, which ->
                checkedIndex = which
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                selectEngine(engines[checkedIndex])
            }
            .setCancelable(false)
            .show()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildEngineList(
        searchState: mozilla.components.browser.state.state.SearchState,
    ): List<SearchEngine> =
        (searchState.regionSearchEngines + searchState.customSearchEngines)
            .distinctBy { it.id }

    private fun selectEngine(engine: SearchEngine) {
        PreferenceManager.getDefaultSharedPreferences(requireContext()).edit {
            putString(getString(R.string.pref_key_search_engine), engine.name)
        }
        store.dispatch(
            SearchAction.SelectSearchEngineAction(
                searchEngineId = engine.id,
                searchEngineName = engine.name,
            ),
        )
        Logger.info("User selected search engine: ${engine.name}")
        Toast.makeText(requireContext(), "Default: ${engine.name}", Toast.LENGTH_SHORT).show()
        adapter.update(adapter.currentEngines, engine.id)
    }

    private fun showAddCustomEngineDialog() {
        val context = requireContext()
        val dialogView = LayoutInflater.from(context)
            .inflate(R.layout.dialog_add_search_engine, null)
        val nameInput = dialogView.findViewById<EditText>(R.id.engine_name_input)
        val urlInput = dialogView.findViewById<EditText>(R.id.engine_url_input)

        AlertDialog.Builder(context)
            .setTitle(R.string.search_engine_add_custom_title)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = nameInput.text.toString().trim()
                val url = urlInput.text.toString().trim()
                if (name.isBlank() || !url.contains("%s")) {
                    Toast.makeText(
                        context,
                        getString(R.string.search_engine_add_invalid_url),
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    addCustomEngine(name, url)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun addCustomEngine(name: String, searchUrlTemplate: String) {
        store.dispatch(
            SearchAction.UpdateCustomSearchEngineAction(
                searchEngine = SearchEngine(
                    id = "custom-${name.lowercase().replace(" ", "-")}",
                    name = name,
                    icon = requireContext()
                        .getDrawable(mozilla.components.ui.icons.R.drawable.mozac_ic_search_24)!!
                        .toBitmap(),
                    type = SearchEngine.Type.CUSTOM,
                    resultUrls = listOf(searchUrlTemplate),
                ),
            ),
        )
        Toast.makeText(
            requireContext(),
            getString(R.string.search_engine_added),
            Toast.LENGTH_SHORT,
        ).show()
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    inner class SearchEngineAdapter(
        engines: List<SearchEngine>,
        private var selectedId: String?,
        private val onSelect: (SearchEngine) -> Unit,
    ) : RecyclerView.Adapter<SearchEngineAdapter.ViewHolder>() {

        var currentEngines: List<SearchEngine> = engines
            private set

        fun update(engines: List<SearchEngine>, selectedId: String?) {
            this.currentEngines = engines
            this.selectedId = selectedId
            notifyDataSetChanged()
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.engine_icon)
            val name: TextView = view.findViewById(R.id.engine_name)
            val radio: RadioButton = view.findViewById(R.id.engine_radio)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_search_engine, parent, false)
            return ViewHolder(v)
        }

        override fun getItemCount() = currentEngines.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val engine = currentEngines[position]
            holder.name.text = engine.name
            holder.radio.isChecked = engine.id == selectedId
            try {
                holder.icon.setImageBitmap(engine.icon)
            } catch (_: Exception) {
                holder.icon.setImageResource(
                    mozilla.components.ui.icons.R.drawable.mozac_ic_search_24,
                )
            }
            holder.itemView.setOnClickListener { onSelect(engine) }
            holder.radio.setOnClickListener { onSelect(engine) }
        }
    }
}

