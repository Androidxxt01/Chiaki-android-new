// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.settings

import android.content.res.Resources
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import com.metallic.chiaki.R
import com.metallic.chiaki.touchcontrols.DefaultTouchControlsFragment

class SettingsTouchControlsFragment : Fragment(), TitleFragment
{
	private val controlsContainerId = R.id.settings_touch_controls_container

	override fun onCreateView(inflater: android.view.LayoutInflater, container: android.view.ViewGroup?, savedInstanceState: Bundle?): View =
		FrameLayout(requireContext()).apply { id = controlsContainerId }

	override fun onViewCreated(view: View, savedInstanceState: Bundle?)
	{
		super.onViewCreated(view, savedInstanceState)
		val controls = DefaultTouchControlsFragment()
		childFragmentManager.beginTransaction()
			.replace(controlsContainerId, controls)
			.commitNow()
		controls.view?.post { controls.openControlLayoutEditor() }
	}

	override fun getTitle(resources: Resources): String = resources.getString(R.string.preferences_touch_controls_title)
}
