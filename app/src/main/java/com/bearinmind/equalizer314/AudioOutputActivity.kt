package com.bearinmind.equalizer314

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.bearinmind.equalizer314.audio.AudioRoutingMonitor
import com.bearinmind.equalizer314.audio.DeviceIdentity
import com.bearinmind.equalizer314.audio.EqService
import com.bearinmind.equalizer314.state.EqPreferencesManager
import com.bearinmind.equalizer314.ui.NotchedDeviceCardView
import com.bearinmind.equalizer314.ui.PresetDropdownAdapter
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.TextInputLayout
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject

/**
 * Detail screen of the "Audio Output" pipeline card — per-device EQ binding: shows the currently
 * routed output, every device the app has seen, and lets the user assign saved presets to each.
 */
class AudioOutputActivity : AppCompatActivity() {

    private lateinit var eqPrefs: EqPreferencesManager
    private lateinit var devicesList: RecyclerView
    private lateinit var emptyState: TextView
    private lateinit var devicesAdapter: DevicesAdapter
    private lateinit var dragTouchHelper: ItemTouchHelper
    private lateinit var activeDeviceLabel: TextView
    private lateinit var activeDeviceKey: TextView
    private lateinit var currentlyRoutedCard: MaterialCardView
    private lateinit var deviceAutoSwitchCard: MaterialCardView
    private lateinit var deviceAutoSwitchSwitch: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var deviceAutoSwitchBody: TextView
    private lateinit var devicesHeader: LinearLayout
    private lateinit var devicesBody: LinearLayout
    private lateinit var devicesChevron: TextView
    private lateinit var currentDeviceDropdown: MaterialAutoCompleteTextView
    private lateinit var currentDeviceDropdownLayout: TextInputLayout
    private var devicesExpanded = true
    // Suppresses the click-handler's reopen when a popup auto-dismisses via outside-tap on the
    // dropdown box — the same touch that dismisses also fires our click handler.
    private var currentDeviceLastDismissAt = 0L

