package com.metallic.chiaki.session

import android.content.Context
import android.hardware.*
import android.view.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.OnLifecycleEvent
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.lib.ControllerState

class StreamInput(val context: Context, val preferences: Preferences)
{
	var controllerStateChangedCallback: ((ControllerState) -> Unit)? = null

	val controllerState: ControllerState get()
	{
		val controllerState = sensorControllerState or physicalControllerState()

		val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
		@Suppress("DEPRECATION")
		when(windowManager.defaultDisplay.rotation)
		{
			Surface.ROTATION_90 -> {
				controllerState.accelX *= -1.0f
				controllerState.accelZ *= -1.0f
				controllerState.gyroX *= -1.0f
				controllerState.gyroZ *= -1.0f
				controllerState.orientX *= -1.0f
				controllerState.orientZ *= -1.0f
			}
			else -> {}
		}

		return controllerState or touchControllerState
	}

	private val sensorControllerState = ControllerState() // from Motion Sensors
	private val physicalDevices = mutableMapOf<String, PhysicalDeviceState>()
	var touchControllerState = ControllerState()
		set(value)
		{
			field = value
			controllerStateUpdated()
		}

	private val swapCrossMoon = preferences.swapCrossMoon
	private val motionAxes = intArrayOf(
		MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
		MotionEvent.AXIS_RX, MotionEvent.AXIS_RY, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER,
		MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y, MotionEvent.AXIS_BRAKE,
		MotionEvent.AXIS_GAS, MotionEvent.AXIS_THROTTLE
	)

	private class PhysicalDeviceState(var device: InputDevice?)
	{
		var mapping: Map<String, PhysicalInputBinding> = emptyMap()
		val pressedKeys = mutableSetOf<Int>()
		val axisValues = mutableMapOf<Int, Float>()
	}

	private val sensorEventListener = object: SensorEventListener {
		override fun onSensorChanged(event: SensorEvent)
		{
			when(event.sensor.type)
			{
				Sensor.TYPE_ACCELEROMETER -> {
					sensorControllerState.accelX = event.values[1] / SensorManager.GRAVITY_EARTH
					sensorControllerState.accelY = event.values[2] / SensorManager.GRAVITY_EARTH
					sensorControllerState.accelZ = event.values[0] / SensorManager.GRAVITY_EARTH
				}
				Sensor.TYPE_GYROSCOPE -> {
					sensorControllerState.gyroX = event.values[1]
					sensorControllerState.gyroY = event.values[2]
					sensorControllerState.gyroZ = event.values[0]
				}
				Sensor.TYPE_ROTATION_VECTOR -> {
					val q = floatArrayOf(0f, 0f, 0f, 0f)
					SensorManager.getQuaternionFromVector(q, event.values)
					sensorControllerState.orientX = q[2]
					sensorControllerState.orientY = q[3]
					sensorControllerState.orientZ = q[1]
					sensorControllerState.orientW = q[0]
				}
				else -> return
			}
			controllerStateUpdated()
		}

		override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
	}

	private val motionLifecycleObserver = object: LifecycleObserver {
		@OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
		fun onResume()
		{
			val samplingPeriodUs = 4000
			val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
			listOfNotNull(
				sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
				sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE),
				sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
			).forEach {
				sensorManager.registerListener(sensorEventListener, it, samplingPeriodUs)
			}
		}

