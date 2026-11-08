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
import org.json.JSONArray
import java.util.UUID

enum class PhysicalInputKind
{
	BUTTON,
	TRIGGER,
	STICK_AXIS
}

data class PhysicalMappingTarget(val id: String, @StringRes val title: Int, val kind: PhysicalInputKind)

data class PhysicalInputBinding(val keyCode: Int? = null, val axis: Int? = null)
data class PhysicalMappingProfile(
	val id: String,
	var name: String,
	var deviceKey: String? = null,
	val bindings: MutableMap<String, PhysicalInputBinding> = mutableMapOf()
)

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

	private const val profilesKey = "physical_controller_mapping_profiles_v1"
	private const val activeProfileKey = "physical_controller_mapping_active_profile"
	private const val defaultProfileId = "default"
	private const val defaultProfileName = "Padrão"

	fun profiles(context: Context): List<PhysicalMappingProfile> = readProfiles(context).values.toList()
	fun activeGlobalProfileId(context: Context): String =
		preferences(context).getString(activeProfileKey, defaultProfileId) ?: defaultProfileId

	fun setActiveGlobalProfile(context: Context, id: String)
	{
		val profile = readProfiles(context)[id]
		if(profile != null && profile.deviceKey == null)
			preferences(context).edit().putString(activeProfileKey, id).apply()
	}

	fun profileForDevice(context: Context, deviceKey: String): PhysicalMappingProfile
	{
		val profiles = readProfiles(context)
		return profiles.values.firstOrNull { it.deviceKey == deviceKey }
			?: profiles[activeGlobalProfileId(context)]
			?: profiles[defaultProfileId]!!
	}

	fun loadProfile(context: Context, deviceKey: String): Map<String, PhysicalInputBinding> =
		profileForDevice(context, deviceKey).bindings.toMap()

	fun loadProfile(context: Context, profileId: String, @Suppress("UNUSED_PARAMETER") forProfileId: Boolean): Map<String, PhysicalInputBinding> =
		readProfiles(context)[profileId]?.bindings?.toMap().orEmpty()

	fun saveBinding(context: Context, profileId: String, targetId: String, binding: PhysicalInputBinding)
	{
		val profiles = readProfiles(context)
		profiles[profileId]?.bindings?.set(targetId, binding) ?: return
		writeProfiles(context, profiles)
	}

	fun clearProfile(context: Context, profileId: String)
	{
		val profiles = readProfiles(context)
		profiles[profileId]?.bindings?.clear() ?: return
		writeProfiles(context, profiles)
	}

	fun createProfile(context: Context, name: String, sourceId: String? = null): String
	{
		val profiles = readProfiles(context)
		val id = UUID.randomUUID().toString()
		val copy = sourceId?.let { profiles[it]?.bindings?.toMutableMap() } ?: mutableMapOf()
		profiles[id] = PhysicalMappingProfile(id, name.trim(), null, copy)
		writeProfiles(context, profiles)
		preferences(context).edit().putString(activeProfileKey, id).apply()
		return id
	}

	fun renameProfile(context: Context, id: String, name: String)
	{
		val profiles = readProfiles(context)
		profiles[id]?.name = name.trim()
		writeProfiles(context, profiles)
	}

	fun deleteProfile(context: Context, id: String)
	{
		if(id == defaultProfileId) return
		val profiles = readProfiles(context)
		profiles.remove(id) ?: return
		if(preferences(context).getString(activeProfileKey, defaultProfileId) == id)
			preferences(context).edit().putString(activeProfileKey, defaultProfileId).apply()
		writeProfiles(context, profiles)
	}

	fun associateProfile(context: Context, id: String, deviceKey: String?)
	{
		val profiles = readProfiles(context)
		val profile = profiles[id] ?: return
		if(deviceKey != null)
			profiles.values.firstOrNull { it.deviceKey == deviceKey && it.id != id }?.deviceKey = null
		profile.deviceKey = deviceKey
		writeProfiles(context, profiles)
		if(deviceKey == null) setActiveGlobalProfile(context, id)
		else if(preferences(context).getString(activeProfileKey, defaultProfileId) == id)
			preferences(context).edit().putString(activeProfileKey, defaultProfileId).apply()
	}

	private fun readProfiles(context: Context): LinkedHashMap<String, PhysicalMappingProfile>
	{
		val prefs = preferences(context)
		val result = linkedMapOf<String, PhysicalMappingProfile>()
		try
		{
			val saved = prefs.getString(profilesKey, null)
			if(saved != null)
			{
				val array = JSONArray(saved)
				for(index in 0 until array.length())
				{
					val item = array.getJSONObject(index)
					val id = item.getString("id")
					result[id] = PhysicalMappingProfile(id, item.getString("name"), item.optString("deviceKey").takeIf { it.isNotEmpty() }, readBindings(item.optJSONObject("bindings") ?: JSONObject()))
				}
			}
		}
		catch(_: Exception) { result.clear() }
		if(result.isEmpty())
		{
			result[defaultProfileId] = PhysicalMappingProfile(defaultProfileId, defaultProfileName)
			// Migrate legacy per-device profiles intact, preserving each controller's mapping.
			prefs.all.filterKeys { it.startsWith("physical_controller_mapping:") }.forEach { (key, value) ->
				val deviceKey = key.removePrefix("physical_controller_mapping:")
				val id = UUID.randomUUID().toString()
				val label = deviceKey.substringBefore('|').ifBlank { "Controle" }
				result[id] = PhysicalMappingProfile(id, label, deviceKey, readBindings(JSONObject(value as? String ?: "{}")))
			}
			writeProfiles(context, result)
		}
		if(defaultProfileId !in result)
		{
			result[defaultProfileId] = PhysicalMappingProfile(defaultProfileId, defaultProfileName)
			writeProfiles(context, result)
		}
		return result
	}

	private fun readBindings(json: JSONObject): MutableMap<String, PhysicalInputBinding>
	{
		val result = mutableMapOf<String, PhysicalInputBinding>()
		for(target in targets)
		{
			val value = json.optJSONObject(target.id) ?: continue
			val keyCode = if(value.has("keyCode")) value.optInt("keyCode") else null
			val axis = if(value.has("axis")) value.optInt("axis") else null
			if(keyCode != null || axis != null) result[target.id] = PhysicalInputBinding(keyCode, axis)
		}
		return result
	}

	private fun writeProfiles(context: Context, profiles: Map<String, PhysicalMappingProfile>)
	{
		val array = JSONArray()
		profiles.values.forEach { profile ->
			val bindings = JSONObject()
			profile.bindings.forEach { (target, binding) ->
				bindings.put(target, JSONObject().apply {
					binding.keyCode?.let { put("keyCode", it) }
					binding.axis?.let { put("axis", it) }
				})
			}
			array.put(JSONObject().put("id", profile.id).put("name", profile.name)
				.put("deviceKey", profile.deviceKey).put("bindings", bindings))
		}
		preferences(context).edit().putString(profilesKey, array.toString()).apply()
	}

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
