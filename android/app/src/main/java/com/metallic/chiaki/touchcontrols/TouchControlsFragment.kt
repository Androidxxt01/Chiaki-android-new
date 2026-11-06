// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.touchcontrols

import android.os.Bundle
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.metallic.chiaki.R
import com.metallic.chiaki.databinding.FragmentControlsBinding
import com.metallic.chiaki.lib.ControllerState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlin.math.sqrt

private const val MIN_SCALE = 0.5f
private const val MAX_SCALE = 2.5f

private class ControlSelectionOverlay(context: android.content.Context) : View(context)
{
	var selected: View? = null
		set(value) { field = value; invalidate() }
	var onResize: ((View, Float, Float, Boolean) -> Unit)? = null
	private var resizing: View? = null
	private var startX = 0f
	private var startY = 0f
	private var startWidth = 0f
	private var startHeight = 0f
	private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f }
	private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6338DFF.toInt(); style = Paint.Style.FILL }

	override fun onTouchEvent(event: MotionEvent): Boolean {
		val target = resizing ?: selected
		if (event.actionMasked == MotionEvent.ACTION_DOWN) {
			val candidate = selected ?: return false
			val rootLocation = IntArray(2); val targetLocation = IntArray(2)
			getLocationOnScreen(rootLocation); candidate.getLocationOnScreen(targetLocation)
			val right = (targetLocation[0] - rootLocation[0] + candidate.width).toFloat()
			val bottom = (targetLocation[1] - rootLocation[1] + candidate.height).toFloat()
			val touchSize = 48f * resources.displayMetrics.density
			if (event.x !in (right - touchSize)..right || event.y !in (bottom - touchSize)..bottom) return false
			resizing = candidate
			startX = event.rawX; startY = event.rawY
			startWidth = candidate.width.toFloat(); startHeight = candidate.height.toFloat()
			(candidate.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(true)
			return true
		}
		if (target == null) return false
		when (event.actionMasked) {
			MotionEvent.ACTION_MOVE -> onResize?.invoke(target, startWidth + event.rawX - startX, startHeight + event.rawY - startY, false)
			MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
				onResize?.invoke(target, target.width.toFloat(), target.height.toFloat(), true)
				(target.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(false)
				resizing = null
			}
		}
		return true
	}

	override fun onDraw(canvas: Canvas) {
		super.onDraw(canvas)
		val target = selected ?: return
		if (!target.isShown || width == 0 || height == 0) return
		val rootLocation = IntArray(2); val targetLocation = IntArray(2)
		getLocationOnScreen(rootLocation); target.getLocationOnScreen(targetLocation)
		val left = (targetLocation[0] - rootLocation[0]).toFloat()
		val top = (targetLocation[1] - rootLocation[1]).toFloat()
		val right = left + target.width; val bottom = top + target.height
		canvas.drawRect(left, top, right, bottom, outline)
		val handle = 24f * resources.displayMetrics.density
		canvas.drawCircle(right - handle / 2f, bottom - handle / 2f, handle / 2f, fill)
		canvas.drawLine(right - handle * .72f, bottom - handle * .28f, right - handle * .28f, bottom - handle * .72f, outline)
	}
}

abstract class TouchControlsFragment : Fragment()
{
	protected var ownControllerState = ControllerState()
		set(value)
		{
			val diff = field != value
			field = value
			if(diff)
				_ownControllerStateFlow.value = ownControllerState
		}

	protected val _ownControllerStateFlow = MutableStateFlow(ControllerState())

	// Proxy to delay attaching to the touchpadView until it's available.
	// Starts emitting ownControllerState, then switches to combined flow when touchpad is ready.
	protected val _controllerStateSource = MutableStateFlow<Flow<ControllerState>>(_ownControllerStateFlow)

	@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
	val controllerState: Flow<ControllerState> get() =
		_controllerStateSource.flatMapLatest { it }

	var onScreenControlsEnabled: LiveData<Boolean>? = null
}

