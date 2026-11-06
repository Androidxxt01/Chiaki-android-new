// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.settings

import android.content.Intent
import android.content.res.Resources
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.metallic.chiaki.R

class SettingsTouchControlsFragment : Fragment(), TitleFragment
{
	override fun onCreateView(inflater: android.view.LayoutInflater, container: android.view.ViewGroup?, savedInstanceState: Bundle?): View =
		View(requireContext()).apply {
			post { startActivity(Intent(requireContext(), TouchControlsEditorActivity::class.java)) }
		}

	override fun getTitle(resources: Resources): String = resources.getString(R.string.preferences_touch_controls_title)
}