		@OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
		fun onPause()
		{
			val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
			sensorManager.unregisterListener(sensorEventListener)
		}
	}

	fun observe(lifecycleOwner: LifecycleOwner)
	{
		if(preferences.motionEnabled)
			lifecycleOwner.lifecycle.addObserver(motionLifecycleObserver)
	}

	private fun controllerStateUpdated()
	{
		controllerStateChangedCallback?.let { it(controllerState) }
	}

	fun dispatchKeyEvent(event: KeyEvent): Boolean
	{
		//Log.i("StreamSession", "key event $event")
		if(event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP)
			return false
		val device = event.device
		val deviceKey = device?.let { PhysicalControllerMappings.deviceKey(it) } ?: "unknown"
		val state = physicalState(deviceKey, device)
		if(event.keyCode !in allHandledKeyCodes(state.mapping))
			return false
		if(event.action == KeyEvent.ACTION_DOWN)
			state.pressedKeys.add(event.keyCode)
		else
			state.pressedKeys.remove(event.keyCode)

		controllerStateUpdated()
		return true
	}

	fun onGenericMotionEvent(event: MotionEvent): Boolean
	{
		if(event.source and InputDevice.SOURCE_CLASS_JOYSTICK != InputDevice.SOURCE_CLASS_JOYSTICK)
			return false
		val device = event.device ?: InputDevice.getDevice(event.deviceId) ?: return false
		val deviceKey = PhysicalControllerMappings.deviceKey(device)
		val state = physicalState(deviceKey, device)
		motionAxes.forEach { state.axisValues[it] = event.getAxisValue(it) }
		//Log.i("StreamSession", "motionEvent => $state")
		controllerStateUpdated()
		return true
	}

	private fun physicalState(deviceKey: String, device: InputDevice?): PhysicalDeviceState =
		physicalDevices.getOrPut(deviceKey) {
			PhysicalDeviceState(device).apply {
				mapping = PhysicalControllerMappings.loadProfile(context, deviceKey)
			}
		}.also { it.device = device ?: it.device }

	private fun allHandledKeyCodes(profile: Map<String, PhysicalInputBinding>): Set<Int>
	{
		return profile.values.mapNotNull { it.keyCode }.toSet() + setOf(
			KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
			KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
			KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR, KeyEvent.KEYCODE_BUTTON_SELECT,
			KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_C, KeyEvent.KEYCODE_BUTTON_MODE,
			KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT
		)
	}

	private fun physicalControllerState(): ControllerState
	{
		var combined = ControllerState()
		physicalDevices.values.forEach { input ->
			combined = combined or input.toControllerState(input.mapping)
		}
		return combined
	}

	private fun PhysicalDeviceState.toControllerState(profile: Map<String, PhysicalInputBinding>): ControllerState
	{
		fun pressed(target: String, defaultKeyCode: Int? = null, defaultPressed: Boolean = false): Boolean
		{
			val binding = profile[target]
			return when
			{
				binding?.keyCode != null -> binding.keyCode in pressedKeys
				binding?.axis != null -> axisValues[binding.axis]?.let { kotlin.math.abs(it) > 0.5f } ?: false
				defaultKeyCode != null -> defaultKeyCode in pressedKeys || defaultPressed
				else -> defaultPressed
			}
		}

		fun axisValue(target: String, defaultAxis: Int): Float
		{
			val binding = profile[target]
			return axisValues[binding?.axis ?: defaultAxis] ?: 0.0f
		}

		fun stickAxis(target: String, defaultAxis: Int): Short
		{
			val binding = profile[target]
			val value = axisValue(target, defaultAxis)
			val normalized = if(binding?.axis != null) normalizeStickAxis(value, binding.axis, device) else value
			return (normalized.coerceIn(-1.0f, 1.0f) * Short.MAX_VALUE).toInt().toShort()
		}

		fun trigger(target: String, defaultAxis: Int, defaultKeyCode: Int): UByte
		{
			val binding = profile[target]
			if(binding?.keyCode != null)
				return if(binding.keyCode in pressedKeys) UByte.MAX_VALUE else 0U
			if(binding?.axis != null)
			{
				val value = axisValues[binding.axis] ?: return 0U
				return (normalizeTriggerAxis(value, binding.axis, device) * UByte.MAX_VALUE.toFloat()).toInt().coerceIn(0, 255).toUByte()
			}
			val rawAxis = axisValues[defaultAxis]
			val analog = rawAxis?.let { normalizeTriggerAxis(it, defaultAxis, device) } ?: 0.0f
			val digital = defaultKeyCode in pressedKeys
			return maxOf((analog * UByte.MAX_VALUE.toFloat()).toInt().toUByte(), if(digital) UByte.MAX_VALUE else 0U)
		}

		fun defaultKey(target: String): Int? = when(target)
		{
			"cross" -> if(swapCrossMoon) KeyEvent.KEYCODE_BUTTON_B else KeyEvent.KEYCODE_BUTTON_A
			"circle" -> if(swapCrossMoon) KeyEvent.KEYCODE_BUTTON_A else KeyEvent.KEYCODE_BUTTON_B
			"square" -> if(swapCrossMoon) KeyEvent.KEYCODE_BUTTON_Y else KeyEvent.KEYCODE_BUTTON_X
			"triangle" -> if(swapCrossMoon) KeyEvent.KEYCODE_BUTTON_X else KeyEvent.KEYCODE_BUTTON_Y
			"l1" -> KeyEvent.KEYCODE_BUTTON_L1
			"r1" -> KeyEvent.KEYCODE_BUTTON_R1
			"l2" -> KeyEvent.KEYCODE_BUTTON_L2
			"r2" -> KeyEvent.KEYCODE_BUTTON_R2
			"l3" -> KeyEvent.KEYCODE_BUTTON_THUMBL
			"r3" -> KeyEvent.KEYCODE_BUTTON_THUMBR
			"options" -> KeyEvent.KEYCODE_BUTTON_START
			"share" -> KeyEvent.KEYCODE_BUTTON_SELECT
			"ps" -> if(KeyEvent.KEYCODE_BUTTON_C in pressedKeys) KeyEvent.KEYCODE_BUTTON_C else KeyEvent.KEYCODE_BUTTON_MODE
			"dpad_up" -> KeyEvent.KEYCODE_DPAD_UP
			"dpad_down" -> KeyEvent.KEYCODE_DPAD_DOWN
			"dpad_left" -> KeyEvent.KEYCODE_DPAD_LEFT
			"dpad_right" -> KeyEvent.KEYCODE_DPAD_RIGHT
			else -> null
		}

		fun dpad(target: String, defaultDirection: Boolean): Boolean =
			pressed(target, defaultKey(target), defaultDirection)

		var buttons = 0U
		fun setButton(target: String, mask: UInt)
		{
			if(pressed(target, defaultKey(target)))
				buttons = buttons or mask
		}
		setButton("cross", ControllerState.BUTTON_CROSS)
		setButton("circle", ControllerState.BUTTON_MOON)
		setButton("square", ControllerState.BUTTON_BOX)
		setButton("triangle", ControllerState.BUTTON_PYRAMID)
		setButton("l1", ControllerState.BUTTON_L1)
		setButton("r1", ControllerState.BUTTON_R1)
		setButton("l3", ControllerState.BUTTON_L3)
		setButton("r3", ControllerState.BUTTON_R3)
		setButton("options", ControllerState.BUTTON_OPTIONS)
		setButton("share", ControllerState.BUTTON_SHARE)
		setButton("ps", ControllerState.BUTTON_PS)
		setButton("touchpad", ControllerState.BUTTON_TOUCHPAD)
		if(dpad("dpad_left", (axisValues[MotionEvent.AXIS_HAT_X] ?: 0.0f) < -0.5f)) buttons = buttons or ControllerState.BUTTON_DPAD_LEFT
		if(dpad("dpad_right", (axisValues[MotionEvent.AXIS_HAT_X] ?: 0.0f) > 0.5f)) buttons = buttons or ControllerState.BUTTON_DPAD_RIGHT
		if(dpad("dpad_up", (axisValues[MotionEvent.AXIS_HAT_Y] ?: 0.0f) < -0.5f)) buttons = buttons or ControllerState.BUTTON_DPAD_UP
		if(dpad("dpad_down", (axisValues[MotionEvent.AXIS_HAT_Y] ?: 0.0f) > 0.5f)) buttons = buttons or ControllerState.BUTTON_DPAD_DOWN

		return ControllerState(
			buttons = buttons,
			l2State = trigger("l2", MotionEvent.AXIS_LTRIGGER, KeyEvent.KEYCODE_BUTTON_L2),
			r2State = trigger("r2", MotionEvent.AXIS_RTRIGGER, KeyEvent.KEYCODE_BUTTON_R2),
			leftX = stickAxis("left_x", MotionEvent.AXIS_X),
			leftY = stickAxis("left_y", MotionEvent.AXIS_Y),
			rightX = stickAxis("right_x", MotionEvent.AXIS_Z),
			rightY = stickAxis("right_y", MotionEvent.AXIS_RZ)
		)
	}

	private fun normalizeStickAxis(value: Float, axis: Int, device: InputDevice?): Float
	{
		val range = device?.motionRanges?.firstOrNull { it.axis == axis } ?: return value
		return if(value < 0.0f && range.min < 0.0f)
			value / -range.min
		else if(value > 0.0f && range.max > 0.0f)
			value / range.max
		else 0.0f
	}

	private fun normalizeTriggerAxis(value: Float, axis: Int, device: InputDevice?): Float
	{
		val range = device?.motionRanges?.firstOrNull { it.axis == axis } ?: return value.coerceIn(0.0f, 1.0f)
		val span = range.max - range.min
		return if(span > 0.0f) ((value - range.min) / span).coerceIn(0.0f, 1.0f) else 0.0f
	}
}