class DefaultTouchControlsFragment : TouchControlsFragment()
{
	private var _binding: FragmentControlsBinding? = null
	private val binding get() = _binding!!
	private val layoutStore by lazy { ControlLayoutStore(requireContext()) }
	private val controlViews by lazy { listOf(
		"dpad" to binding.dpadView, "cross" to binding.crossButtonView, "circle" to binding.moonButtonView,
		"triangle" to binding.pyramidButtonView, "square" to binding.boxButtonView, "l1" to binding.l1ButtonView,
		"l2" to binding.l2ButtonView, "r1" to binding.r1ButtonView, "r2" to binding.r2ButtonView,
		"l3" to binding.l3ButtonView, "r3" to binding.r3ButtonView, "options" to binding.optionsButtonView,
		"share" to binding.shareButtonView, "ps" to binding.psButtonView, "touchpad" to binding.touchpadView,
		"left_stick" to binding.leftAnalogStickView, "right_stick" to binding.rightAnalogStickView
	) }
	private var profiles = mutableListOf<ControlLayoutProfile>()
	private var activeProfile: ControlLayoutProfile? = null
	private var editBar: LinearLayout? = null
	private var selectedBar: LinearLayout? = null
	private var globalOpacityControl: SeekBar? = null
	private var selectedControlId: String? = null
	private var selectionOverlay: ControlSelectionOverlay? = null
	private var editing = false
	private val defaultGeometry = mutableMapOf<String, ControlLayoutItem>()

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
		FragmentControlsBinding.inflate(inflater, container, false).let {
			_binding = it
			_controllerStateSource.value =
				combine(_ownControllerStateFlow, binding.touchpadView.controllerState) { a, b -> a or b }
			it.root
		}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?)
	{
		super.onViewCreated(view, savedInstanceState)
		binding.dpadView.stateChangeCallback = this::dpadStateChanged
		binding.crossButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_CROSS)
		binding.moonButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_MOON)
		binding.pyramidButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_PYRAMID)
		binding.boxButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_BOX)
		binding.l1ButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_L1)
		binding.r1ButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_R1)
		binding.l3ButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_L3)
		binding.r3ButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_R3)
		binding.optionsButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_OPTIONS)
		binding.shareButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_SHARE)
		binding.psButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_PS)
		installSafeAreaInsets(view)
		installSelectionOverlay()
		installEditorGestures()
		loadSavedLayout()

		binding.l2ButtonView.buttonPressedCallback = { ownControllerState = ownControllerState.copy().apply { l2State = if(it) 255U else 0U } }
		binding.r2ButtonView.buttonPressedCallback = { ownControllerState = ownControllerState.copy().apply { r2State = if(it) 255U else 0U } }

		val quantizeStick = { f: Float ->
			(Short.MAX_VALUE * f).toInt().toShort()
		}

		binding.leftAnalogStickView.stateChangedCallback = { ownControllerState = ownControllerState.copy().apply {
			leftX = quantizeStick(it.x)
			leftY = quantizeStick(it.y)
		}}

		binding.rightAnalogStickView.stateChangedCallback = { ownControllerState = ownControllerState.copy().apply {
			rightX = quantizeStick(it.x)
			rightY = quantizeStick(it.y)
		}}

		onScreenControlsEnabled?.observe(viewLifecycleOwner, Observer {
			view.visibility = if(it) View.VISIBLE else View.GONE
		})
	}

	/** Opens the layout picker/editor from the stream overlay. */
	fun openControlLayoutEditor() {
		if (!isAdded) return
		openProfilePicker()
	}

	private fun openProfilePicker() {
		val names = profiles.map { it.name }.toMutableList()
		val options = mutableListOf("Editar perfil atual", "Criar perfil…", "Restaurar padrão")
		options.addAll(names.map { "Usar: $it" })
		val dialog = AlertDialog.Builder(requireContext()).setTitle("Layouts dos controles").setItems(options.toTypedArray()) { _, which ->
			when {
				which == 0 -> enterEditMode()
				which == 1 -> createProfile()
				which == 2 -> restoreDefault()
				else -> { activeProfile = profiles.getOrNull(which - 3); applyLayout(); saveLayout(); enterEditMode() }
			}
		}.create()
		dialog.setOnDismissListener {
			if (editing) {
					globalOpacityControl?.progress = activeProfile?.opacity ?: 75
				showEditBar()
					selectedControlId?.let { showSelectedPanel() }
				editBar?.bringToFront()
				selectedBar?.bringToFront()
				binding.root.requestLayout()
			}
		}
		dialog.show()
	}

	private fun loadSavedLayout() {
		profiles = layoutStore.profiles()
		if (profiles.none { it.name == "Padrão" }) profiles.add(ControlLayoutProfile("Padrão"))
		activeProfile = profiles.firstOrNull { it.name == layoutStore.activeName() } ?: profiles.first { it.name == "Padrão" }
		view?.post {
			controlViews.forEach { (key, control) -> defaultGeometry[key] = ControlLayoutItem(
				((rootX(control) - binding.root.paddingLeft) / usableWidth()).coerceIn(0f, 1f),
				((rootY(control) - binding.root.paddingTop) / usableHeight()).coerceIn(0f, 1f),
				(control.width.toFloat() / usableWidth()).coerceIn(.02f, 1f), (control.height.toFloat() / usableHeight()).coerceIn(.02f, 1f)) }
			val default = profiles.first { it.name == "Padrão" }
			if (default.items.isEmpty()) default.items.putAll(defaultGeometry)
			profiles.forEach { profile ->
				controlViews.forEach { (key, _) ->
					val saved = profile.items[key] ?: defaultGeometry[key]?.copy()?.also { profile.items[key] = it }
					if (saved != null) normalizeSavedGeometry(key, saved)
				}
			}
			applyLayout()
			layoutStore.save(profiles, activeProfile?.name ?: "Padrão")
		}
	}

	private fun createProfile() {
		val input = EditText(requireContext()).apply { hint = "Nome do perfil" }
		AlertDialog.Builder(requireContext()).setTitle("Novo layout").setView(input)
			.setNegativeButton("Cancelar", null).setPositiveButton("Criar") { _, _ ->
				val name = input.text.toString().trim().ifBlank { "Layout ${profiles.size + 1}" }
				val base = activeProfile ?: ControlLayoutProfile("Padrão")
				activeProfile = ControlLayoutProfile(name, base.opacity, base.items.mapValues { it.value.copy() }.toMutableMap())
				profiles.removeAll { it.name == name }; profiles.add(activeProfile!!); saveLayout(); enterEditMode()
			}.show()
	}

	private fun restoreDefault() {
		val default = profiles.firstOrNull { it.name == "Padrão" } ?: ControlLayoutProfile("Padrão").also { profiles.add(it) }
		default.items.clear(); default.items.putAll(defaultGeometry.mapValues { it.value.copy(opacity = 100) }); default.opacity = 75
		activeProfile = default; applyLayout(); saveLayout(); Toast.makeText(requireContext(), "Layout padrão restaurado", Toast.LENGTH_SHORT).show()
		selectedControlId?.let { key -> controlViews.firstOrNull { it.first == key }?.let { selectControl(key, it.second) } }
	}

	fun enterEditMode() {
		if (editing) return
		if (binding.root.width == 0 || binding.root.height == 0) {
			binding.root.post { enterEditMode() }
			return
		}
		editing = true
		Toast.makeText(requireContext(), "Arraste os controles; redimensione pelo canto inferior direito.", Toast.LENGTH_LONG).show()
		controlViews.forEach { (key, control) ->
			control.setOnTouchListener { target, event ->
				if (!editing) return@setOnTouchListener false
				when (event.actionMasked) {
					MotionEvent.ACTION_DOWN -> {
						(target.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(true)
						selectControl(key, target)
						val handleSize = dp(48).toFloat()
						val resize = event.x >= target.width - handleSize && event.y >= target.height - handleSize
						target.setTag(R.id.virtual_control_edit_touch, floatArrayOf(event.rawX, event.rawY, rootX(target), rootY(target), target.width.toFloat(), target.height.toFloat(), if (resize) 1f else 0f))
						true
					}
					MotionEvent.ACTION_MOVE -> {
						val data = target.getTag(R.id.virtual_control_edit_touch) as? FloatArray ?: return@setOnTouchListener true
						val dx = event.rawX - data[0]; val dy = event.rawY - data[1]
						if (data[6] == 1f) {
							resizeControl(key, target, data[4] + dx, data[5] + dy)
						} else {
							setRootPosition(target, (data[2] + dx).coerceIn(binding.root.paddingLeft.toFloat(), (binding.root.width - binding.root.paddingRight - target.width).toFloat().coerceAtLeast(0f)), (data[3] + dy).coerceIn(binding.root.paddingTop.toFloat(), (binding.root.height - binding.root.paddingBottom - target.height).toFloat().coerceAtLeast(0f)))
						}
						selectionOverlay?.invalidate()
						true
					}
					MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
						val data = target.getTag(R.id.virtual_control_edit_touch) as? FloatArray
						savePositions(preserveSavedSizes = data?.getOrNull(6) == 1f)
						showSelectedPanel()
						(target.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(false)
						true
					}
					else -> true
				}
			}
			control.alpha = (activeProfile?.opacity ?: 75) / 100f * ((activeProfile?.items?.get(key)?.opacity ?: 100) / 100f)
		}
		showEditBar()
	}

	private fun showEditBar() {
		if (editBar != null) return
		val bar = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(4), dp(8), dp(4)); setBackgroundColor(0xCC202020.toInt()); gravity = android.view.Gravity.CENTER_VERTICAL }
		val profileButton = Button(requireContext()).apply { text = getString(R.string.touch_editor_profiles); setOnClickListener { openProfilePicker() } }
		val opacity = SeekBar(requireContext()).apply { max = 100; progress = activeProfile?.opacity ?: 75; setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
			override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) { activeProfile?.opacity = value.coerceAtLeast(10); controlViews.forEach { (key, control) -> control.alpha = (activeProfile?.opacity ?: 75) / 100f * ((activeProfile?.items?.get(key)?.opacity ?: 100) / 100f) } }
			override fun onStartTrackingTouch(seekBar: SeekBar?) {}
			override fun onStopTrackingTouch(seekBar: SeekBar?) { saveLayout() }
		}) }
		globalOpacityControl = opacity
		val restore = Button(requireContext()).apply { text = getString(R.string.touch_editor_default); setOnClickListener { restoreDefault() } }
		val done = Button(requireContext()).apply { text = getString(R.string.touch_editor_done); setOnClickListener { savePositions(); editing = false; selectedControlId = null; selectionOverlay?.selected = null; selectedBar?.let { (binding.root as ViewGroup).removeView(it) }; selectedBar = null; controlViews.forEach { it.second.setOnTouchListener(null) }; (binding.root as ViewGroup).removeView(bar); editBar = null; (activity as? androidx.appcompat.app.AppCompatActivity)?.finish() } }
		bar.addView(profileButton); bar.addView(opacity, LinearLayout.LayoutParams(dp(130), dp(48))); bar.addView(restore); bar.addView(done)
		val lp = ConstraintLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topToTop = ConstraintLayout.LayoutParams.PARENT_ID; startToStart = ConstraintLayout.LayoutParams.PARENT_ID; endToEnd = ConstraintLayout.LayoutParams.PARENT_ID; topMargin = dp(12) }
		(binding.root as ViewGroup).addView(bar, lp); editBar = bar
	}

	private fun showSelectedPanel() {
		selectedBar?.let { (binding.root as ViewGroup).removeView(it) }
		val key = selectedControlId ?: return
		val profile = activeProfile ?: return
		val item = profile.items[key] ?: return
		val panel = LinearLayout(requireContext()).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = android.view.Gravity.CENTER_VERTICAL
			setPadding(dp(8), dp(4), dp(8), dp(4))
			setBackgroundColor(0xCC202020.toInt())
		}
		panel.addView(TextView(requireContext()).apply { text = getString(R.string.touch_editor_selected, key); setTextColor(Color.WHITE) })
		val defaultItem = defaultGeometry[key] ?: item
		val scaleProgress = scaleFor(key, item).let { ((it - MIN_SCALE) / (maxScaleFor(key) - MIN_SCALE).coerceAtLeast(.01f) * 100).toInt().coerceIn(0, 100) }
		val sizeControl = SeekBar(requireContext()).apply {
			max = 100; progress = scaleProgress
			contentDescription = getString(R.string.touch_editor_size)
			setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
				override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
					if (!fromUser) return
					val scale = MIN_SCALE + (maxScaleFor(key) - MIN_SCALE).coerceAtLeast(.01f) * value / 100f
					setScale(key, item, defaultItem, scale)
					applyLayout(); saveLayout(); selectionOverlay?.invalidate()
				}
				override fun onStartTrackingTouch(seekBar: SeekBar?) {}
				override fun onStopTrackingTouch(seekBar: SeekBar?) {}
			})
		}
		panel.addView(TextView(requireContext()).apply { text = getString(R.string.touch_editor_size) })
		panel.addView(sizeControl, LinearLayout.LayoutParams(dp(150), dp(48)))
		val opacityControl = SeekBar(requireContext()).apply {
			max = 90; progress = item.opacity - 10
			contentDescription = getString(R.string.touch_editor_button_opacity)
			setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
				override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
					if (!fromUser) return
					item.opacity = value + 10
					applyLayout(); saveLayout()
				}
				override fun onStartTrackingTouch(seekBar: SeekBar?) {}
				override fun onStopTrackingTouch(seekBar: SeekBar?) {}
			})
		}
		panel.addView(TextView(requireContext()).apply { text = getString(R.string.touch_editor_button_opacity) })
		panel.addView(opacityControl, LinearLayout.LayoutParams(dp(120), dp(48)))
		panel.addView(Button(requireContext()).apply {
			text = getString(R.string.touch_editor_reset_button)
			setOnClickListener {
				defaultGeometry[key]?.copy()?.let { profile.items[key] = it }
				applyLayout(); saveLayout(); selectControl(key, controlViews.first { it.first == key }.second)
			}
		})
		val lp = ConstraintLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
			topToTop = ConstraintLayout.LayoutParams.PARENT_ID
			startToStart = ConstraintLayout.LayoutParams.PARENT_ID
			endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
			topMargin = dp(64)
		}
		(binding.root as ViewGroup).addView(panel, lp)
		selectedBar = panel
	}

	private fun applyLayout() {
		val profile = activeProfile ?: return
		if (binding.root.width == 0 || binding.root.height == 0) { binding.root.post { applyLayout() }; return }
		controlViews.forEach { (key, control) ->
			val item = profile.items[key]
			control.alpha = profile.opacity / 100f * ((item?.opacity ?: 100) / 100f)
			if (item == null) return@forEach
			normalizeSavedGeometry(key, item)
			val w = (item.width * usableWidth()).toInt().coerceIn(1, usableWidth().toInt())
			val h = (item.height * usableHeight()).toInt().coerceIn(1, usableHeight().toInt())
			control.layoutParams = control.layoutParams.apply { width = w; height = h }
			control.post {
				setClampedPosition(control, binding.root.paddingLeft + item.x * usableWidth(), binding.root.paddingTop + item.y * usableHeight())
				control.alpha = profile.opacity / 100f * (item.opacity / 100f)
				selectionOverlay?.invalidate()
			}
		}
	}

	private fun savePositions(preserveSavedSizes: Boolean = false) {
		val profile = activeProfile ?: return
		controlViews.forEach { (key, control) ->
			val previous = profile.items[key]
			profile.items[key] = ControlLayoutItem(
				((rootX(control) - binding.root.paddingLeft) / usableWidth()).coerceIn(0f, 1f),
				((rootY(control) - binding.root.paddingTop) / usableHeight()).coerceIn(0f, 1f),
				if (preserveSavedSizes && previous != null) previous.width else (control.width.toFloat() / usableWidth()).coerceIn(.02f, 1f),
				if (preserveSavedSizes && previous != null) previous.height else (control.height.toFloat() / usableHeight()).coerceIn(.02f, 1f), previous?.opacity ?: 100
			)
			normalizeSavedGeometry(key, profile.items.getValue(key))
		}
		saveLayout()
	}

	private fun normalizeSavedGeometry(key: String, item: ControlLayoutItem) {
		val baseline = defaultGeometry[key] ?: return
		if (baseline.width <= 0f || baseline.height <= 0f) return
		if (hasIndependentAspect(key)) {
			item.width = item.width.coerceIn(baseline.width * MIN_SCALE, minOf(1f, baseline.width * MAX_SCALE))
			item.height = item.height.coerceIn(baseline.height * MIN_SCALE, minOf(1f, baseline.height * MAX_SCALE))
		} else {
			val scale = sqrt(((item.width / baseline.width) * (item.height / baseline.height)).coerceAtLeast(0f)).coerceIn(MIN_SCALE, maxScaleFor(key))
			item.width = baseline.width * scale
			item.height = baseline.height * scale
		}
		item.x = item.x.coerceIn(0f, (1f - item.width).coerceAtLeast(0f))
		item.y = item.y.coerceIn(0f, (1f - item.height).coerceAtLeast(0f))
		item.opacity = item.opacity.coerceIn(10, 100)
	}

	private fun hasIndependentAspect(key: String) = key in setOf("l1", "l2", "r1", "r2")
	private fun maxScaleFor(key: String): Float {
		val base = defaultGeometry[key] ?: return MAX_SCALE
		return minOf(MAX_SCALE, 1f / base.width.coerceAtLeast(.001f), 1f / base.height.coerceAtLeast(.001f)).coerceAtLeast(MIN_SCALE)
	}
	private fun scaleFor(key: String, item: ControlLayoutItem): Float {
		val base = defaultGeometry[key] ?: return 1f
		return sqrt(((item.width / base.width) * (item.height / base.height)).coerceAtLeast(0f)).coerceIn(MIN_SCALE, maxScaleFor(key))
	}
	private fun setScale(key: String, item: ControlLayoutItem, base: ControlLayoutItem, scale: Float) {
		val bounded = scale.coerceIn(MIN_SCALE, maxScaleFor(key))
		item.width = base.width * bounded
		item.height = base.height * bounded
	}
	private fun resizeControl(key: String, target: View, width: Float, height: Float) {
		val baseline = defaultGeometry[key] ?: return
		val profile = activeProfile ?: return
		val item = profile.items[key] ?: return
		val newWidth: Int
		val newHeight: Int
		if (hasIndependentAspect(key)) {
			newWidth = width.toInt().coerceIn((baseline.width * usableWidth() * MIN_SCALE).toInt().coerceAtLeast(1), (baseline.width * usableWidth() * MAX_SCALE).toInt().coerceAtMost(usableWidth().toInt()))
			newHeight = height.toInt().coerceIn((baseline.height * usableHeight() * MIN_SCALE).toInt().coerceAtLeast(1), (baseline.height * usableHeight() * MAX_SCALE).toInt().coerceAtMost(usableHeight().toInt()))
		} else {
			val widthScale = width / (baseline.width * usableWidth()).coerceAtLeast(1f)
			val heightScale = height / (baseline.height * usableHeight()).coerceAtLeast(1f)
			val scale = maxOf(widthScale, heightScale).coerceIn(MIN_SCALE, maxScaleFor(key))
			newWidth = (baseline.width * usableWidth() * scale).toInt().coerceIn(1, usableWidth().toInt())
			newHeight = (baseline.height * usableHeight() * scale).toInt().coerceIn(1, usableHeight().toInt())
		}
		target.layoutParams = target.layoutParams.apply { this.width = newWidth; this.height = newHeight }
		item.width = newWidth / usableWidth(); item.height = newHeight / usableHeight()
		val maxX = (binding.root.width - binding.root.paddingRight - newWidth).coerceAtLeast(binding.root.paddingLeft).toFloat()
		val maxY = (binding.root.height - binding.root.paddingBottom - newHeight).coerceAtLeast(binding.root.paddingTop).toFloat()
		setClampedPosition(target, rootX(target).coerceIn(binding.root.paddingLeft.toFloat(), maxX), rootY(target).coerceIn(binding.root.paddingTop.toFloat(), maxY))
	}
	private fun saveLayout() { activeProfile?.let { layoutStore.save(profiles, it.name) } }
	private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
	private fun usableWidth() = (binding.root.width - binding.root.paddingLeft - binding.root.paddingRight).coerceAtLeast(1).toFloat()
	private fun usableHeight() = (binding.root.height - binding.root.paddingTop - binding.root.paddingBottom).coerceAtLeast(1).toFloat()
	private fun rootX(view: View): Float { val p = IntArray(2); val r = IntArray(2); view.getLocationOnScreen(p); binding.root.getLocationOnScreen(r); return (p[0] - r[0]).toFloat() }
	private fun rootY(view: View): Float { val p = IntArray(2); val r = IntArray(2); view.getLocationOnScreen(p); binding.root.getLocationOnScreen(r); return (p[1] - r[1]).toFloat() }
	private fun setRootPosition(view: View, x: Float, y: Float) { view.translationX += x - rootX(view); view.translationY += y - rootY(view) }
	private fun installEditorGestures() { controlViews.forEach { (_, control) -> control.isLongClickable = true } }
	private fun installSelectionOverlay() {
		val overlay = ControlSelectionOverlay(requireContext()).apply {
			isClickable = true
			importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
			onResize = { target, width, height, finished ->
				val key = controlViews.firstOrNull { it.second === target }?.first
				if (key != null) {
					if (finished) {
						savePositions(preserveSavedSizes = true)
						showSelectedPanel()
					} else resizeControl(key, target, width, height)
				}
				invalidate()
			}
		}
		val lp = ConstraintLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
			leftToLeft = ConstraintLayout.LayoutParams.PARENT_ID; rightToRight = ConstraintLayout.LayoutParams.PARENT_ID
			topToTop = ConstraintLayout.LayoutParams.PARENT_ID; bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
		}
		binding.root.addView(overlay, lp)
		selectionOverlay = overlay
	}
	private fun selectControl(key: String, target: View) {
		selectedControlId = key
		selectionOverlay?.selected = target
		if (editing) showSelectedPanel()
	}
	private fun setClampedPosition(target: View, x: Float, y: Float) {
		val maxX = (binding.root.width - binding.root.paddingRight - target.width).coerceAtLeast(binding.root.paddingLeft).toFloat()
		val maxY = (binding.root.height - binding.root.paddingBottom - target.height).coerceAtLeast(binding.root.paddingTop).toFloat()
		setRootPosition(target, x.coerceIn(binding.root.paddingLeft.toFloat(), maxX), y.coerceIn(binding.root.paddingTop.toFloat(), maxY))
	}
	private fun installSafeAreaInsets(root: View) {
		ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
			val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
			v.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
		}
		ViewCompat.requestApplyInsets(root)
	}

	private fun dpadStateChanged(direction: DPadView.Direction?)
	{
		ownControllerState = ownControllerState.copy().apply {
			buttons = ((buttons
						and ControllerState.BUTTON_DPAD_LEFT.inv()
						and ControllerState.BUTTON_DPAD_RIGHT.inv()
						and ControllerState.BUTTON_DPAD_UP.inv()
						and ControllerState.BUTTON_DPAD_DOWN.inv())
					or when(direction)
					{
						DPadView.Direction.UP -> ControllerState.BUTTON_DPAD_UP
						DPadView.Direction.DOWN -> ControllerState.BUTTON_DPAD_DOWN
						DPadView.Direction.LEFT -> ControllerState.BUTTON_DPAD_LEFT
						DPadView.Direction.RIGHT -> ControllerState.BUTTON_DPAD_RIGHT
						DPadView.Direction.LEFT_UP -> ControllerState.BUTTON_DPAD_LEFT or ControllerState.BUTTON_DPAD_UP
						DPadView.Direction.LEFT_DOWN -> ControllerState.BUTTON_DPAD_LEFT or ControllerState.BUTTON_DPAD_DOWN
						DPadView.Direction.RIGHT_UP -> ControllerState.BUTTON_DPAD_RIGHT or ControllerState.BUTTON_DPAD_UP
						DPadView.Direction.RIGHT_DOWN -> ControllerState.BUTTON_DPAD_RIGHT or ControllerState.BUTTON_DPAD_DOWN
						null -> 0U
					})
		}
	}

	private fun buttonStateChanged(buttonMask: UInt) = { pressed: Boolean ->
		ownControllerState = ownControllerState.copy().apply {
			buttons =
				if(pressed)
					buttons or buttonMask
				else
					buttons and buttonMask.inv()

		}
	}
}
