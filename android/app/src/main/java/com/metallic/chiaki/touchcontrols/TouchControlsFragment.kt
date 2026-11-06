// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.touchcontrols

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
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
		val names = profiles.map { it.name }.toMutableList()
		val options = mutableListOf("Editar perfil atual", "Criar perfil…", "Restaurar padrão")
		options.addAll(names.map { "Usar: $it" })
		AlertDialog.Builder(requireContext()).setTitle("Layouts dos controles").setItems(options.toTypedArray()) { _, which ->
			when {
				which == 0 -> enterEditMode()
				which == 1 -> createProfile()
				which == 2 -> restoreDefault()
				else -> { activeProfile = profiles.getOrNull(which - 3); applyLayout(); enterEditMode() }
			}
		}.show()
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
			applyLayout()
		}
	}

	private fun createProfile() {
		val input = EditText(requireContext()).apply { hint = "Nome do perfil" }
		AlertDialog.Builder(requireContext()).setTitle("Novo layout").setView(input)
			.setNegativeButton("Cancelar", null).setPositiveButton("Criar") { _, _ ->
				val name = input.text.toString().trim().ifBlank { "Layout ${profiles.size + 1}" }
				val base = activeProfile ?: ControlLayoutProfile("Padrão")
				activeProfile = ControlLayoutProfile(name, base.opacity, base.items.toMutableMap())
				profiles.removeAll { it.name == name }; profiles.add(activeProfile!!); saveLayout(); enterEditMode()
			}.show()
	}

	private fun restoreDefault() {
		val default = profiles.firstOrNull { it.name == "Padrão" } ?: ControlLayoutProfile("Padrão").also { profiles.add(it) }
		default.items.clear(); default.items.putAll(defaultGeometry); default.opacity = 75
		activeProfile = default; applyLayout(); saveLayout(); Toast.makeText(requireContext(), "Layout padrão restaurado", Toast.LENGTH_SHORT).show()
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
						target.setTag(R.id.virtual_control_edit_touch, floatArrayOf(event.rawX, event.rawY, rootX(target), rootY(target), target.width.toFloat(), target.height.toFloat(), if (event.x > target.width * .72f && event.y > target.height * .72f) 1f else 0f))
						true
					}
					MotionEvent.ACTION_MOVE -> {
						val data = target.getTag(R.id.virtual_control_edit_touch) as? FloatArray ?: return@setOnTouchListener true
						val dx = event.rawX - data[0]; val dy = event.rawY - data[1]
						if (data[6] == 1f) {
							val lp = target.layoutParams; lp.width = (data[4] + dx).toInt().coerceAtLeast(dp(36)); lp.height = (data[5] + dy).toInt().coerceAtLeast(dp(36)); target.layoutParams = lp
						} else { setRootPosition(target, (data[2] + dx).coerceIn(binding.root.paddingLeft.toFloat(), (binding.root.width - binding.root.paddingRight - target.width).toFloat().coerceAtLeast(0f)), (data[3] + dy).coerceIn(binding.root.paddingTop.toFloat(), (binding.root.height - binding.root.paddingBottom - target.height).toFloat().coerceAtLeast(0f))) }
						true
					}
					MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
						savePositions()
						(target.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(false)
						true
					}
					else -> true
				}
			}
			control.alpha = (activeProfile?.opacity ?: 75) / 100f
		}
		showEditBar()
	}

	private fun showEditBar() {
		if (editBar != null) return
		val bar = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(4), dp(8), dp(4)); setBackgroundColor(0xCC202020.toInt()); gravity = android.view.Gravity.CENTER_VERTICAL }
		val profileButton = Button(requireContext()).apply { text = "Perfis"; setOnClickListener { editing = false; controlViews.forEach { it.second.setOnTouchListener(null) }; editBar = null; (binding.root as ViewGroup).removeView(bar); openControlLayoutEditor() } }
		val opacity = SeekBar(requireContext()).apply { max = 100; progress = activeProfile?.opacity ?: 75; setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
			override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) { activeProfile?.opacity = value.coerceAtLeast(10); controlViews.forEach { it.second.alpha = (activeProfile?.opacity ?: 75) / 100f } }
			override fun onStartTrackingTouch(seekBar: SeekBar?) {}
			override fun onStopTrackingTouch(seekBar: SeekBar?) { saveLayout() }
		}) }
		val restore = Button(requireContext()).apply { text = "Padrão"; setOnClickListener { restoreDefault() } }
		val done = Button(requireContext()).apply { text = "Concluir"; setOnClickListener { savePositions(); editing = false; controlViews.forEach { it.second.setOnTouchListener(null) }; (binding.root as ViewGroup).removeView(bar); editBar = null; (activity as? androidx.appcompat.app.AppCompatActivity)?.finish() } }
		bar.addView(profileButton); bar.addView(opacity, LinearLayout.LayoutParams(dp(130), dp(48))); bar.addView(restore); bar.addView(done)
		val lp = ConstraintLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topToTop = ConstraintLayout.LayoutParams.PARENT_ID; startToStart = ConstraintLayout.LayoutParams.PARENT_ID; endToEnd = ConstraintLayout.LayoutParams.PARENT_ID; topMargin = dp(12) }
		(binding.root as ViewGroup).addView(bar, lp); editBar = bar
	}

	private fun applyLayout() {
		val profile = activeProfile ?: return
		if (binding.root.width == 0 || binding.root.height == 0) { binding.root.post { applyLayout() }; return }
		controlViews.forEach { (key, control) ->
			control.alpha = profile.opacity / 100f
			val item = profile.items[key]
			if (item == null) return@forEach
			val w = (item.width * binding.root.width).toInt().coerceIn(dp(36), binding.root.width)
			val h = (item.height * binding.root.height).toInt().coerceIn(dp(36), binding.root.height)
			control.layoutParams = control.layoutParams.apply { width = w; height = h }
			control.post { setRootPosition(control, binding.root.paddingLeft + item.x * usableWidth(), binding.root.paddingTop + item.y * usableHeight()); control.alpha = profile.opacity / 100f }
		}
	}

	private fun savePositions() {
		val profile = activeProfile ?: return
		controlViews.forEach { (key, control) -> profile.items[key] = ControlLayoutItem(
			((rootX(control) - binding.root.paddingLeft) / usableWidth()).coerceIn(0f, 1f), ((rootY(control) - binding.root.paddingTop) / usableHeight()).coerceIn(0f, 1f),
			(control.width.toFloat() / usableWidth()).coerceIn(.02f, 1f), (control.height.toFloat() / usableHeight()).coerceIn(.02f, 1f)) }
		saveLayout()
	}
	private fun saveLayout() { activeProfile?.let { layoutStore.save(profiles, it.name) } }
	private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
	private fun usableWidth() = (binding.root.width - binding.root.paddingLeft - binding.root.paddingRight).coerceAtLeast(1).toFloat()
	private fun usableHeight() = (binding.root.height - binding.root.paddingTop - binding.root.paddingBottom).coerceAtLeast(1).toFloat()
	private fun rootX(view: View): Float { val p = IntArray(2); val r = IntArray(2); view.getLocationOnScreen(p); binding.root.getLocationOnScreen(r); return (p[0] - r[0]).toFloat() }
	private fun rootY(view: View): Float { val p = IntArray(2); val r = IntArray(2); view.getLocationOnScreen(p); binding.root.getLocationOnScreen(r); return (p[1] - r[1]).toFloat() }
	private fun setRootPosition(view: View, x: Float, y: Float) { view.translationX += x - rootX(view); view.translationY += y - rootY(view) }
	private fun installEditorGestures() { controlViews.forEach { (_, control) -> control.isLongClickable = true } }
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