    private var eqService: EqService? = null
    private var serviceBound = false
    private var activeKey: String? = null
    private var activeLabel: String? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? EqService.EqBinder ?: return
            eqService = binder.service
            serviceBound = true
            refreshActiveDevice()
            // Subscribe to live route changes so the screen updates as
            // the user plugs / unplugs devices while it's open.
            binder.service.routingMonitor?.let { monitor ->
                val previous = monitor.onRouteChange
                monitor.onRouteChange = { change ->
                    previous?.invoke(change)
                    runOnUiThread {
                        activeKey = change.key
                        activeLabel = change.label
                        refreshActiveDevice()
                        refreshDevices()
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            eqService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_audio_output)

        eqPrefs = EqPreferencesManager(this)

        findViewById<ImageButton>(R.id.audioOutputBackButton).setOnClickListener { finish() }

        // Device auto-switch toggle — twin of Channel Input's Session detection card. Flips the
        // persisted flag synchronously so the next route change applies the bound preset (on) or no-ops (off).
        deviceAutoSwitchCard = findViewById(R.id.deviceAutoSwitchCard)
        deviceAutoSwitchSwitch = findViewById(R.id.deviceAutoSwitchSwitch)
        deviceAutoSwitchBody = findViewById(R.id.deviceAutoSwitchBody)
        val toggleAutoSwitch = {
            val next = !eqPrefs.getDeviceAutoSwitchEnabled()
            eqPrefs.setDeviceAutoSwitchEnabled(next)
            refreshDeviceAutoSwitchUi()
        }
        deviceAutoSwitchSwitch.setOnClickListener { toggleAutoSwitch() }
        deviceAutoSwitchCard.setOnClickListener { toggleAutoSwitch() }

        currentlyRoutedCard = findViewById(R.id.currentlyRoutedCard)
        activeDeviceLabel = findViewById(R.id.activeDeviceLabel)
        activeDeviceKey = findViewById(R.id.activeDeviceKey)
        devicesList = findViewById(R.id.devicesList)
        emptyState = findViewById(R.id.devicesEmptyState)

        // RecyclerView setup + ItemTouchHelper-driven drag-to-reorder.
        devicesAdapter = DevicesAdapter()
        devicesList.layoutManager = LinearLayoutManager(this)
        devicesList.adapter = devicesAdapter
        devicesList.isNestedScrollingEnabled = false
        dragTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0,
        ) {
            override fun isLongPressDragEnabled() = false
            override fun isItemViewSwipeEnabled() = false

            override fun onMove(
                rv: RecyclerView,
                from: RecyclerView.ViewHolder,
                to: RecyclerView.ViewHolder,
            ): Boolean {
                devicesAdapter.moveItem(from.bindingAdapterPosition, to.bindingAdapterPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun onSelectedChanged(
                viewHolder: RecyclerView.ViewHolder?,
                actionState: Int,
            ) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG &&
                    viewHolder is DevicesAdapter.VH) {
                    val handle = viewHolder.handle
                    // Hold the handle's pressed state so the ripple stays lit during the drag;
                    // post()'ed because ItemTouchHelper fires this callback BEFORE dispatching
                    // ACTION_CANCEL to the handle, which would reset isPressed=false right after.
                    handle.post { handle.isPressed = true }
                    // Opaque card while dragging so it covers rows underneath (vs transparent fill)
                    val surfaceColor = MaterialColors.getColor(
                        viewHolder.itemView,
                        com.google.android.material.R.attr.colorSurface,
                    )
                    viewHolder.card.setCardBackgroundColor(surfaceColor)
                }
            }

            override fun clearView(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(rv, viewHolder)
                if (viewHolder is DevicesAdapter.VH) {
                    // Drag ended — release the press state so the ripple fades.
                    viewHolder.handle.isPressed = false
                    // Restore the transparent fill so the row blends back into the page
                    viewHolder.card.setCardBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
                // Persist on drag end so the order survives across launches.
                eqPrefs.saveDevicesOrder(devicesAdapter.currentOrder())
            }
        })
        dragTouchHelper.attachToRecyclerView(devicesList)
        devicesHeader = findViewById(R.id.devicesHeader)
        devicesBody = findViewById(R.id.devicesBody)
        devicesChevron = findViewById(R.id.devicesChevron)
        currentDeviceDropdownLayout = findViewById(R.id.currentDevicePresetLayout)
        currentDeviceDropdown = findViewById(R.id.currentDevicePresetDropdown)
        // TextInputLayout (holder of the ripple foreground) intercepts touches; forward them to
        // popup open/dismiss so the dropdown still toggles on box tap.
        currentDeviceDropdown.setOnDismissListener {
            currentDeviceLastDismissAt = System.currentTimeMillis()
        }
        currentDeviceDropdownLayout.setOnClickListener {
            // A popup dismissed <300ms ago by an outside-tap on this box also fired this click — don't reopen
            if (System.currentTimeMillis() - currentDeviceLastDismissAt < 300) {
                currentDeviceLastDismissAt = 0L
                return@setOnClickListener
            }
            if (currentDeviceDropdown.isPopupShowing) {
                currentDeviceDropdown.dismissDropDown()
            } else {
                currentDeviceDropdown.showDropDown()
            }
        }
        applyBoxOutlineRipple(currentDeviceDropdownLayout, currentDeviceDropdown)

        // Restore the last expand/collapse choice; default expanded.
        devicesExpanded = getPreferences(MODE_PRIVATE).getBoolean(PREF_DEVICES_EXPANDED, true)
        applyDevicesExpanded(animate = false)
        devicesHeader.setOnClickListener {
            devicesExpanded = !devicesExpanded
            getPreferences(MODE_PRIVATE).edit().putBoolean(PREF_DEVICES_EXPANDED, devicesExpanded).apply()
            applyDevicesExpanded(animate = true)
        }

        maybeRequestBluetoothPermission()
    }

