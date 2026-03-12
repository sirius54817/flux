/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser

import android.app.Application
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import mozilla.components.browser.state.action.SearchAction
import mozilla.components.browser.state.action.SystemAction
import mozilla.components.browser.state.search.SearchEngine
import mozilla.components.concept.engine.webextension.isUnsupported
import mozilla.components.concept.push.PushProcessor
import mozilla.components.feature.addons.update.GlobalAddonDependencyProvider
import mozilla.components.lib.state.ext.flow
import mozilla.components.support.AppServicesInitializer
import mozilla.components.support.base.log.Log
import mozilla.components.support.base.log.logger.Logger
import mozilla.components.support.base.log.sink.AndroidLogSink
import mozilla.components.support.ktx.android.content.isMainProcess
import mozilla.components.support.ktx.android.content.runOnlyInMainProcess
import mozilla.components.support.rusthttp.RustHttpConfig
import mozilla.components.support.webextensions.WebExtensionSupport
import org.mozilla.reference.browser.push.PushFxaIntegration
import org.mozilla.reference.browser.push.WebPushEngineIntegration
import java.util.concurrent.TimeUnit
import mozilla.components.support.AppServicesInitializer.Config as AppServicesConfig

open class BrowserApplication : Application() {
    val components by lazy { Components(this) }

