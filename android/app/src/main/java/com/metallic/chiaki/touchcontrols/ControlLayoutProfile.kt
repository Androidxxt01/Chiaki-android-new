// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.touchcontrols

import android.content.Context
import androidx.preference.PreferenceManager
import org.json.JSONObject

/** A layout stores normalized top-left coordinates and normalized dimensions. */
data class ControlLayoutItem(
	var x: Float,
	var y: Float,
	var width: Float,
	var height: Float,
	var opacity: Int = 100
)
data class ControlLayoutProfile(
	var name: String,
	var opacity: Int = 75,
	val items: MutableMap<String, ControlLayoutItem> = mutableMapOf()
)

class ControlLayoutStore(context: Context) {
	private val preferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val key = "virtual_controls_layout_profiles"
    private val activeKey = "virtual_controls_active_profile"

	fun profiles(): MutableList<ControlLayoutProfile> = try {
		val array = org.json.JSONArray(preferences.getString(key, "[]"))
		MutableList(array.length()) { index ->
			val entry = array.getJSONObject(index)
			val items = mutableMapOf<String, ControlLayoutItem>()
			val itemObject = entry.optJSONObject("items") ?: JSONObject()
			itemObject.keys().forEach { id -> itemObject.optJSONObject(id)?.let { item ->
				items[id] = ControlLayoutItem(
					item.optDouble("x").toFloat(), item.optDouble("y").toFloat(),
					item.optDouble("w").toFloat(), item.optDouble("h").toFloat(),
					item.optInt("opacity", 100).coerceIn(10, 100)
				)
			} }
			ControlLayoutProfile(entry.optString("name", "Layout ${index + 1}"), entry.optInt("opacity", 75).coerceIn(10, 100), items)
		}
	} catch (_: Exception) { mutableListOf() }

	fun save(profiles: List<ControlLayoutProfile>, active: String) {
		val array = org.json.JSONArray()
		profiles.forEach { profile ->
			val items = JSONObject()
			profile.items.forEach { (id, item) -> items.put(id, JSONObject().put("x", item.x).put("y", item.y).put("w", item.width).put("h", item.height).put("opacity", item.opacity)) }
			array.put(JSONObject().put("name", profile.name).put("opacity", profile.opacity).put("items", items))
		}
		preferences.edit().putString(key, array.toString()).putString(activeKey, active).apply()
	}
	fun activeName(): String = preferences.getString(activeKey, "Padrão") ?: "Padrão"
}
