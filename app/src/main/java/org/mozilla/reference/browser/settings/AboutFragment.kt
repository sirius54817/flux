/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.PackageInfoCompat
import androidx.fragment.app.Fragment
import mozilla.components.Build
import org.mozilla.geckoview.BuildConfig.MOZ_APP_BUILDID
import org.mozilla.geckoview.BuildConfig.MOZ_APP_VERSION
import org.mozilla.reference.browser.R

class AboutFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? = inflater.inflate(R.layout.fragment_about, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (activity as AppCompatActivity).title = getString(R.string.preferences_about_page)

        // Version string
        val versionName = try {
            val packageInfo = requireContext().packageManager
                .getPackageInfo(requireContext().packageName, 0)
            val buildCode = PackageInfoCompat.getLongVersionCode(packageInfo)
            "v${packageInfo.versionName} (build $buildCode)\nGeckoView $MOZ_APP_VERSION-$MOZ_APP_BUILDID"
        } catch (e: PackageManager.NameNotFoundException) {
            "Unknown version"
        }

        val acVersion = "AC: ${Build.VERSION}  •  ${Build.GIT_HASH}\nApp-Services: ${Build.APPLICATION_SERVICES_VERSION}"

        view.findViewById<TextView>(R.id.about_app_name).text = getString(R.string.app_name)
        view.findViewById<TextView>(R.id.about_tagline).text = getString(R.string.about_tagline)
        view.findViewById<TextView>(R.id.about_developer).text = getString(R.string.about_developer)
        view.findViewById<TextView>(R.id.about_version).text = versionName
        view.findViewById<TextView>(R.id.about_ac_version).text = acVersion

        // Tap version to copy
        view.findViewById<TextView>(R.id.about_version).setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("version", "$versionName\n$acVersion"))
            Toast.makeText(requireContext(), getString(R.string.toast_copied), Toast.LENGTH_SHORT).show()
        }
    }
}