    override fun onStart() {
        super.onStart()
        // Sync the auto-switch card on every surface — the flag could have flipped elsewhere (debug, ADB, linked settings)
        refreshDeviceAutoSwitchUi()
        // Bind to EqService (same pattern as MainActivity) to read the live routing monitor
        bindService(Intent(this, EqService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
        // Standalone scan: with the EQ service off the routing monitor isn't alive and nothing has
        // been remembered — enumerate output sinks ourselves so the list still populates.
        scanCurrentlyConnectedOutputs()
        refreshDevices()
    }

    /** Push the persisted auto-switch state into the toggle card. Body copy is fixed (matches the
     *  Channel Input Session-detection card); the switch position alone communicates on/off. Gates
     *  [com.bearinmind.equalizer314.audio.RouteSwitchCoordinator] — off = route changes tracked but no preset applied. */
    private fun refreshDeviceAutoSwitchUi() {
        deviceAutoSwitchSwitch.isChecked = eqPrefs.getDeviceAutoSwitchEnabled()
    }

    private fun scanCurrentlyConnectedOutputs() {
        val am = getSystemService(android.media.AudioManager::class.java) ?: return
        for (d in am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)) {
            if (!d.isSink) continue
            val key = DeviceIdentity.keyOf(d) ?: continue
            eqPrefs.rememberSeenDevice(key, DeviceIdentity.labelOf(d))
        }
    }

    /** Pick the highest-priority connected output via AudioManager directly — fallback when the
     *  service's AudioRoutingMonitor isn't running or its dedupe never emitted for the current device. */
    private fun pickActiveOutputDirect(): android.media.AudioDeviceInfo? {
        val am = getSystemService(android.media.AudioManager::class.java) ?: return null
        var best: android.media.AudioDeviceInfo? = null
        var bestPri = 0
        for (d in am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)) {
            if (!d.isSink) continue
            DeviceIdentity.keyOf(d) ?: continue
            val p = DeviceIdentity.priority(d)
            if (p > bestPri) {
                bestPri = p
                best = d
            }
        }
        return best
    }

    override fun onStop() {
        super.onStop()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
            eqService = null
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    }

    // ---- Active device card --------------------------------------------

