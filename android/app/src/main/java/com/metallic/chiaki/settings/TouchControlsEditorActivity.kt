// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.settings

import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.metallic.chiaki.touchcontrols.DefaultTouchControlsFragment

/** Hosts the controls editor on a full-size, landscape canvas like the stream overlay. */
class TouchControlsEditorActivity : AppCompatActivity()
{
	private fun applyImmersiveMode() {
		WindowInsetsControllerCompat(window, window.decorView).apply {
			systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
			hide(WindowInsetsCompat.Type.systemBars())
		}
	}

	override fun onResume() {
		super.onResume()
		applyImmersiveMode()
	}

	override fun onWindowFocusChanged(hasFocus: Boolean) {
		super.onWindowFocusChanged(hasFocus)
		if (hasFocus) applyImmersiveMode()
	}

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		WindowCompat.setDecorFitsSystemWindows(window, false)
		applyImmersiveMode()

		val container = FrameLayout(this).apply { id = View.generateViewId() }
		setContentView(container, FrameLayout.LayoutParams(
			FrameLayout.LayoutParams.MATCH_PARENT,
			FrameLayout.LayoutParams.MATCH_PARENT
		))
		if (savedInstanceState == null) {
			val controls = DefaultTouchControlsFragment()
			supportFragmentManager.beginTransaction().replace(container.id, controls).commitNow()
			container.post { controls.enterEditMode() }
		}
	}
}
