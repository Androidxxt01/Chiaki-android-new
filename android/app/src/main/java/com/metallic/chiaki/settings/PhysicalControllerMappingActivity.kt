// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.settings

import android.os.Bundle
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.widget.EditText
import android.widget.Toast
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import com.metallic.chiaki.R
import com.metallic.chiaki.session.PhysicalControllerMappings
import com.metallic.chiaki.session.PhysicalInputBinding
import com.metallic.chiaki.session.PhysicalInputAtom
import com.metallic.chiaki.session.PhysicalInputKind
import com.metallic.chiaki.session.PhysicalMappingTarget

class PhysicalControllerMappingActivity : AppCompatActivity()
{
	private lateinit var deviceSpinner: Spinner
	private lateinit var deviceStatus: TextView
	private lateinit var profileSpinner: Spinner
	private lateinit var rowsContainer: LinearLayout
	private lateinit var comboControls: LinearLayout
	private var devices: List<InputDevice> = emptyList()
	private var selectedDeviceId: Int? = null
	private var selectedProfileId: String = "default"
	private var updatingProfileSpinner = false
	private var learningTarget: PhysicalMappingTarget? = null
	private var learningCombo = false
	private var learningDialog: AlertDialog? = null
	private val capturedCombo = mutableListOf<PhysicalInputAtom>()
	private val mappingButtons = mutableMapOf<String, Button>()

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		val page = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(dp(16), dp(8), dp(16), dp(16))
		}
		page.addView(TextView(this).apply {
			text = getString(R.string.physical_mapping_title)
			textSize = 22f
			setPadding(0, dp(8), 0, dp(12))
		}, matchWidth())
		page.addView(TextView(this).apply {
			text = getString(R.string.physical_mapping_instructions)
		}, matchWidth())
		profileSpinner = Spinner(this)
		page.addView(profileSpinner, matchWidth())
		page.addView(Button(this).apply {
			text = getString(R.string.physical_mapping_profile_actions)
			setOnClickListener { showProfileActions() }
		}, matchWidth())
		deviceSpinner = Spinner(this)
		page.addView(deviceSpinner, matchWidth())
		deviceStatus = TextView(this).apply { setPadding(0, dp(8), 0, dp(8)) }
		page.addView(deviceStatus, matchWidth())
		comboControls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE }
		page.addView(comboControls, matchWidth())

		val scrollView = ScrollView(this)
		rowsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
		scrollView.addView(rowsContainer)
		page.addView(scrollView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

		page.addView(Button(this).apply {
			text = getString(R.string.physical_mapping_restore_default)
			setOnClickListener { restoreSelectedProfile() }
		}, matchWidth())
		setContentView(page)
		deviceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
			override fun onNothingSelected(parent: AdapterView<*>?) { selectedDeviceId = null; renderRows() }
			override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long)
			{
				selectedDeviceId = devices.getOrNull(position)?.id
				renderRows()
			}
		}
		profileSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
			override fun onNothingSelected(parent: AdapterView<*>?) = Unit
			override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long)
			{
				if(updatingProfileSpinner) return
				val profile = PhysicalControllerMappings.profiles(this@PhysicalControllerMappingActivity).getOrNull(position) ?: return
				selectedProfileId = profile.id
				if(profile.deviceKey == null) PhysicalControllerMappings.setActiveGlobalProfile(this@PhysicalControllerMappingActivity, profile.id)
				renderRows()
			}
		}
		refreshDevices()
	}

	override fun onResume()
	{
		super.onResume()
		if(::deviceSpinner.isInitialized)
			refreshDevices()
	}

	private fun refreshDevices(preferredDeviceId: Int? = selectedDeviceId)
	{
		devices = InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }
			.filter { PhysicalControllerMappings.isController(it) }
		val selectedIndex = devices.indexOfFirst { it.id == preferredDeviceId }.let { if(it >= 0) it else 0 }
		selectedDeviceId = devices.getOrNull(selectedIndex)?.id
		val selectedDevice = devices.getOrNull(selectedIndex)
		selectedProfileId = if(selectedDevice != null)
			PhysicalControllerMappings.profileForDevice(this, PhysicalControllerMappings.deviceKey(selectedDevice)).id
		else PhysicalControllerMappings.activeGlobalProfileId(this)
		val labels = devices.map { "${it.name} (${it.vendorId.toString(16)}:${it.productId.toString(16)})" }
		deviceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).apply {
			setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
		}
		if(devices.isNotEmpty())
			deviceSpinner.setSelection(selectedIndex)
		deviceStatus.text = if(devices.isEmpty()) getString(R.string.physical_mapping_no_device) else devices[selectedIndex].name
		renderProfileSelector()
		renderRows()
	}

	private fun renderProfileSelector()
	{
		val profiles = PhysicalControllerMappings.profiles(this)
		val selectedIndex = profiles.indexOfFirst { it.id == selectedProfileId }.coerceAtLeast(0)
		updatingProfileSpinner = true
		profileSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item,
			profiles.map { profile -> if(profile.deviceKey == null) profile.name else "${profile.name} (${getString(R.string.physical_mapping_profile_associated)})" }
		).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
		profileSpinner.setSelection(selectedIndex)
		updatingProfileSpinner = false
	}

	private fun renderRows()
	{
		if(!::rowsContainer.isInitialized)
			return
		rowsContainer.removeAllViews()
		mappingButtons.clear()
		val device = devices.firstOrNull { it.id == selectedDeviceId }
		val profile = PhysicalControllerMappings.loadProfile(this, selectedProfileId, true)
		PhysicalControllerMappings.targets.forEach { target ->
			val row = LinearLayout(this).apply {
				orientation = LinearLayout.HORIZONTAL
				gravity = android.view.Gravity.CENTER_VERTICAL
				setPadding(0, dp(3), 0, dp(3))
			}
			row.addView(TextView(this).apply {
				text = getString(target.title)
			}, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
			val mapButton = Button(this).apply {
				isEnabled = device != null
		text = PhysicalControllerMappings.bindingLabel(this@PhysicalControllerMappingActivity, profile[target.id])
				setOnClickListener { startLearning(target) }
			}
			mappingButtons[target.id] = mapButton
			row.addView(mapButton)
			rowsContainer.addView(row, matchWidth())
		}
	}

	private fun startLearning(target: PhysicalMappingTarget)
	{
		if(target.kind == PhysicalInputKind.STICK_AXIS)
		{
			learningTarget = target
			learningCombo = false
			comboControls.visibility = View.GONE
			updateLearningLabel()
			return
		}
		AlertDialog.Builder(this).setMessage(R.string.physical_mapping_single_or_combo)
			.setItems(arrayOf(getString(R.string.physical_mapping_single), getString(R.string.physical_mapping_combo))) { _, which ->
				learningTarget = target
				learningCombo = which == 1
				capturedCombo.clear()
				if(learningCombo)
				{
					showComboControls()
					updateLearningLabel()
					showComboLearningDialog()
				}
				else
				{
					comboControls.visibility = View.GONE
					updateLearningLabel()
				}
			}.show()
	}

	private fun showComboLearningDialog()
	{
		val dialog = AlertDialog.Builder(this)
			.setTitle(R.string.physical_mapping_combo)
			.setMessage(getString(R.string.physical_mapping_press_control))
			.setPositiveButton(R.string.physical_mapping_combo_confirm) { _, _ -> confirmCombo() }
			.setNeutralButton(R.string.physical_mapping_combo_remove_last) { _, _ -> }
			.setNegativeButton(R.string.physical_mapping_combo_redo) { _, _ -> }
			.create()

		dialog.show()
		dialog.window?.let { window ->
			val originalCallback = window.callback
			window.callback = object : Window.Callback by originalCallback {
				override fun dispatchKeyEvent(event: KeyEvent): Boolean
				{
					Log.d("PhysMap", "key keyCode=${event.keyCode} source=${event.source} action=${event.action}")
					return if(handleLearningKeyEvent(event)) true else originalCallback.dispatchKeyEvent(event)
				}

				override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean
				{
					Log.d("PhysMap", "motion keyCode=none source=${event.source} action=${event.action}")
					return if(handleLearningMotionEvent(event)) true else originalCallback.dispatchGenericMotionEvent(event)
				}
			}
		}
		dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
			if(capturedCombo.isNotEmpty()) capturedCombo.removeAt(capturedCombo.lastIndex)
			updateLearningLabel()
			updateComboDialogMessage()
		}
		dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
			capturedCombo.clear()
			updateLearningLabel()
			updateComboDialogMessage()
		}
		listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_NEGATIVE).forEach { which ->
			dialog.getButton(which).apply {
				isFocusable = false
				isFocusableInTouchMode = false
			}
		}
		learningDialog = dialog
	}

	private fun updateComboDialogMessage()
	{
		val dialog = learningDialog ?: return
		val currentLabel = capturedCombo.joinToString(" + ") { PhysicalControllerMappings.bindingLabel(this, PhysicalInputBinding(it.keyCode, it.axis)) }
		val message = if(capturedCombo.isNotEmpty())
			"$currentLabel\n${getString(R.string.physical_mapping_combo_add_next)}" else getString(R.string.physical_mapping_press_control)
		dialog.findViewById<TextView>(android.R.id.message)?.text = message
	}

	private fun showComboControls()
	{
		comboControls.removeAllViews()
		comboControls.visibility = View.VISIBLE
		comboControls.addView(Button(this).apply {
			text = getString(R.string.physical_mapping_combo_remove_last)
			setOnClickListener { if(capturedCombo.isNotEmpty()) capturedCombo.removeAt(capturedCombo.lastIndex); updateLearningLabel() }
		}, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
		comboControls.addView(Button(this).apply {
			text = getString(R.string.physical_mapping_combo_redo)
			setOnClickListener { capturedCombo.clear(); updateLearningLabel() }
		}, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
		comboControls.addView(Button(this).apply {
			text = getString(R.string.physical_mapping_combo_confirm)
			setOnClickListener { confirmCombo() }
		}, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
	}

	private fun updateLearningLabel()
	{
		val target = learningTarget ?: return
		val label = if(learningCombo)
			capturedCombo.joinToString(" + ") { PhysicalControllerMappings.bindingLabel(this, PhysicalInputBinding(it.keyCode, it.axis)) }
		else getString(R.string.physical_mapping_press_control)
		mappingButtons[target.id]?.text = if(learningCombo && capturedCombo.size < 4)
			"$label\n${getString(R.string.physical_mapping_combo_add_next)}" else label
	}

	private fun captureInput(atom: PhysicalInputAtom, device: InputDevice)
	{
		val target = learningTarget ?: return
		if(!learningCombo)
		{
			saveLearnedBinding(target, PhysicalInputBinding(atom.keyCode, atom.axis), device)
			return
		}
		if(atom !in capturedCombo && capturedCombo.size < 4) capturedCombo.add(atom)
		updateLearningLabel()
		updateComboDialogMessage()
		if(capturedCombo.size >= 4)
		{
			confirmCombo()
		}
	}

	private fun confirmCombo()
	{
		val target = learningTarget ?: return
		if(capturedCombo.size !in 2..4)
		{
			Toast.makeText(this, R.string.physical_mapping_combo_limit, Toast.LENGTH_SHORT).show()
			return
		}
		val device = devices.firstOrNull { it.id == selectedDeviceId } ?: return
		val binding = PhysicalInputBinding(combo = capturedCombo.toList())
		val save = { saveLearnedBinding(target, binding, device) }
		if(PhysicalControllerMappings.hasComboConflict(this, selectedProfileId, target.id, binding))
			AlertDialog.Builder(this).setTitle(R.string.physical_mapping_conflict_title)
				.setMessage(R.string.physical_mapping_conflict_body)
				.setNegativeButton(android.R.string.cancel, null)
				.setPositiveButton(android.R.string.ok) { _, _ -> save() }.show()
		else save()
	}

	private fun saveLearnedBinding(target: PhysicalMappingTarget, binding: PhysicalInputBinding, device: InputDevice)
	{
		PhysicalControllerMappings.saveBinding(this, selectedProfileId, target.id, binding)
		learningDialog?.dismiss()
		learningDialog = null
		learningTarget = null
		learningCombo = false
		capturedCombo.clear()
		comboControls.visibility = View.GONE
		selectedDeviceId = device.id
		refreshDevices(device.id)
	}

	private fun restoreSelectedProfile()
	{
		PhysicalControllerMappings.clearProfile(this, selectedProfileId)
		learningTarget = null
		renderRows()
	}

	private fun showProfileActions()
	{
		val actions = arrayOf(
			getString(R.string.physical_mapping_profile_create),
			getString(R.string.physical_mapping_profile_rename),
			getString(R.string.physical_mapping_profile_duplicate),
			getString(R.string.physical_mapping_profile_delete),
			getString(R.string.physical_mapping_profile_associate),
			getString(R.string.physical_mapping_profile_global)
		)
		AlertDialog.Builder(this).setItems(actions) { _, which ->
			when(which)
			{
				0 -> editProfileName(getString(R.string.physical_mapping_profile_create)) { name ->
					selectedProfileId = PhysicalControllerMappings.createProfile(this, name)
					refreshDevices()
				}
				1 -> if(selectedProfileId != "default") editProfileName(getString(R.string.physical_mapping_profile_rename)) { name ->
					PhysicalControllerMappings.renameProfile(this, selectedProfileId, name)
					refreshDevices()
				}
				2 -> editProfileName(getString(R.string.physical_mapping_profile_duplicate)) { name ->
					selectedProfileId = PhysicalControllerMappings.createProfile(this, name, selectedProfileId)
					refreshDevices()
				}
				3 -> if(selectedProfileId != "default") AlertDialog.Builder(this)
					.setMessage(getString(R.string.physical_mapping_profile_delete_confirm))
					.setNegativeButton(android.R.string.cancel, null)
					.setPositiveButton(android.R.string.ok) { _, _ ->
						PhysicalControllerMappings.deleteProfile(this, selectedProfileId)
						selectedProfileId = PhysicalControllerMappings.activeGlobalProfileId(this)
						refreshDevices()
					}.show()
				4 -> devices.firstOrNull { it.id == selectedDeviceId }?.let { device ->
					PhysicalControllerMappings.associateProfile(this, selectedProfileId, PhysicalControllerMappings.deviceKey(device))
					refreshDevices(device.id)
				}
				5 -> {
					PhysicalControllerMappings.associateProfile(this, selectedProfileId, null)
					refreshDevices()
				}
			}
		}.show()
	}

	private fun editProfileName(title: String, onSave: (String) -> Unit)
	{
		val input = EditText(this).apply { setSingleLine(); hint = getString(R.string.physical_mapping_profile_name) }
		AlertDialog.Builder(this).setTitle(title).setView(input)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok) { _, _ ->
				val name = input.text.toString().trim()
				if(name.isNotEmpty()) onSave(name)
			}.show()
	}

	override fun dispatchKeyEvent(event: KeyEvent): Boolean
	{
		Log.d("PhysMap", "key keyCode=${event.keyCode} source=${event.source} action=${event.action}")
		if(handleLearningKeyEvent(event)) return true
		return super.dispatchKeyEvent(event)
	}

	override fun onGenericMotionEvent(event: MotionEvent): Boolean
	{
		Log.d("PhysMap", "motion keyCode=none source=${event.source} action=${event.action}")
		if(handleLearningMotionEvent(event)) return true
		val device = event.device ?: InputDevice.getDevice(event.deviceId)
		if(device != null && PhysicalControllerMappings.isController(device) &&
			devices.none { it.id == device.id })
			refreshDevices(device.id)
		return super.onGenericMotionEvent(event)
	}

	private fun handleLearningKeyEvent(event: KeyEvent): Boolean
	{
		val target = learningTarget
		if(target == null || target.kind == PhysicalInputKind.STICK_AXIS || event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0 ||
			!isControllerSource(event.source)) return false
		val device = event.device ?: InputDevice.getDevice(event.deviceId) ?: return false
		if(!PhysicalControllerMappings.isController(device)) return false
		if(selectedDeviceId == null) refreshDevices(device.id)
		if(device.id != selectedDeviceId) return false
		captureInput(PhysicalInputAtom(keyCode = event.keyCode), device)
		return true
	}

	private fun handleLearningMotionEvent(event: MotionEvent): Boolean
	{
		val target = learningTarget
		if(target == null || event.action != MotionEvent.ACTION_MOVE || !isControllerSource(event.source)) return false
		val device = event.device ?: InputDevice.getDevice(event.deviceId) ?: return false
		if(!PhysicalControllerMappings.isController(device)) return false
		if(selectedDeviceId == null) refreshDevices(device.id)
		if(device.id != selectedDeviceId || (target.kind == PhysicalInputKind.BUTTON && !learningCombo)) return false
		val axisKind = if(target.kind == PhysicalInputKind.BUTTON) PhysicalInputKind.TRIGGER else target.kind
		val binding = findMovedAxis(device, event, axisKind) ?: return false
		captureInput(PhysicalInputAtom(axis = binding.axis), device)
		return true
	}

	private fun isControllerSource(source: Int): Boolean =
		(source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
		(source and InputDevice.SOURCE_CLASS_JOYSTICK) == InputDevice.SOURCE_CLASS_JOYSTICK ||
		(source and InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD

	private fun findMovedAxis(device: InputDevice, event: MotionEvent, kind: PhysicalInputKind): PhysicalInputBinding?
	{
		val allowedAxes = when(kind)
		{
			PhysicalInputKind.TRIGGER -> setOf(MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER,
				MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_GAS, MotionEvent.AXIS_THROTTLE)
			PhysicalInputKind.STICK_AXIS -> setOf(MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z,
				MotionEvent.AXIS_RZ, MotionEvent.AXIS_RX, MotionEvent.AXIS_RY)
			PhysicalInputKind.BUTTON -> emptySet()
			PhysicalInputKind.ACTION -> setOf(MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER,
				MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_GAS, MotionEvent.AXIS_THROTTLE,
				MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
				MotionEvent.AXIS_RX, MotionEvent.AXIS_RY)
		}
		val moved = device.motionRanges.asSequence()
			.filter { it.axis in allowedAxes }
			.map { range ->
				val value = event.getAxisValue(range.axis)
				val isTriggerAxis = range.axis in setOf(MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_GAS, MotionEvent.AXIS_THROTTLE)
				val amount = if(kind == PhysicalInputKind.TRIGGER || (kind == PhysicalInputKind.ACTION && isTriggerAxis))
					if(range.max > range.min) ((value - range.min) / (range.max - range.min)).coerceIn(0.0f, 1.0f) else 0.0f
				else if(value < 0.0f && range.min < 0.0f) value / -range.min
				else if(value > 0.0f && range.max > 0.0f) value / range.max
				else 0.0f
				range.axis to kotlin.math.abs(amount)
			}
			.filter { (axis, amount) ->
				val range = device.motionRanges.firstOrNull { it.axis == axis }
				val isTriggerAxis = range != null && range.axis in setOf(MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_GAS, MotionEvent.AXIS_THROTTLE)
				val triggerKind = kind == PhysicalInputKind.TRIGGER || (kind == PhysicalInputKind.ACTION && isTriggerAxis)
				val threshold = if(triggerKind && range != null && range.min < 0.0f && range.max > 0.0f) 0.7f
					else if(triggerKind) 0.45f else 0.65f
				amount >= threshold
			}
			.maxByOrNull { it.second }
		return moved?.let { PhysicalInputBinding(axis = it.first) }
	}

	private fun matchWidth() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
	private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