    private fun refreshActiveDevice() {
        val monitor = eqService?.routingMonitor
        // Prefer the service's monitor (live add/remove callbacks) with a direct AudioManager scan
        // fallback so the card still shows something when the EQ service is off, or when the
        // monitor's stale-key dedupe never emitted for the current output (e.g. USB connected before service start).
        val active = monitor?.pickActiveOutput() ?: pickActiveOutputDirect()
        if (active == null) {
            activeKey = null
            activeLabel = null
            activeDeviceLabel.text = "No current device"
            activeDeviceKey.text = ""
            activeDeviceKey.visibility = View.GONE
            currentDeviceDropdownLayout.visibility = View.GONE
            return
        }
        activeKey = DeviceIdentity.keyOf(active)
        activeLabel = DeviceIdentity.labelOf(active)
        activeDeviceLabel.text = activeLabel ?: "Current output"
        // Device key on a second line via DeviceIdentity.displayKey: USB/wired/speaker show
        // "USB"/"Wired"/"Speaker" instead of duplicating the product name; Bluetooth still shows the MAC.
        val keyDisplay = activeKey?.let { DeviceIdentity.displayKey(it) }.orEmpty()
        if (keyDisplay.isNotEmpty()) {
            activeDeviceKey.text = keyDisplay
            activeDeviceKey.visibility = View.VISIBLE
        } else {
            activeDeviceKey.text = ""
            activeDeviceKey.visibility = View.GONE
        }
        val binding = activeKey?.let { eqPrefs.getDeviceBinding(it) }

        // Mirror of the per-row dropdown, bound to the active device — writes the same binding
        // entry; both stay in sync via refreshActiveDevice + refreshDevices.
        val key = activeKey
        currentDeviceDropdownLayout.visibility = View.VISIBLE
        if (key == null) {
            currentDeviceDropdown.setOnItemClickListener(null)
            return
        }
        val knownNames = listCustomPresetNames()
        val isDisable = binding?.presetName == EqPreferencesManager.DEVICE_PRESET_DISABLED
        val currentSelection = when {
            binding == null -> "(none)"
            isDisable -> DISABLE_LABEL
            else -> binding.presetName
        }
        val missing = binding != null && !isDisable && binding.presetName !in knownNames
        val entries = buildPresetEntries(if (missing) binding!!.presetName else null)
        currentDeviceDropdown.setText(
            if (missing) "${binding!!.presetName} (missing)" else currentSelection,
            false,
        )
        currentDeviceDropdown.setAdapter(PresetDropdownAdapter(this, entries))
        currentDeviceDropdown.setOnItemClickListener { _, _, position, _ ->
            val pick = entries[position].displayName
            val label = activeLabel ?: ""
            when {
                pick == "(none)" -> {
                    eqPrefs.removeDeviceBinding(key)
                    notifyBindingChanged()
                    Toast.makeText(this, "Unbound $label", Toast.LENGTH_SHORT).show()
                }
                pick == DISABLE_LABEL -> {
                    eqPrefs.saveDeviceBinding(
                        EqPreferencesManager.Binding(key, label, EqPreferencesManager.DEVICE_PRESET_DISABLED)
                    )
                    notifyBindingChanged()
                    Toast.makeText(this, "EQ disabled for $label", Toast.LENGTH_SHORT).show()
                }
                pick.endsWith(" (missing)") -> {
                    // dangling — keep as-is
                }
                else -> {
                    eqPrefs.saveDeviceBinding(EqPreferencesManager.Binding(key, label, pick))
                    // This dropdown edits the active device, so the pick IS the preset driving
                    // audio. Write the preset name pref directly in addition to the broadcast —
                    // the broadcast's RouteSwitchCoordinator calls savePresetName only when
                    // auto-switch is on; direct write makes the chip/notification reflect the
                    // explicit binding regardless of the toggle.
                    eqPrefs.savePresetName(pick)
                    notifyBindingChanged()
                    Toast.makeText(this, "Bound \"$pick\" to $label", Toast.LENGTH_SHORT).show()
                }
            }
            // Drop focus so the TextInputLayout returns to its idle outline color
            currentDeviceDropdown.clearFocus()
            // Keep both views in sync — the active device is also a row in the Devices list
            refreshActiveDevice()
            refreshDevices()
        }
    }

    // ---- Devices list --------------------------------------------------

    private fun refreshDevices() {
        val seen = eqPrefs.getAllSeenDevices().toMutableList()
        // Ensure the active device is listed even if never explicitly remembered (e.g. first launch)
        val activeKey = this.activeKey
        val activeLabel = this.activeLabel
        if (activeKey != null && activeLabel != null && seen.none { it.first == activeKey }) {
            seen.add(0, activeKey to activeLabel)
        }
        // Apply user-saved drag order; devices not in it append in natural (insertion) order,
        // active device pinned first.
        val savedOrder = eqPrefs.getDevicesOrder()
        val byKey = seen.associateBy { it.first }
        val ordered = mutableListOf<Pair<String, String>>()
        for (k in savedOrder) byKey[k]?.let { ordered.add(it) }
        for (item in seen) if (ordered.none { it.first == item.first }) ordered.add(item)

        if (ordered.isEmpty()) {
            emptyState.visibility = View.VISIBLE
            devicesAdapter.setItems(emptyList())
            return
        }
        emptyState.visibility = View.GONE
        devicesAdapter.setItems(ordered)
    }