    override fun onCreate() {
        super.onCreate()

        setupCrashReporting(this)

        AppServicesInitializer.init(
            AppServicesConfig(components.analytics.crashReporter),
        )
        RustHttpConfig.setClient(lazy { components.core.client })

        Log.addSink(AndroidLogSink())

        if (!isMainProcess()) {
            // If this is not the main process then do not continue with the initialization here. Everything that
            // follows only needs to be done in our app's main process and should not be done in other processes like
            // a GeckoView child process or the crash handling process. Most importantly we never want to end up in a
            // situation where we create a GeckoRuntime from the Gecko child process (
            return
        }

        components.core.engine.warmUp()

        restoreBrowserState()
        seedPreinstalledEngines()
        setupDefaultSearchEngine()

        GlobalAddonDependencyProvider.initialize(
            components.core.addonManager,
            components.core.addonUpdater,
        )
        WebExtensionSupport.initialize(
            runtime = components.core.engine,
            store = components.core.store,
            onNewTabOverride = { _, engineSession, url ->
                val tabId = components.useCases.tabsUseCases.addTab(
                    url = url,
                    selectTab = true,
                    engineSession = engineSession,
                )
                tabId
            },
            onCloseTabOverride = { _, sessionId ->
                components.useCases.tabsUseCases.removeTab(sessionId)
            },
            onSelectTabOverride = { _, sessionId ->
                components.useCases.tabsUseCases.selectTab(sessionId)
            },
            onExtensionsLoaded = { extensions ->
                components.core.addonUpdater.registerForFutureUpdates(extensions)

                val checker = components.core.supportedAddonsChecker
                val hasUnsupportedAddons = extensions.any { it.isUnsupported() }
                if (hasUnsupportedAddons) {
                    checker.registerForChecks()
                } else {
                    // As checks are a persistent subscriptions, we have to make sure
                    // we remove any previous subscriptions.
                    checker.unregisterForChecks()
                }
            },
            onUpdatePermissionRequest = components.core.addonUpdater::onUpdatePermissionRequest,
        )

        components.push.feature?.let {
            Logger.info("AutoPushFeature is configured, initializing it...")

            PushProcessor.install(it)

            // WebPush integration to observe and deliver push messages to engine.
            WebPushEngineIntegration(components.core.engine, it).start()

            // Perform a one-time initialization of the account manager if a message is received.
            PushFxaIntegration(it, lazy { components.backgroundServices.accountManager }).launch()

            // Initialize the push feature and service.
            it.initialize()
        }
        @OptIn(DelicateCoroutinesApi::class)
        GlobalScope.launch(Dispatchers.IO) {
            components.core.fileUploadsDirCleaner.cleanUploadsDirectory()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        runOnlyInMainProcess {
            components.core.store.dispatch(SystemAction.LowMemoryAction(level))
            components.core.icons.onTrimMemory(level)
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun restoreBrowserState() =
        GlobalScope.launch(Dispatchers.Main) {
        val store = components.core.store
        val sessionStorage = components.core.sessionStorage

        components.useCases.tabsUseCases.restore(sessionStorage)

        // Now that we have restored our previous state (if there's one) let's setup auto saving the state while
        // the app is used.
        sessionStorage
            .autoSave(store)
            .periodicallyInForeground(interval = 30, unit = TimeUnit.SECONDS)
            .whenGoingToBackground()
            .whenSessionsChange()
    }

    companion object {
        const val NON_FATAL_CRASH_BROADCAST = "org.mozilla.reference.browser"
        private const val DEFAULT_SEARCH_ENGINE_NAME = "Yandex"
    }

    /**
     * Seeds the 13 preinstalled search engines into the store on first run.
     * Guarded by a SharedPreferences flag so it only runs once per version.
     */
    @OptIn(DelicateCoroutinesApi::class)
    private fun seedPreinstalledEngines() = GlobalScope.launch(Dispatchers.Main) {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this@BrowserApplication)
        val seededKey = "pref_engines_seeded_v2"   // bumped → forces re-seed on existing installs
        if (prefs.getBoolean(seededKey, false)) return@launch

        val store = components.core.store
        val icon = androidx.core.content.ContextCompat.getDrawable(
            this@BrowserApplication,
            mozilla.components.ui.icons.R.drawable.mozac_ic_search_24,
        )!!.let { d ->
            androidx.core.graphics.drawable.DrawableCompat.wrap(d).also {
                it.setBounds(0, 0, 64, 64)
            }
        }
        val bitmap = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888).also { bmp ->
            icon.draw(android.graphics.Canvas(bmp))
        }

        org.mozilla.reference.browser.settings.SearchEngineSettingsFragment.PREINSTALLED_ENGINES.forEach { spec ->
            store.dispatch(
                SearchAction.UpdateCustomSearchEngineAction(
                    searchEngine = SearchEngine(
                        id = "preinstalled-${spec.name.lowercase().replace(" ", "-")}",
                        name = spec.name,
                        icon = bitmap,
                        type = SearchEngine.Type.CUSTOM,
                        resultUrls = listOf(spec.url),
                    ),
                ),
            )
        }

        prefs.edit().putBoolean(seededKey, true).apply()
        Logger.info("BrowserApplication: preinstalled search engines seeded (v2)")
    }

    /**
     * Observes the [BrowserStore] and selects a default search engine once the
     * [SearchMiddleware] has finished loading the bundled search engines.
     * Without this, [SearchUseCases.defaultSearch] and [SearchSuggestionProvider] both
     * warn "No default search engine" and no searches are performed.
     */
    @OptIn(DelicateCoroutinesApi::class)
    private fun setupDefaultSearchEngine() = GlobalScope.launch(Dispatchers.Main) {
        val store = components.core.store
        store
            .flow()
            .map { state -> state.search }
            .distinctUntilChanged()
            .filter { searchState ->
                // Wait until both region engines AND our custom/preinstalled engines are loaded
                val allEngines = searchState.regionSearchEngines + searchState.customSearchEngines
                allEngines.isNotEmpty() && searchState.userSelectedSearchEngineId == null
            }
            .collect { searchState ->
                // Respect user's saved preference, fall back to Yandex, then first available
                val allEngines = searchState.regionSearchEngines + searchState.customSearchEngines
                val savedName = androidx.preference.PreferenceManager
                    .getDefaultSharedPreferences(this@BrowserApplication)
                    .getString(getString(R.string.pref_key_search_engine), null)

                val preferredEngine = if (savedName != null) {
                    allEngines.firstOrNull { it.name.equals(savedName, ignoreCase = true) }
                } else {
                    null
                } ?: allEngines.firstOrNull { engine ->
                    engine.name.equals(DEFAULT_SEARCH_ENGINE_NAME, ignoreCase = true)
                } ?: allEngines.firstOrNull() ?: return@collect

                store.dispatch(
                    SearchAction.SelectSearchEngineAction(
                        searchEngineId = preferredEngine.id,
                        searchEngineName = preferredEngine.name,
                    ),
                )
                Logger.info("Default search engine set to: ${preferredEngine.name}")
            }
    }
}

private fun setupCrashReporting(application: BrowserApplication) {
    application
        .components
        .analytics
        .crashReporter
        .install(application)
}
