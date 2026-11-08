// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.settings

import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.metallic.chiaki.R
import com.metallic.chiaki.session.PhysicalControllerMappings
import com.metallic.chiaki.session.PhysicalInputBinding
import com.metallic.chiaki.session.PhysicalInputKind
import com.metallic.chiaki.session.PhysicalMappingTarget

class PhysicalControllerMappingActivity : AppCompatActivity()
{
	private lateinit var deviceSpinner: Spinner
	private lateinit var deviceStatus: TextView
	private lateinit var rowsContainer: LinearLayout
	private var devices: List<InputDevice> = emptyList()
	private var selectedDeviceId: Int? = null
	private var learningTarget: PhysicalMappingTarget? = null
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
		deviceSpinner = Spinner(this)
		page.addView(deviceSpinner, matchWidth())
		deviceStatus = TextView(this).apply { setPadding(0, dp(8), 0, dp(8)) }
		page.addView(deviceStatus, matchWidth())

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
		devices = InputDevice.getDeviceIds().mapNotNull { InputDevice.getDevice(it) }
			.filter { PhysicalControllerMappings.isController(it) }
		val selectedIndex = devices.indexOfFirst { it.id == preferredDeviceId }.let { if(it >= 0) it else 0 }
		selectedDeviceId = devices.getOrNull(selectedIndex)?.id
		val labels = devices.map { "${it.name} (${it.vendorId.toString(16)}:${it.productId.toString(16)})" }
		deviceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).apply {
			setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
		}
		if(devices.isNotEmpty())
			deviceSpinner.setSelection(selectedIndex)
		deviceStatus.text = if(devices.isEmpty()) getString(R.string.physical_mapping_no_device) else devices[selectedIndex].name
		renderRows()
	}

	private fun renderRows()
	{
		if(!::rowsContainer.isInitialized)
			return
		rowsContainer.removeAllViews()
		mappingButtons.clear()
		val device = devices.firstOrNull { it.id == selectedDeviceId }
		val profile = device?.let { PhysicalControllerMappings.loadProfile(this, PhysicalControllerMappings.deviceKey(it)) }.orEmpty()
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
		learningTarget = target
		mappingButtons[target.id]?.text = getString(R.string.physical_mapping_press_control)
	}

	private fun saveLearnedBinding(target: PhysicalMappingTarget, binding: PhysicalInputBinding, device: InputDevice)
	{
		PhysicalControllerMappings.saveBinding(this, PhysicalControllerMappings.deviceKey(device), target.id, binding)
		learningTarget = null
		selectedDeviceId = device.id
		refreshDevices(device.id)
	}

	private fun restoreSelectedProfile()
	{
		val device = devices.firstOrNull { it.id == selectedDeviceId } ?: return
		PhysicalControllerMappings.clearProfile(this, PhysicalControllerMappings.deviceKey(device))
		learningTarget = null
		renderRows()
	}

	override fun dispatchKeyEvent(event: KeyEvent): Boolean
	{
		val target = learningTarget
		val device = event.device ?: InputDevice.getDevice(event.deviceId)
		if(target != null && target.kind != PhysicalInputKind.STICK_AXIS && event.action == KeyEvent.ACTION_DOWN && device != null &&
			PhysicalControllerMappings.isController(device))
		{
			if(selectedDeviceId == null)
				refreshDevices(device.id)
			if(device.id == selectedDeviceId)
			{
				saveLearnedBinding(target, PhysicalInputBinding(keyCode = event.keyCode), device)
				return true
			}
		}
		return super.dispatchKeyEvent(event)
	}

	override fun onGenericMotionEvent(event: MotionEvent): Boolean
	{
		val device = event.device ?: InputDevice.getDevice(event.deviceId)
		if(device != null && PhysicalControllerMappings.isController(device) &&
			devices.none { it.id == device.id })
			refreshDevices(device.id)

		val target = learningTarget
		if(target != null && event.action == MotionEvent.ACTION_MOVE &&
			event.source and InputDevice.SOURCE_CLASS_JOYSTICK == InputDevice.SOURCE_CLASS_JOYSTICK &&
			device != null && target.kind != PhysicalInputKind.BUTTON && device.id == selectedDeviceId)
		{
			val binding = findMovedAxis(device, event, target.kind)
			if(binding != null)
			{
				saveLearnedBinding(target, binding, device)
				return true
			}
		}
		return super.onGenericMotionEvent(event)
	}

	private fun findMovedAxis(device: InputDevice, event: MotionEvent, kind: PhysicalInputKind): PhysicalInputBinding?
	{
		val allowedAxes = when(kind)
		{
			PhysicalInputKind.TRIGGER -> setOf(MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER,
				MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_GAS, MotionEvent.AXIS_THROTTLE)
			PhysicalInputKind.STICK_AXIS -> setOf(MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z,
				MotionEvent.AXIS_RZ, MotionEvent.AXIS_RX, MotionEvent.AXIS_RY)
			PhysicalInputKind.BUTTON -> emptySet()
		}
		val moved = device.motionRanges.asSequence()
			.filter { it.axis in allowedAxes }
			.map { range ->
				val value = event.getAxisValue(range.axis)
				val amount = if(kind == PhysicalInputKind.TRIGGER)
					if(range.max > range.min) ((value - range.min) / (range.max - range.min)).coerceIn(0.0f, 1.0f) else 0.0f
				else if(value < 0.0f && range.min < 0.0f) value / -range.min
				else if(value > 0.0f && range.max > 0.0f) value / range.max
				else 0.0f
				range.axis to kotlin.math.abs(amount)
			}
			.filter { (axis, amount) ->
				val range = device.motionRanges.firstOrNull { it.axis == axis }
				val threshold = if(kind == PhysicalInputKind.TRIGGER && range != null && range.min < 0.0f && range.max > 0.0f) 0.7f
					else if(kind == PhysicalInputKind.TRIGGER) 0.45f else 0.65f
				amount >= threshold
			}
			.maxByOrNull { it.second }
		return moved?.let { PhysicalInputBinding(axis = it.first) }
	}

	private fun matchWidth() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
	private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