    /**
     * Devices-list adapter. Each row is `item_device_row.xml` (drag handle left, outlined card
     * right); the handle's touch listener starts an [ItemTouchHelper] drag whose onMove/clearView
     * callbacks reorder + persist the list.
     */
    private inner class DevicesAdapter : RecyclerView.Adapter<DevicesAdapter.VH>() {

        private val items = mutableListOf<Pair<String, String>>()

        fun setItems(newItems: List<Pair<String, String>>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        fun moveItem(from: Int, to: Int) {
            if (from < 0 || to < 0 || from >= items.size || to >= items.size) return
            val moved = items.removeAt(from)
            items.add(to, moved)
            notifyItemMoved(from, to)
        }

        fun currentOrder(): List<String> = items.map { it.first }

        inner class VH(view: android.view.View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.deviceRowName)
            val keyText: TextView = view.findViewById(R.id.deviceRowKey)
            val presetLayout: TextInputLayout = view.findViewById(R.id.deviceRowPresetLayout)
            val dropdown: MaterialAutoCompleteTextView = view.findViewById(R.id.deviceRowPresetDropdown)
            val handle: android.widget.ImageView = view.findViewById(R.id.deviceRowDragHandle)
            // Top-level is a FrameLayout; the notched card and the
            // drag handle live as siblings inside it.
            val card: NotchedDeviceCardView = view.findViewById(R.id.deviceRowCard)
            // Suppresses the click-handler's reopen when this row's
            // popup auto-dismisses via outside-tap on the dropdown box.
            var lastDismissAt = 0L
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = layoutInflater.inflate(R.layout.item_device_row, parent, false)
            return VH(view)
        }

        override fun getItemCount() = items.size

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: VH, position: Int) {
            val (key, label) = items[position]

            // Name on top, key below via DeviceIdentity.displayKey: USB/wired/speaker show
            // "USB"/"Wired"/"Speaker" (not the product name again); Bluetooth shows the MAC (unique identifier).
            val keyDisplay = DeviceIdentity.displayKey(key)
            holder.name.text = label
            if (keyDisplay.isNotEmpty()) {
                holder.keyText.text = keyDisplay
                holder.keyText.visibility = View.VISIBLE
            } else {
                holder.keyText.text = ""
                holder.keyText.visibility = View.GONE
            }

            val knownNames = listCustomPresetNames()
            val binding = eqPrefs.getDeviceBinding(key)
            val isDisable = binding?.presetName == EqPreferencesManager.DEVICE_PRESET_DISABLED
            val currentSelection = when {
                binding == null -> "(none)"
                isDisable -> DISABLE_LABEL
                else -> binding.presetName
            }
            val missing = binding != null && !isDisable && binding.presetName !in knownNames
            val entries = buildPresetEntries(if (missing) binding!!.presetName else null)

            val dropdown = holder.dropdown
            dropdown.setText(
                if (missing) "${binding!!.presetName} (missing)" else currentSelection,
                false,
            )
            dropdown.setAdapter(PresetDropdownAdapter(this@AudioOutputActivity, entries))

            // TextInputLayout owns the ripple foreground + touch handling; route clicks to popup
            // open/dismiss. The dismiss-listener + 300ms suppression avoids the reopen cycle when
            // an outside-tap both dismisses the popup AND fires this click.
            dropdown.setOnDismissListener {
                holder.lastDismissAt = System.currentTimeMillis()
            }
            holder.presetLayout.setOnClickListener {
                if (System.currentTimeMillis() - holder.lastDismissAt < 300) {
                    holder.lastDismissAt = 0L
                    return@setOnClickListener
                }
                if (dropdown.isPopupShowing) dropdown.dismissDropDown() else dropdown.showDropDown()
            }
            applyBoxOutlineRipple(holder.presetLayout, dropdown)

            dropdown.setOnItemClickListener { _, _, pos, _ ->
                val pick = entries[pos].displayName
                when {
                    pick == "(none)" -> {
                        eqPrefs.removeDeviceBinding(key)
                        notifyBindingChanged()
                        Toast.makeText(this@AudioOutputActivity, "Unbound $label", Toast.LENGTH_SHORT).show()
                    }
                    pick == DISABLE_LABEL -> {
                        eqPrefs.saveDeviceBinding(
                            EqPreferencesManager.Binding(key, label, EqPreferencesManager.DEVICE_PRESET_DISABLED)
                        )
                        notifyBindingChanged()
                        Toast.makeText(this@AudioOutputActivity, "EQ disabled for $label", Toast.LENGTH_SHORT).show()
                    }
                    pick.endsWith(" (missing)") -> {
                        // Picked the dangling entry — keep the binding as-is.
                    }
                    else -> {
                        eqPrefs.saveDeviceBinding(EqPreferencesManager.Binding(key, label, pick))
                        // If this row IS the active device, apply the pick to the preset name pref
                        // immediately — mirrors the top dropdown, bypassing RouteSwitchCoordinator's
                        // auto-switch gate for explicit user actions.
                        if (key == activeKey) {
                            eqPrefs.savePresetName(pick)
                        }
                        notifyBindingChanged()
                        Toast.makeText(this@AudioOutputActivity, "Bound \"$pick\" to $label", Toast.LENGTH_SHORT).show()
                    }
                }
                dropdown.clearFocus()
                refreshActiveDevice()
            }

            // Long-press the card → "Forget device" option.
            holder.card.setOnLongClickListener {
                PopupMenu(this@AudioOutputActivity, holder.card).apply {
                    menu.add("Forget device")
                    setOnMenuItemClickListener {
                        eqPrefs.forgetSeenDevice(key)
                        eqPrefs.removeDeviceBinding(key)
                        notifyBindingChanged()
                        refreshDevices()
                        true
                    }
                    show()
                }
                true
            }

            // Drag handle starts the ItemTouchHelper drag. Pressed-state is driven manually so the
            // ripple stays lit for the whole gesture: DOWN → isPressed=true; UP → isPressed=false
            // (tap-without-drag); CANCEL → consume so View.onTouchEvent never sets isPressed=false
            // (ItemTouchHelper dispatches CANCEL when it takes over the touch stream — unconsumed,
            // the framework's cancel handler kills the ripple mid-drag; clearView() releases isPressed instead).
            holder.handle.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        v.isPressed = true
                        dragTouchHelper.startDrag(holder)
                        false
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        v.isPressed = false
                        false
                    }
                    android.view.MotionEvent.ACTION_CANCEL -> true
                    else -> false
                }
            }
        }
    }

    // ---- Helpers -------------------------------------------------------

    /**
     * Ripple foreground whose bounds match the actual outlined-box rect, so the ripple stops at
     * every outline edge. Hard-coded insets never lined up — the offset depends on the floated
     * label's vertical centre, which varies with font scale. In OutlinedBox mode the box drawable
     * is the inner AutoCompleteTextView's background; `offsetDescendantRectToMyCoords` translates
     * its bounds into TextInputLayout space, giving the exact outline rect to inset to.
     */
    private fun applyBoxOutlineRipple(layout: TextInputLayout, dropdown: android.view.View) {
        layout.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (dropdown.width <= 0 || dropdown.height <= 0 || layout.width <= 0) return true
                layout.viewTreeObserver.removeOnPreDrawListener(this)

                val rect = android.graphics.Rect(0, 0, dropdown.width, dropdown.height)
                layout.offsetDescendantRectToMyCoords(dropdown, rect)

                val cornerRadius = layout.boxCornerRadiusTopStart
                val highlightColor = MaterialColors.getColor(
                    layout,
                    com.google.android.material.R.attr.colorControlHighlight,
                )
                val density = resources.displayMetrics.density

                // Main outline mask — covers the full outline rect.
                val outlineMask = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    this.cornerRadius = cornerRadius
                    setColor(android.graphics.Color.WHITE)
                }
                // Bump mask above the outline at the "Preset" label. LayerDrawable unions it with
                // the outline mask — ripple clip = outline + a small rounded tab following the
                // label. Position comes from the label's measured width plus Material3's known
                // padding (16dp from outline.left to the text baseline, ~4dp cutout padding around
                // the label), so the bump tracks the label across font scales.
                val labelText = (layout.hint ?: "Preset").toString()
                val labelTextSizePx = 12f * resources.displayMetrics.scaledDensity
                // Same typeface + letter spacing as the dropdown so the measurement tracks what
                // Material's CollapsingTextHelper actually paints.
                val labelMeasuredWidth = android.graphics.Paint().apply {
                    textSize = labelTextSizePx
                    typeface = (dropdown as? android.widget.TextView)?.typeface
                        ?: android.graphics.Typeface.DEFAULT
                    if (dropdown is android.widget.TextView) {
                        letterSpacing = dropdown.letterSpacing
                    }
                }.measureText(labelText)
                val labelCutoutPaddingPx = (4 * density).toInt()
                val labelHorizontalInsetPx = (16 * density).toInt()
                val bumpHeightPx = (labelTextSizePx + 2 * 2 * density).toInt()
                // Bump spans label-left − 4dp to label-right + 4dp — the exact width of the outline
                // cutout Material draws around the floated label.
                val labelTextLeft = rect.left + labelHorizontalInsetPx
                val labelTextRight = labelTextLeft + labelMeasuredWidth.toInt()
                val bumpLeft = labelTextLeft - labelCutoutPaddingPx
                val bumpRight = labelTextRight + labelCutoutPaddingPx
                val bumpTop = (rect.top - bumpHeightPx / 2).coerceAtLeast(0)
                val bumpBottom = rect.top + bumpHeightPx / 2
                val bumpCornerRadius = bumpHeightPx / 2f  // pill shape

                val labelBumpMask = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    this.cornerRadius = bumpCornerRadius
                    setColor(android.graphics.Color.WHITE)
                }

                val mask = android.graphics.drawable.LayerDrawable(
                    arrayOf(outlineMask, labelBumpMask)
                ).apply {
                    setLayerInset(
                        0,
                        rect.left,
                        rect.top,
                        (layout.width - rect.right).coerceAtLeast(0),
                        (layout.height - rect.bottom).coerceAtLeast(0),
                    )
                    setLayerInset(
                        1,
                        bumpLeft,
                        bumpTop,
                        (layout.width - bumpRight).coerceAtLeast(0),
                        (layout.height - bumpBottom).coerceAtLeast(0),
                    )
                }

                layout.foreground = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(highlightColor),
                    null,
                    mask,
                )
                return true
            }
        })
    }

    /** Re-run EqService's route coordinator for the currently-routed device — called after any
     *  binding add/change/remove so it takes effect on live DP immediately (no disconnect/reconnect needed). */
    private fun notifyBindingChanged() {
        sendBroadcast(
            Intent(com.bearinmind.equalizer314.audio.EqService.ACTION_REAPPLY_DEVICE_BINDING)
                .setPackage(packageName)
        )
    }

    private fun listCustomPresetNames(): List<String> {
        val prefs = getSharedPreferences("custom_presets", MODE_PRIVATE)
        // Only String values are real presets — MainActivity also stores a `preset_names` StringSet
        // bookkeeping key here, which would parse as a preset "names" and crash ClassCastException on getString().
        return prefs.all
            .filter { (k, v) -> k.startsWith("preset_") && v is String }
            .keys
            .map { it.removePrefix("preset_") }
            .sorted()
    }

    private fun loadPresetJson(name: String): JSONObject? {
        val prefs = getSharedPreferences("custom_presets", MODE_PRIVATE)
        val str = runCatching { prefs.getString("preset_$name", null) }
            .getOrNull() ?: return null
        return runCatching { JSONObject(str) }.getOrNull()
    }

    /** Entries for every preset dropdown on this screen. Order: "(none)", each custom preset
     *  (with full JSON so the curve preview can detect CSE), then optionally a dangling/missing sentinel. */
    private fun buildPresetEntries(missingPresetName: String?): List<PresetDropdownAdapter.Entry> {
        val out = mutableListOf<PresetDropdownAdapter.Entry>()
        out.add(PresetDropdownAdapter.Entry("(none)", null))
        // "Disable EQ" fully detaches our DP while this device is active (vs "(none)" which keeps
        // the current preset); null JSON → blank curve, no preamp subtitle.
        out.add(PresetDropdownAdapter.Entry(DISABLE_LABEL, null, isDisable = true))
        for (name in listCustomPresetNames()) {
            out.add(PresetDropdownAdapter.Entry(name, loadPresetJson(name)))
        }
        if (missingPresetName != null) {
            out.add(PresetDropdownAdapter.Entry("$missingPresetName (missing)", null))
        }
        return out
    }

    private fun maybeRequestBluetoothPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
            == PackageManager.PERMISSION_GRANTED
        ) return
        // Fire-and-forget (like the notification permission flow); UI isn't gated on it — without
        // it, BT identity falls back to product name only.
        requestPermissions(arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT), REQ_BT_CONNECT)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BT_CONNECT) {
            if (grantResults.isNotEmpty()
                && grantResults[0] != PackageManager.PERMISSION_GRANTED
            ) {
                Toast.makeText(
                    this,
                    "Bluetooth identification will use device name only — two of the same model collide.",
                    Toast.LENGTH_LONG,
                ).show()
            }
            refreshDevices()
        }
    }

    /** Compose-style expand/collapse (AppDrawer `AnimatedVisibility` feel): pure height
     *  interpolation via ValueAnimator on `layoutParams.height` — no Transition fade, no visibility
     *  blink. Symmetric [EXPAND_DURATION_MS], FastOutSlowInInterpolator (Compose default); chevron rotates in lockstep. */
    private fun applyDevicesExpanded(animate: Boolean) {
        val targetRotation = if (devicesExpanded) 90f else 0f
        if (!animate) {
            devicesBody.visibility = if (devicesExpanded) View.VISIBLE else View.GONE
            if (devicesExpanded) {
                devicesBody.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                devicesBody.requestLayout()
            }
            devicesChevron.rotation = targetRotation
            return
        }
        animateDevicesBody(devicesExpanded)
        devicesChevron.animate()
            .rotation(targetRotation)
            .setDuration(EXPAND_DURATION_MS)
            .setInterpolator(androidx.interpolator.view.animation.FastOutSlowInInterpolator())
            .start()
    }

    private fun animateDevicesBody(expand: Boolean) {
        val interp = androidx.interpolator.view.animation.FastOutSlowInInterpolator()
        if (expand) {
            devicesBody.visibility = View.VISIBLE
            val widthSpec = View.MeasureSpec.makeMeasureSpec(
                (devicesBody.parent as View).width, View.MeasureSpec.EXACTLY,
            )
            val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            devicesBody.measure(widthSpec, heightSpec)
            val target = devicesBody.measuredHeight
            android.animation.ValueAnimator.ofInt(0, target).apply {
                duration = EXPAND_DURATION_MS
                interpolator = interp
                addUpdateListener {
                    devicesBody.layoutParams.height = it.animatedValue as Int
                    devicesBody.requestLayout()
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        devicesBody.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        devicesBody.requestLayout()
                    }
                })
                start()
            }
        } else {
            val start = devicesBody.height
            android.animation.ValueAnimator.ofInt(start, 0).apply {
                duration = EXPAND_DURATION_MS
                interpolator = interp
                addUpdateListener {
                    devicesBody.layoutParams.height = it.animatedValue as Int
                    devicesBody.requestLayout()
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        devicesBody.visibility = View.GONE
                        devicesBody.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        devicesBody.requestLayout()
                    }
                })
                start()
            }
        }
    }

    companion object {
        private const val REQ_BT_CONNECT = 300
        /** Display label for the "fully detach DP for this device" dropdown choice; persisted as
         *  [EqPreferencesManager.DEVICE_PRESET_DISABLED] in the binding. */
        private const val DISABLE_LABEL = "Disable EQ"
        private const val PREF_DEVICES_EXPANDED = "devicesExpanded"
        /** Devices section open/close duration — past Compose's 300 ms `AnimatedVisibility` default
         *  toward Material's "Emphasized" ≈500 ms so the slide reads deliberate. */
        private const val EXPAND_DURATION_MS = 500L
    }
}
