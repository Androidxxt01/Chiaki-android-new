// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.session

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.annotation.StringRes
import androidx.preference.PreferenceManager
import com.metallic.chiaki.R
import org.json.JSONObject

enum class PhysicalInputKind
{
	BUTTON,
	TRIGGER,
	STICK_AXIS
}

data class PhysicalMappingTarget(val id: String, @StringRes val title: Int, val kind: PhysicalInputKind)

data class PhysicalInputBinding(val keyCode: Int? = null, val axis: Int? = null)

object PhysicalControllerMappings
{
	val targets = listOf(
		PhysicalMappingTarget("cross", R.string.physical_mapping_cross, PhysicalInputKind.BUTTON),
		PhysicalMappingTarget("circle", R.string.physical_mapping_circle, PhysicalInputKind.BUTTON),
		PhysicalMappingTarget("square", R.string.physical_mapping_square, PhysicalInputKind.BUTTON),
		PhysicalMappingTarget("triangle", R.string.physical_mapping_triangle, PhysicalInputKind.BUTTON),
		PhysicalMappingTarget("l1", R.string.physical_mapping_l1, PhysicalInputKind.BUTTON),
		PhysicalMappingTarget("r1", R.string.physical_mapping_r1, PhysicalInputKind.BUTTON),
		PhysicalMappingTarget("l2", R.string.physical_mapping_l2, PhysicalInputKind.TRIGGER),
		PhysicalMappingTarget("r2", R.string.physical_mapping_r2, PhysicalInputKind.TRIGGER),
		PhysicalMappingTarget("l3", R.string.physical_mapping_l3, PhysicalInputKind.BUTTON),
		PhysicalMappingTarget("r3", R.string.physical_mapping_r3, PhysicalInputKind.BUTTON),
		PhysicalMappingTarget("options", R.string.physical_mapping_options, PhysicalInputKind.BUTTON),
		PhysicalMappingTarget("share", R.string.physical_mapping_share, PhysicalInputKind.BUTTON),
		PhysicalMappingTarget("ps", R.string.physical_mapping_ps, PhysicalInputKind.BUTTON),
		PhysicalMappingTarget("touchpad", R.string.physical_mapping_touchpad, PhysicalInputKind.BUTTON),
		PhysicalMappingTarget("dpad_up", R.string.physical_mapping_dpad_up, PhysicalInputKind.BUTTON),
		PhysicalMappingTarget("dpad_down", R.string.physical_mapping_dpad_down, PhysicalInputKind.BUTTON),
		PhysicalMappingTarget("dpad_left", R.string.physical_mapping_dpad_left, PhysicalInputKind.BUTTON),
		PhysicalMappingTarget("dpad_right", R.string.physical_mapping_dpad_right, PhysicalInputKind.BUTTON),
		PhysicalMappingTarget("left_x", R.string.physical_mapping_left_stick_x, PhysicalInputKind.STICK_AXIS),
		PhysicalMappingTarget("left_y", R.string.physical_mapping_left_stick_y, PhysicalInputKind.STICK_AXIS),
		PhysicalMappingTarget("right_x", R.string.physical_mapping_right_stick_x, PhysicalInputKind.STICK_AXIS),
		PhysicalMappingTarget("right_y", R.string.physical_mapping_right_stick_y, PhysicalInputKind.STICK_AXIS)
	)

	fun deviceKey(device: InputDevice): String =
		"${device.descriptor.ifBlank { device.name }}|${device.vendorId}:${device.productId}"

	fun isController(device: InputDevice): Boolean =
		(device.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
			(device.sources and InputDevice.SOURCE_CLASS_JOYSTICK) == InputDevice.SOURCE_CLASS_JOYSTICK

	fun loadProfile(context: Context, deviceKey: String): Map<String, PhysicalInputBinding>
	{
		val json = preferences(context).getString(profilePreferenceKey(deviceKey), null) ?: return emptyMap()
		return try
		{
			val profile = JSONObject(json)
			val result = mutableMapOf<String, PhysicalInputBinding>()
			for(target in targets)
			{
				val value = profile.optJSONObject(target.id) ?: continue
				val keyCode = if(value.has("keyCode")) value.optInt("keyCode") else null
				val axis = if(value.has("axis")) value.optInt("axis") else null
				if(keyCode != null || axis != null)
					result[target.id] = PhysicalInputBinding(keyCode, axis)
			}
			result
		}
		catch(_: Exception)
		{
			emptyMap()
		}
	}

	fun saveBinding(context: Context, deviceKey: String, targetId: String, binding: PhysicalInputBinding)
	{
		val profile = JSONObject()
		loadProfile(context, deviceKey).forEach { (id, saved) ->
			profile.put(id, JSONObject().apply {
				saved.keyCode?.let { put("keyCode", it) }
				saved.axis?.let { put("axis", it) }
			})
		}
		profile.put(targetId, JSONObject().apply {
			binding.keyCode?.let { put("keyCode", it) }
			binding.axis?.let { put("axis", it) }
		})
		preferences(context).edit().putString(profilePreferenceKey(deviceKey), profile.toString()).apply()
	}

	fun clearProfile(context: Context, deviceKey: String)
	{
		preferences(context).edit().remove(profilePreferenceKey(deviceKey)).apply()
	}

	private fun profilePreferenceKey(deviceKey: String) = "physical_controller_mapping:$deviceKey"

	private fun preferences(context: Context) = PreferenceManager.getDefaultSharedPreferences(context)

	fun bindingLabel(context: Context, binding: PhysicalInputBinding?): String
	{
		if(binding == null)
			return context.getString(R.string.physical_mapping_default)
		return when
		{
			binding.keyCode != null -> KeyEvent.keyCodeToString(binding.keyCode).removePrefix("KEYCODE_")
			binding.axis != null -> MotionEvent.axisToString(binding.axis).removePrefix("AXIS_")
			else -> context.getString(R.string.physical_mapping_default)
		}
	}
}
