/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mozilla.components.feature.addons.Addon
import mozilla.components.feature.addons.AddonManagerException
import mozilla.components.feature.addons.ui.AddonsManagerAdapter
import mozilla.components.feature.addons.ui.AddonsManagerAdapterDelegate
import mozilla.components.support.base.feature.ViewBoundFeatureWrapper
import org.mozilla.reference.browser.R
import org.mozilla.reference.browser.addons.AddonDetailsActivity
import org.mozilla.reference.browser.addons.InstalledAddonDetailsActivity
import org.mozilla.reference.browser.addons.WebExtensionPromptFeature
import org.mozilla.reference.browser.ext.components
import org.mozilla.reference.browser.ext.requireComponents
import android.content.Intent
import mozilla.components.feature.addons.R as addonsR

/**
 * Settings fragment that shows installed add-ons and a FAB to browse more
 * extensions on addons.mozilla.org (direct install is supported natively).
 */
class AddonsSettingsFragment :
    Fragment(),
    AddonsManagerAdapterDelegate {

    private val webExtensionPromptFeature = ViewBoundFeatureWrapper<WebExtensionPromptFeature>()
    private lateinit var recyclerView: RecyclerView
    private val scope = CoroutineScope(Dispatchers.IO)
    private var adapter: AddonsManagerAdapter? = null
    private var isInstallationInProgress = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_addons_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.addons_settings_list)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        webExtensionPromptFeature.set(
            feature = WebExtensionPromptFeature(
                store = requireContext().components.core.store,
                context = requireContext(),
                fragmentManager = parentFragmentManager,
            ),
            owner = this,
            view = view,
        )

        // FAB → open Firefox Add-ons page in the browser (direct install supported)
        view.findViewById<FloatingActionButton>(R.id.fab_browse_addons).setOnClickListener {
            requireComponents.useCases.tabsUseCases.addTab(
                url = "https://addons.mozilla.org/en-US/firefox/extensions/",
                selectTab = true,
            )
            // Go back to the browser
            activity?.finish()
        }

        loadAddons()
    }

    override fun onResume() {
        super.onResume()
        (activity as? SettingsFragment.ActionBarUpdater)?.updateTitle(R.string.preferences_addons)
        loadAddons()
    }

    private fun loadAddons() {
        scope.launch {
            try {
                val addons = requireContext().components.core.addonManager.getAddons()
                launch(Dispatchers.Main) {
                    if (adapter == null) {
                        adapter = AddonsManagerAdapter(
                            this@AddonsSettingsFragment,
                            addons,
                            store = requireContext().components.core.store,
                        )
                        recyclerView.adapter = adapter
                    } else {
                        adapter?.updateAddons(addons)
                    }
                }
            } catch (e: AddonManagerException) {
                launch(Dispatchers.Main) {
                    Toast.makeText(
                        activity,
                        addonsR.string.mozac_feature_addons_failed_to_query_extensions,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    override fun onAddonItemClicked(addon: Addon) {
        if (addon.isInstalled()) {
            val intent = Intent(context, InstalledAddonDetailsActivity::class.java)
            intent.putExtra("add_on", addon)
            startActivity(intent)
        } else {
            val intent = Intent(context, AddonDetailsActivity::class.java)
            intent.putExtra("add_on", addon)
            startActivity(intent)
        }
    }

    override fun onInstallAddonButtonClicked(addon: Addon) {
        if (isInstallationInProgress) return
        isInstallationInProgress = true
        requireContext().components.core.addonManager.installAddon(
            url = addon.downloadUrl,
            onSuccess = {
                isInstallationInProgress = false
                loadAddons()
            },
            onError = { _ ->
                isInstallationInProgress = false
            },
        )
    }
}

