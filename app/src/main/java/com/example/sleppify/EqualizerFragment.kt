package com.example.sleppify

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.materialswitch.MaterialSwitch
import java.util.Locale

class EqualizerFragment : Fragment() {

    private lateinit var preferences: SharedPreferences
    private var tvOutputRoute: TextView? = null
    private var ivOutputIcon: ImageView? = null
    private lateinit var tvSelectedPreset: TextView
    private lateinit var cardPresetSelector: View
    private var cardGraphicEq: View? = null
    private var cardBassTuner: View? = null

    private lateinit var eqCurveView: EqCurveEditorView
    private var switchEqEnabled: MaterialSwitch? = null
    private var activePopupWindow: PopupWindow? = null
    private lateinit var sliderBassGain: Slider
    private lateinit var tvBassGainValue: TextView
    private var tvSelectedBassType: TextView? = null
    private var cardBassTypeSelector: View? = null
    private lateinit var sliderBassFrequency: Slider
    private lateinit var tvBassFrequencyValue: TextView

    private var restoringUi: Boolean = false
    private var hasLoadedUi: Boolean = false
    private var boundProfileId: String? = null
    private var audioManager: AudioManager? = null
    private var forceSpeakerOutputSelection: Boolean = false
    private var forceSpeakerFallbackUntilMs: Long = 0L
    private var lastOutputRefreshAtMs: Long = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

    private val outputDeviceCallback: AudioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>?) {
            if (containsHeadphoneLikeOutput(addedDevices)) {
                forceSpeakerOutputSelection = false
                forceSpeakerFallbackUntilMs = 0L
            }
            refreshOutputDeviceAndProfile(true, true)
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>?) {
            if (containsHeadphoneLikeOutput(removedDevices)) {
                forceSpeakerOutputSelection = true
                forceSpeakerFallbackUntilMs = System.currentTimeMillis() + FORCE_SPEAKER_FALLBACK_WINDOW_MS
            }
            refreshOutputDeviceAndProfile(true, true)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_equalizer, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        preferences = requireContext().getSharedPreferences(AudioEffectsService.PREFS_NAME, Context.MODE_PRIVATE)
        audioManager = requireContext().getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        // Own toolbar: back button + status bar inset
        val llEqToolbar = view.findViewById<View>(R.id.llEqToolbar)
        view.findViewById<View>(R.id.btnEqBack)?.setOnClickListener {
            (activity as? MainActivity)?.returnFromEqualizer()
        }
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            llEqToolbar?.setPadding(llEqToolbar.paddingLeft, top, llEqToolbar.paddingRight, llEqToolbar.paddingBottom)
            insets
        }

        // La vista puede recrearse al entrar/salir de Ajustes; forzamos recarga de preset/curva.
        hasLoadedUi = false
        boundProfileId = null

        // tvOutputRoute = view.findViewById(R.id.tvOutputRoute) // View removed from layout
        // ivOutputIcon = view.findViewById(R.id.ivOutputIcon) // View removed from layout
        tvSelectedPreset = view.findViewById(R.id.tvSelectedPreset)
        cardPresetSelector = view.findViewById(R.id.cardPresetSelector)
        cardGraphicEq = view.findViewById(R.id.cardGraphicEq)
        cardBassTuner = view.findViewById(R.id.cardBassTuner)

        eqCurveView = view.findViewById(R.id.eqCurveView)
        switchEqEnabled = view.findViewById(R.id.switchEqEnabled)

        sliderBassGain = view.findViewById(R.id.sliderBassGain)
        tvBassGainValue = view.findViewById(R.id.tvBassGainValue)
        tvSelectedBassType = view.findViewById(R.id.tvSelectedBassType)
        cardBassTypeSelector = view.findViewById(R.id.cardBassTypeSelector)
        sliderBassFrequency = view.findViewById(R.id.sliderBassFrequency)
        tvBassFrequencyValue = view.findViewById(R.id.tvBassFrequencyValue)

        sliderBassFrequency.valueFrom = AudioEffectsService.BASS_FREQUENCY_MIN_HZ
        sliderBassFrequency.valueTo = AudioEffectsService.BASS_FREQUENCY_MAX_HZ

        setupListeners()
        refreshOutputDeviceAndProfile(false, true)
        refreshEqModuleState()
        ensureEqServiceActive()
        applyCardStyles(view)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            view?.findViewById<android.widget.ScrollView>(R.id.scrollEqContent)?.scrollTo(0, 0)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshOutputDeviceAndProfile(true, false)
        refreshEqModuleState()
        ensureEqServiceActive()
    }

    fun onCloudEqHydrationCompleted() {
        if (!isAdded) return

        mainHandler.post {
            if (!isAdded) return@post
            hasLoadedUi = false
            refreshOutputDeviceAndProfile(true, true)
            refreshEqModuleState()
            ensureEqServiceActive()
        }
    }

    fun onOutputProfileSwitchedLocally() {
        if (!isAdded) return
        mainHandler.post {
            if (!isAdded) return@post
            refreshOutputDeviceAndProfile(true, true)
        }
    }

    override fun onStart() {
        super.onStart()
        registerOutputDeviceCallback()
    }

    override fun onStop() {
        dismissActivePopupWindow()
        unregisterOutputDeviceCallback()
        super.onStop()
    }

    private fun dismissActivePopupWindow() {
        val popup = activePopupWindow ?: return
        runCatching { popup.dismiss() }
        activePopupWindow = null
    }

    private fun registerActivePopupWindow(popupWindow: PopupWindow) {
        dismissActivePopupWindow()
        activePopupWindow = popupWindow
        popupWindow.setOnDismissListener {
            if (activePopupWindow === popupWindow) activePopupWindow = null
        }
    }

    private fun loadPreferencesIntoUi() {
        restoringUi = true

        val bassDb = quantizeBassGainToggle(preferences.getFloat(AudioEffectsService.KEY_BASS_DB, 0f))
        sliderBassGain.value = bassDb
        tvBassGainValue.text = formatDb(bassDb)

        val bassFrequencyHz = normalizeBassFrequencySlider(
            preferences.getFloat(AudioEffectsService.KEY_BASS_FREQUENCY_HZ, BASS_DEFAULT_FREQUENCY_HZ)
        )
        sliderBassFrequency.value = bassFrequencyHz
        tvBassFrequencyValue.text = formatHz(bassFrequencyHz)

        val bassType = normalizeBassType(preferences.getInt(AudioEffectsService.KEY_BASS_TYPE, BASS_TYPE_DEFAULT))
        tvSelectedBassType?.text = bassTypeLabelFromValue(bassType)

        val bandValues = FloatArray(EQ_BAND_COUNT)
        for (i in bandValues.indices) {
            bandValues[i] = preferences.getFloat(AudioEffectsService.bandDbKey(i), 0f)
        }
        eqCurveView.setBandValues(bandValues)

        val selectedPreset = preferences.getString(KEY_SELECTED_PRESET, PRESET_DEFAULT)
        tvSelectedPreset.text = presetLabelFromId(selectedPreset)

        restoringUi = false
    }

    private fun setupListeners() {
        sliderBassGain.addOnChangeListener { _, value, _ ->
            tvBassGainValue.text = formatDb(value)
            if (restoringUi) return@addOnChangeListener
            preferences.edit().putFloat(AudioEffectsService.KEY_BASS_DB, value).apply()
            markPresetAsCustom()
            applyIfEnabled()
        }
        sliderBassGain.setOnFocusChangeListener { _, hasFocus ->
            sliderBassGain.haloRadius = if (hasFocus) dp(24) else dp(0)
        }
        sliderBassFrequency.addOnChangeListener { _, value, _ ->
            tvBassFrequencyValue.text = formatHz(value)
            if (restoringUi) return@addOnChangeListener
            preferences.edit().putFloat(AudioEffectsService.KEY_BASS_FREQUENCY_HZ, value).apply()
            markPresetAsCustom()
            applyIfEnabled()
        }
        sliderBassFrequency.setOnFocusChangeListener { _, hasFocus ->
            sliderBassFrequency.haloRadius = if (hasFocus) dp(24) else dp(0)
        }
        cardBassTypeSelector?.setOnClickListener { showBassTypePopup() }

        eqCurveView.apply {
            setEditingEnabled(false)
            setHandlesVisible(false)
            setGridVisible(true)
            setAxisDbLabelsEnabled(true)
            setAreaFillEnabled(true)
            setModalBandGuidesEnabled(false)
        }

        cardGraphicEq?.setOnClickListener { showGraphicEqEditorDialog() }

        cardPresetSelector.setOnClickListener { showPresetPopup() }

        switchEqEnabled?.setOnCheckedChangeListener { _, isChecked ->
            if (restoringUi) return@setOnCheckedChangeListener
            preferences.edit().putBoolean(AudioEffectsService.KEY_ENABLED, isChecked).apply()
            AudioDeviceProfileStore.persistActiveValuesToCurrentProfile(preferences)
            if (isChecked) startOrUpdateService() else stopEqService()
            refreshEqModuleState()
        }
    }

    private fun applyIfEnabled() {
        if (!isEqEnabled()) {
            refreshEqModuleState()
            return
        }
        AudioDeviceProfileStore.persistActiveValuesToCurrentProfile(preferences)
        startOrUpdateService()
    }

    private fun ensureEqServiceActive() {
        if (!isEqEnabled()) return
        startOrUpdateService()
    }

    private fun isEqEnabled(): Boolean =
        preferences.getBoolean(AudioEffectsService.KEY_ENABLED, false)

    private fun refreshEqModuleState() {
        val eqEnabled = isEqEnabled()

        switchEqEnabled?.let {
            if (it.isChecked != eqEnabled) {
                restoringUi = true
                it.isChecked = eqEnabled
                restoringUi = false
            }
        }

        applyEnabledStyle(cardPresetSelector, eqEnabled)
        applyEnabledStyle(cardBassTypeSelector, eqEnabled)
        applyEnabledStyle(cardGraphicEq, eqEnabled)
        applyEnabledStyle(cardBassTuner, eqEnabled)

        eqCurveView.apply {
            isEnabled = eqEnabled
            setEditingEnabled(false)
            setHandlesVisible(false)
            alpha = if (eqEnabled) 1f else 0.45f
        }
        sliderBassGain.isEnabled = eqEnabled
        sliderBassFrequency.isEnabled = eqEnabled
        applyCardStyles(view)
    }

    private fun applyEnabledStyle(target: View?, enabled: Boolean) {
        if (target == null) return
        target.isEnabled = enabled
        target.isClickable = enabled
        target.isFocusable = enabled
        target.alpha = if (enabled) 1f else 0.45f
    }

    private fun registerOutputDeviceCallback() {
        val manager = audioManager
        if (manager == null) {
            refreshOutputDeviceAndProfile(false, false)
            return
        }
        runCatching { manager.registerAudioDeviceCallback(outputDeviceCallback, mainHandler) }
        refreshOutputDeviceAndProfile(true, false)
    }

    private fun unregisterOutputDeviceCallback() {
        val manager = audioManager ?: return
        runCatching { manager.unregisterAudioDeviceCallback(outputDeviceCallback) }
    }

    private fun refreshOutputDeviceAndProfile(applyOnProfileSwitch: Boolean, forceRefresh: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!forceRefresh && (now - lastOutputRefreshAtMs) < OUTPUT_REFRESH_MIN_INTERVAL_MS) return
        lastOutputRefreshAtMs = now

        var selected: AudioDeviceInfo? = null
        val manager = audioManager
        if (manager == null) {
            setOutputUi("Altavoces", R.drawable.ic_output_speaker, "Salida por altavoz")
        } else {
            selected = AudioDeviceProfileStore.selectPreferredOutput(manager)

            if (selected != null) {
                val selectedType = selected.type
                if (AudioDeviceProfileStore.isBluetoothType(selectedType) || AudioDeviceProfileStore.isWiredType(selectedType)) {
                    forceSpeakerOutputSelection = false
                    forceSpeakerFallbackUntilMs = 0L
                }
            }

            if (selected == null) {
                setOutputUi("Altavoces", R.drawable.ic_output_speaker, "Salida por altavoz")
            } else {
                val type = selected.type
                when {
                    AudioDeviceProfileStore.isBluetoothType(type) ->
                        setOutputUi(deviceName(selected, "Bluetooth"), R.drawable.ic_output_bluetooth, "Salida por Bluetooth")
                    AudioDeviceProfileStore.isWiredType(type) ->
                        setOutputUi("Auriculares", R.drawable.ic_output_wired, "Salida por auriculares")
                    type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE ->
                        setOutputUi("Auriculares", R.drawable.ic_output_earpiece, "Salida por auriculares")
                    else ->
                        setOutputUi("Altavoces", R.drawable.ic_output_speaker, "Salida por altavoz")
                }
            }
        }

        val restoredFromProfile = AudioDeviceProfileStore.restoreActiveValuesForOutput(preferences, selected)
        val profileSwitched = AudioDeviceProfileStore.syncActiveProfileForOutput(preferences, selected)
        val currentProfile = AudioDeviceProfileStore.getActiveProfileId(preferences, selected)
        val previousBoundProfile = boundProfileId
        val shouldReloadUi = restoredFromProfile || profileSwitched || !hasLoadedUi ||
                !TextUtils.equals(previousBoundProfile, currentProfile)
        boundProfileId = currentProfile
        if (shouldReloadUi) {
            ensureBassDefaults()
            ensurePresetStateConsistency()
            loadPreferencesIntoUi()
            hasLoadedUi = true
        }
        refreshEqModuleState()
        if ((profileSwitched || restoredFromProfile) && applyOnProfileSwitch) {
            applyIfEnabled()
        }
    }

    private fun setOutputUi(route: String, iconRes: Int, contentDescription: String) {
        tvOutputRoute?.text = route
        ivOutputIcon?.setImageResource(iconRes)
        ivOutputIcon?.contentDescription = contentDescription
    }

    private fun showPresetPopup() {
        if (!isEqEnabled()) return

        val context = requireContext()
        val selectedPresetId = preferences.getString(KEY_SELECTED_PRESET, PRESET_DEFAULT)
        val userPresets = loadUserPresetEntries()
        val popupWidth = dp(188)

        val popupRoot = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(context, R.color.surface_low))
                cornerRadius = dp(14).toFloat()
            }
        }

        val scrollContainer = android.widget.ScrollView(context).apply {
            // Increased height to support more presets and better scrolling on both TV and Mobile
            layoutParams = ViewGroup.LayoutParams(popupWidth, dp(380))
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            addView(popupRoot)
        }

        val popupWindow = PopupWindow(scrollContainer, popupWidth, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            isFocusable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(8).toFloat()
        }
        registerActivePopupWindow(popupWindow)

        for (preset in PRESET_CONFIGS) {
            val isSelected = preset.id == selectedPresetId
            val row = createPresetRow(preset, isSelected, popupWindow)
            popupRoot.addView(
                row,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }

        if (userPresets.isNotEmpty()) {
            val divider = View(context)
            val dividerParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(6)
                bottomMargin = dp(6)
            }
            divider.layoutParams = dividerParams
            divider.setBackgroundColor(withAlpha(ContextCompat.getColor(context, R.color.text_secondary), 0.22f))
            popupRoot.addView(divider)

            for (entry in userPresets) {
                val isSelected = entry.id == selectedPresetId
                val row = createUserPresetRow(entry, isSelected, popupWindow)
                popupRoot.addView(
                    row,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                )
            }
        }

        popupWindow.showAsDropDown(cardPresetSelector, dp(10), dp(8))
    }

    private fun showBassTypePopup() {
        val anchor = cardBassTypeSelector ?: return
        if (!isEqEnabled()) return

        val context = requireContext()
        val selectedBassType = normalizeBassType(
            preferences.getInt(AudioEffectsService.KEY_BASS_TYPE, BASS_TYPE_DEFAULT)
        )
        val popupWidth = dp(228)

        val popupRoot = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(context, R.color.surface_low))
                cornerRadius = dp(14).toFloat()
            }
        }

        val popupWindow = PopupWindow(popupRoot, popupWidth, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            isFocusable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(8).toFloat()
        }
        registerActivePopupWindow(popupWindow)

        for (option in BASS_TYPE_OPTIONS) {
            val isSelected = option.value == selectedBassType
            val row = createBassTypeRow(option, isSelected, popupWindow)
            popupRoot.addView(
                row,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }

        popupWindow.showAsDropDown(anchor, dp(10), dp(8))
    }

    private fun createPresetRow(
        preset: PresetConfig,
        isSelected: Boolean,
        popupWindow: PopupWindow
    ): TextView = createPopupRow(preset.label, isSelected) {
        applyPreset(preset)
        popupWindow.dismiss()
    }

    private fun createUserPresetRow(
        preset: UserPresetEntry,
        isSelected: Boolean,
        popupWindow: PopupWindow
    ): TextView {
        val row = createPopupRow(preset.name, isSelected) {
            applyUserPreset(preset)
            popupWindow.dismiss()
        }
        row.setOnLongClickListener {
            showDeleteUserPresetDialog(preset, popupWindow)
            true
        }
        return row
    }

    private fun createBassTypeRow(
        option: BassTypeOption,
        isSelected: Boolean,
        popupWindow: PopupWindow
    ): TextView = createPopupRow(option.label, isSelected) {
        preferences.edit().putInt(AudioEffectsService.KEY_BASS_TYPE, option.value).apply()
        tvSelectedBassType?.text = option.label
        markPresetAsCustom()
        applyIfEnabled()
        popupWindow.dismiss()
    }

    private inline fun createPopupRow(
        label: String,
        isSelected: Boolean,
        crossinline onClick: () -> Unit
    ): TextView {
        val context = requireContext()
        val row = TextView(context).apply {
            text = label
            setSingleLine(true)
            textSize = 14f // Slightly larger for TV
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(16), dp(12), dp(16), dp(12)) // Larger padding for TV targets
            setTextColor(
                ContextCompat.getColor(
                    context,
                    if (isSelected) R.color.stitch_blue_light else R.color.text_secondary
                )
            )
            isFocusable = true
            isClickable = true
            
            // Use a focus-aware background for TV remote navigation
            val focusColor = withAlpha(ContextCompat.getColor(context, R.color.stitch_blue_light), 0.35f)
            val selectedColor = withAlpha(ContextCompat.getColor(context, R.color.stitch_blue_light), 0.15f)
            
            val states = android.graphics.drawable.StateListDrawable()
            states.addState(intArrayOf(android.R.attr.state_focused), ColorDrawable(focusColor))
            states.addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(focusColor))
            states.addState(intArrayOf(), if (isSelected) ColorDrawable(selectedColor) else ColorDrawable(Color.TRANSPARENT))
            background = states
        }

        row.setOnClickListener { onClick() }
        return row
    }

    private fun applyPreset(preset: PresetConfig) {
        restoringUi = true
        eqCurveView.setBandValues(preset.bands)
        eqCurveView.setHandlesVisible(false)
        sliderBassGain.value = quantizeBassGainToggle(preset.bassDb)
        tvBassGainValue.text = formatDb(sliderBassGain.value)
        val normalizedBassFrequencyHz = normalizeBassFrequencySlider(preset.bassFrequencyHz)
        sliderBassFrequency.value = normalizedBassFrequencyHz
        tvBassFrequencyValue.text = formatHz(normalizedBassFrequencyHz)
        val normalizedBassType = normalizeBassType(preset.bassType)
        tvSelectedBassType?.text = bassTypeLabelFromValue(normalizedBassType)
        tvSelectedPreset.text = preset.label
        restoringUi = false

        savePresetValues(preset)
        applyIfEnabled()
    }

    private fun applyUserPreset(preset: UserPresetEntry) {
        restoringUi = true
        eqCurveView.setBandValues(preset.bands)
        eqCurveView.setHandlesVisible(false)
        tvSelectedPreset.text = preset.name
        restoringUi = false

        val editor = preferences.edit()
        for (i in preset.bands.indices) {
            editor.putFloat(AudioEffectsService.bandDbKey(i), preset.bands[i])
        }
        editor.putString(KEY_SELECTED_PRESET, preset.id)
        editor.apply()

        applyIfEnabled()
    }

    private fun showDeleteUserPresetDialog(preset: UserPresetEntry, popupWindow: PopupWindow) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Eliminar preset")
            .setMessage("Deseas eliminar el preset \"${preset.name}\"?")
            .setNegativeButton("No", null)
            .setPositiveButton("Eliminar") { _, _ ->
                val selectedPresetId = preferences.getString(KEY_SELECTED_PRESET, PRESET_DEFAULT)

                val editor = preferences.edit()
                editor.remove(KEY_PROFILE_CUSTOM_PRESET_NAME_PREFIX + preset.profileId)
                for (i in 0 until EQ_BAND_COUNT) {
                    editor.remove(userPresetBandKey(preset.profileId, i))
                }

                if (TextUtils.equals(selectedPresetId, preset.id)) {
                    editor.putString(KEY_SELECTED_PRESET, PRESET_CUSTOM)
                }
                editor.apply()

                if (TextUtils.equals(selectedPresetId, preset.id)) {
                    tvSelectedPreset.text = presetLabelFromId(PRESET_CUSTOM)
                }

                popupWindow.dismiss()
            }
            .show()
    }

    private fun loadUserPresetEntries(): List<UserPresetEntry> {
        val allValues = preferences.all
        if (allValues.isEmpty()) return emptyList()

        val result = ArrayList<UserPresetEntry>()
        for ((key, valueObj) in allValues) {
            if (key == null || !key.startsWith(KEY_PROFILE_CUSTOM_PRESET_NAME_PREFIX)) continue
            if (valueObj !is String) continue

            val profileId = key.substring(KEY_PROFILE_CUSTOM_PRESET_NAME_PREFIX.length)
            if (TextUtils.isEmpty(profileId)) continue

            val name = valueObj.trim()
            if (name.isEmpty()) continue

            val bands = readUserPresetBands(profileId)
            result.add(UserPresetEntry(profileId, userPresetIdForProfile(profileId), name, bands))
        }

        if (result.isEmpty()) return emptyList()

        result.sortBy { it.name.lowercase(Locale.US) }
        return result
    }

    private fun findUserPresetById(presetId: String?): UserPresetEntry? {
        val profileId = profileIdFromUserPresetId(presetId) ?: return null

        val raw = preferences.getString(KEY_PROFILE_CUSTOM_PRESET_NAME_PREFIX + profileId, "") ?: return null
        val cleanedName = raw.trim()
        if (cleanedName.isEmpty()) return null

        val bands = readUserPresetBands(profileId)
        return UserPresetEntry(profileId, userPresetIdForProfile(profileId), cleanedName, bands)
    }

    private fun readUserPresetBands(profileId: String): FloatArray {
        val bands = FloatArray(EQ_BAND_COUNT)
        var hasStoredUserBands = false

        for (i in bands.indices) {
            val bandKey = userPresetBandKey(profileId, i)
            if (preferences.contains(bandKey)) {
                bands[i] = readNumericPrefAsFloat(bandKey, 0f)
                hasStoredUserBands = true
            }
        }

        if (hasStoredUserBands) return bands

        // Migration fallback: si no hay snapshot de usuario, intentamos tomar valores del bucket de perfil.
        var hasProfileBands = false
        for (i in bands.indices) {
            val fallbackProfileBandKey = KEY_DEVICE_PROFILE_PREFIX + profileId + "_" + AudioEffectsService.bandDbKey(i)
            if (preferences.contains(fallbackProfileBandKey)) {
                bands[i] = readNumericPrefAsFloat(fallbackProfileBandKey, 0f)
                hasProfileBands = true
            }
        }

        if (!hasProfileBands && TextUtils.equals(profileId, activeProfileIdForCustomPreset())) {
            for (i in bands.indices) {
                bands[i] = preferences.getFloat(AudioEffectsService.bandDbKey(i), 0f)
            }
        }

        saveUserPresetBandsForProfile(profileId, bands)
        return bands
    }

    private fun readNumericPrefAsFloat(key: String, defaultValue: Float): Float {
        try {
            return preferences.getFloat(key, defaultValue)
        } catch (_: ClassCastException) {
            // Fallback para datos legacy tipados como int/long/double.
        }

        val all = preferences.all
        if (!all.containsKey(key)) return defaultValue

        return when (val raw = all[key]) {
            is Number -> raw.toFloat()
            is String -> raw.trim().toFloatOrNull() ?: defaultValue
            else -> defaultValue
        }
    }

    private fun saveUserPresetBandsForProfile(profileId: String, bands: FloatArray) {
        val editor = preferences.edit()
        for (i in 0 until EQ_BAND_COUNT) {
            val value = if (i < bands.size) bands[i] else 0f
            editor.putFloat(userPresetBandKey(profileId, i), value)
        }
        editor.apply()
    }

    private fun userPresetBandKey(profileId: String, bandIndex: Int): String =
        KEY_USER_PRESET_BAND_PREFIX + profileId + "_" + bandIndex

    private fun userPresetIdForProfile(profileId: String): String = PRESET_USER_PREFIX + profileId

    private fun isUserPresetId(presetId: String?): Boolean =
        presetId != null && presetId.startsWith(PRESET_USER_PREFIX)

    private fun profileIdFromUserPresetId(presetId: String?): String? {
        if (!isUserPresetId(presetId)) return null
        val profileId = presetId!!.substring(PRESET_USER_PREFIX.length)
        return if (TextUtils.isEmpty(profileId)) null else profileId
    }

    private fun showGraphicEqEditorDialog() {
        if (!isAdded || !isEqEnabled()) return

        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_eq_editor, null, false)
        val modalEqView: EqCurveEditorView? = dialogView.findViewById(R.id.eqCurveEditorModal)
        val gainTextsRow: LinearLayout? = dialogView.findViewById(R.id.gain_texts)
        val frequencyTextsRow: LinearLayout? = dialogView.findViewById(R.id.frequency_texts)
        val btnClose: MaterialButton? = dialogView.findViewById(R.id.btnEqEditorClose)
        val btnAccept: MaterialButton? = dialogView.findViewById(R.id.btnEqEditorAccept)

        val originalBands = readCurrentBandsSnapshot()
        val originalPresetId = preferences.getString(KEY_SELECTED_PRESET, PRESET_DEFAULT)

        val draftBands = FloatArray(EQ_BAND_COUNT)
        System.arraycopy(originalBands, 0, draftBands, 0, originalBands.size)
        val gainValueViews = populateGraphicEqDialogRows(gainTextsRow, frequencyTextsRow, draftBands)

        var accepted = false

        modalEqView?.apply {
            setBandValues(draftBands)
            setEditingEnabled(true)
            setHandlesVisible(true)
            setGridVisible(false)
            setAxisDbLabelsEnabled(false)
            setAreaFillEnabled(false)
            setModalBandGuidesEnabled(true)
            setOnBandChangeListener(object : EqCurveEditorView.OnBandChangeListener {
                override fun onBandChanged(bandIndex: Int, value: Float) {
                    if (bandIndex < 0 || bandIndex >= draftBands.size) return
                    draftBands[bandIndex] = value
                    refreshGraphicEqDialogGainLabels(gainValueViews, draftBands)
                    applyDraftBandsPreview(draftBands)
                }
            })
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.setOnDismissListener {
            if (!accepted) restoreGraphicEqSnapshot(originalBands, originalPresetId)
        }

        btnClose?.setOnClickListener { dialog.dismiss() }

        btnAccept?.setOnClickListener {
            if (needsCustomPresetNameForCurrentProfile()) {
                promptForCustomPresetName { name ->
                    persistCustomPresetNameForCurrentProfile(name)
                    commitAcceptedGraphicEqChanges(draftBands)
                    accepted = true
                    dialog.dismiss()
                }
                return@setOnClickListener
            }

            commitAcceptedGraphicEqChanges(draftBands)
            accepted = true
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            decorView.setPadding(0, 0, 0, 0)
            setDimAmount(0.88f)
        }
    }

    private fun populateGraphicEqDialogRows(
        gainTextsRow: LinearLayout?,
        frequencyTextsRow: LinearLayout?,
        bandValues: FloatArray
    ): Array<TextView?> {
        val gainValueViews = arrayOfNulls<TextView>(EQ_BAND_COUNT)

        gainTextsRow?.let {
            it.removeAllViews()
            for (i in 0 until EQ_BAND_COUNT) {
                val gainView = createGraphicEqDialogLabel(true)
                gainView.text = formatGraphicEqDialogGain(if (i < bandValues.size) bandValues[i] else 0f)
                it.addView(gainView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                gainValueViews[i] = gainView
            }
        }

        frequencyTextsRow?.let {
            it.removeAllViews()
            for (i in 0 until EQ_BAND_COUNT) {
                val frequencyView = createGraphicEqDialogLabel(false)
                val frequencyHz: Float = if (i < GRAPHIC_EQ_DIALOG_FREQUENCIES_HZ.size) {
                    GRAPHIC_EQ_DIALOG_FREQUENCIES_HZ[i]
                } else {
                    AudioEffectsService.WAVELET_9_BAND_FREQUENCIES_HZ[i].toFloat()
                }
                frequencyView.text = formatGraphicEqDialogFrequency(frequencyHz)
                it.addView(frequencyView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }

        return gainValueViews
    }

    private fun refreshGraphicEqDialogGainLabels(gainValueViews: Array<TextView?>, bandValues: FloatArray) {
        for (i in gainValueViews.indices) {
            val gainView = gainValueViews[i] ?: continue
            val value = if (i < bandValues.size) bandValues[i] else 0f
            gainView.text = formatGraphicEqDialogGain(value)
        }
    }

    private fun createGraphicEqDialogLabel(gainRow: Boolean): TextView {
        val context = requireContext()
        return TextView(context).apply {
            gravity = Gravity.CENTER
            setSingleLine(true)
            maxLines = 1
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setPadding(0, if (gainRow) dp(2) else dp(6), 0, if (gainRow) dp(2) else dp(4))
            textSize = if (gainRow) 11f else 12f
            setTextColor(
                ContextCompat.getColor(
                    context,
                    if (gainRow) R.color.text_secondary else R.color.text_primary
                )
            )
            typeface = if (gainRow) Typeface.DEFAULT else Typeface.DEFAULT_BOLD
        }
    }

    private fun formatGraphicEqDialogGain(value: Float): String = String.format(Locale.US, "%.1f", value)

    private fun formatGraphicEqDialogFrequency(frequencyHz: Float): String {
        if (frequencyHz >= 1000f) {
            val khz = frequencyHz / 1000f
            return if (Math.abs(khz - Math.round(khz)) > 0.01f) {
                String.format(Locale.US, "%.1fk", khz)
            } else {
                String.format(Locale.US, "%dk", Math.round(khz))
            }
        }

        return if (Math.abs(frequencyHz - Math.round(frequencyHz)) > 0.01f) {
            String.format(Locale.US, "%.1f", frequencyHz)
        } else {
            String.format(Locale.US, "%d", Math.round(frequencyHz))
        }
    }

    private fun applyDraftBandsPreview(draftBands: FloatArray) {
        val editor = preferences.edit()
        for (i in draftBands.indices) {
            editor.putFloat(AudioEffectsService.bandDbKey(i), draftBands[i])
        }
        editor.apply()

        eqCurveView.setBandValues(draftBands)
        eqCurveView.setHandlesVisible(false)

        applyIfEnabled()
    }

    private fun commitAcceptedGraphicEqChanges(draftBands: FloatArray) {
        val activeProfileId = activeProfileIdForCustomPreset()
        val customPresetName = customPresetNameForCurrentProfile()
        val selectedPresetId = if (TextUtils.isEmpty(customPresetName)) {
            PRESET_CUSTOM
        } else {
            userPresetIdForProfile(activeProfileId)
        }

        val editor = preferences.edit()
        for (i in draftBands.indices) {
            editor.putFloat(AudioEffectsService.bandDbKey(i), draftBands[i])
        }
        editor.putString(KEY_SELECTED_PRESET, selectedPresetId)
        editor.apply()

        if (!TextUtils.isEmpty(customPresetName)) {
            saveUserPresetBandsForProfile(activeProfileId, draftBands)
        }

        eqCurveView.setBandValues(draftBands)
        eqCurveView.setHandlesVisible(false)
        tvSelectedPreset.text = presetLabelFromId(selectedPresetId)

        AudioDeviceProfileStore.persistActiveValuesToCurrentProfile(preferences)
        applyIfEnabled()
    }

    private fun restoreGraphicEqSnapshot(originalBands: FloatArray, originalPresetId: String?) {
        val safePresetId = if (TextUtils.isEmpty(originalPresetId)) PRESET_DEFAULT else originalPresetId!!

        val editor = preferences.edit()
        for (i in originalBands.indices) {
            editor.putFloat(AudioEffectsService.bandDbKey(i), originalBands[i])
        }
        editor.putString(KEY_SELECTED_PRESET, safePresetId)
        editor.apply()

        eqCurveView.setBandValues(originalBands)
        eqCurveView.setHandlesVisible(false)
        tvSelectedPreset.text = presetLabelFromId(safePresetId)

        applyIfEnabled()
    }

    private fun needsCustomPresetNameForCurrentProfile(): Boolean =
        customPresetNameForCurrentProfile().isNullOrEmpty()

    private fun readCurrentBandsSnapshot(): FloatArray {
        return eqCurveView.getBandValuesSnapshot()
    }

    private fun resolveMutableCustomPresetId(): String {
        val customName = customPresetNameForCurrentProfile()
        return if (!TextUtils.isEmpty(customName)) {
            userPresetIdForProfile(activeProfileIdForCustomPreset())
        } else {
            PRESET_CUSTOM
        }
    }

    private fun activeProfileIdForCustomPreset(): String {
        if (!TextUtils.isEmpty(boundProfileId)) return boundProfileId!!
        return AudioDeviceProfileStore.getActiveProfileId(preferences, null)
    }

    private fun profileCustomPresetNameKey(profileId: String): String =
        KEY_PROFILE_CUSTOM_PRESET_NAME_PREFIX + profileId

    private fun customPresetNameForCurrentProfile(): String? {
        val profileId = activeProfileIdForCustomPreset()
        val raw = preferences.getString(profileCustomPresetNameKey(profileId), null) ?: return null
        val value = raw.trim()
        return if (value.isEmpty()) null else value
    }

    private fun persistCustomPresetNameForCurrentProfile(presetName: String) {
        var cleanName = presetName.trim()
        if (cleanName.isEmpty()) return
        if (cleanName.length > 32) cleanName = cleanName.substring(0, 32)
        val profileId = activeProfileIdForCustomPreset()
        preferences.edit().putString(profileCustomPresetNameKey(profileId), cleanName).apply()
    }

    private fun promptForCustomPresetName(callback: (String) -> Unit) {
        if (!isAdded) return

        val context = requireContext()
        val input = EditText(context).apply {
            setSingleLine(true)
            hint = "Ejemplo: Mis audifonos"
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            val padding = dp(14)
            setPadding(padding, padding, padding, padding)
        }

        val nameDialog = MaterialAlertDialogBuilder(context)
            .setTitle("Nombre del preset")
            .setMessage("Ponle un nombre a tu preset personalizado para este dispositivo. Solo se pedira una vez.")
            .setView(input)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Guardar", null)
            .create()

        nameDialog.setOnShowListener {
            val positiveButton = nameDialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton.setOnClickListener {
                val value = input.text?.toString()?.trim().orEmpty()
                if (value.isEmpty()) {
                    input.error = "Escribe un nombre para el preset"
                    return@setOnClickListener
                }
                callback(value)
                nameDialog.dismiss()
            }
        }

        nameDialog.show()
    }

    private fun ensureBassDefaults() {
        val hasBassDb = preferences.contains(AudioEffectsService.KEY_BASS_DB)
        val hasBassType = preferences.contains(AudioEffectsService.KEY_BASS_TYPE)
        val hasBassFrequency = preferences.contains(AudioEffectsService.KEY_BASS_FREQUENCY_HZ)
        if (hasBassDb && hasBassType && hasBassFrequency) return

        val editor = preferences.edit()
        if (!hasBassDb) editor.putFloat(AudioEffectsService.KEY_BASS_DB, 0f)
        if (!hasBassType) editor.putInt(AudioEffectsService.KEY_BASS_TYPE, BASS_TYPE_DEFAULT)
        if (!hasBassFrequency) editor.putFloat(AudioEffectsService.KEY_BASS_FREQUENCY_HZ, BASS_DEFAULT_FREQUENCY_HZ)
        editor.apply()
    }

    private fun ensurePresetStateConsistency() {
        if (!preferences.contains(KEY_SELECTED_PRESET)) {
            // Si viene una curva restaurada sin metadata de preset, la respetamos como personalizada.
            val activeUserPresetId = resolveActiveUserPresetIdIfSynced()
            preferences.edit()
                .putString(KEY_SELECTED_PRESET, if (TextUtils.isEmpty(activeUserPresetId)) PRESET_CUSTOM else activeUserPresetId)
                .apply()
            return
        }

        val presetId = preferences.getString(KEY_SELECTED_PRESET, PRESET_DEFAULT)
        if (PRESET_CUSTOM == presetId) {
            val activeUserPresetId = resolveActiveUserPresetIdIfSynced()
            if (!TextUtils.isEmpty(activeUserPresetId)) {
                preferences.edit().putString(KEY_SELECTED_PRESET, activeUserPresetId).apply()
            }
            return
        }

        if (isUserPresetId(presetId)) {
            val userPreset = findUserPresetById(presetId)
            if (userPreset == null || !isUserPresetSynced(userPreset)) {
                val activeUserPresetId = resolveActiveUserPresetIdIfSynced()
                preferences.edit()
                    .putString(KEY_SELECTED_PRESET, if (TextUtils.isEmpty(activeUserPresetId)) PRESET_CUSTOM else activeUserPresetId)
                    .apply()
            }
            return
        }

        val selectedPreset = findPresetById(presetId)
        if (selectedPreset == null) {
            val activeUserPresetId = resolveActiveUserPresetIdIfSynced()
            preferences.edit()
                .putString(KEY_SELECTED_PRESET, if (TextUtils.isEmpty(activeUserPresetId)) PRESET_CUSTOM else activeUserPresetId)
                .apply()
            return
        }

        if (!isPresetSynced(selectedPreset)) {
            // Evita pisar una configuracion real del usuario cuando no coincide exactamente con un preset fijo.
            val activeUserPresetId = resolveActiveUserPresetIdIfSynced()
            preferences.edit()
                .putString(KEY_SELECTED_PRESET, if (TextUtils.isEmpty(activeUserPresetId)) PRESET_CUSTOM else activeUserPresetId)
                .apply()
        }
    }

    private fun resolveActiveUserPresetIdIfSynced(): String? {
        val activeProfileId = activeProfileIdForCustomPreset()
        if (TextUtils.isEmpty(activeProfileId)) return null

        val activeUserPresetId = userPresetIdForProfile(activeProfileId)
        val activeUserPreset = findUserPresetById(activeUserPresetId) ?: return null
        if (!isUserPresetSynced(activeUserPreset)) return null

        return activeUserPresetId
    }

    private fun isUserPresetSynced(preset: UserPresetEntry): Boolean {
        for (i in preset.bands.indices) {
            val stored = preferences.getFloat(AudioEffectsService.bandDbKey(i), 0f)
            if (Math.abs(stored - preset.bands[i]) > 0.01f) return false
        }
        return true
    }

    private fun isPresetSynced(preset: PresetConfig): Boolean {
        for (i in preset.bands.indices) {
            val stored = preferences.getFloat(AudioEffectsService.bandDbKey(i), 0f)
            if (Math.abs(stored - preset.bands[i]) > 0.01f) return false
        }
        return true
    }

    private fun savePresetValues(preset: PresetConfig) {
        val editor = preferences.edit()
        editor.putString(KEY_SELECTED_PRESET, preset.id)
        for (i in preset.bands.indices) {
            editor.putFloat(AudioEffectsService.bandDbKey(i), preset.bands[i])
        }
        editor.putFloat(AudioEffectsService.KEY_BASS_DB, quantizeBassGainToggle(preset.bassDb))
        editor.putFloat(AudioEffectsService.KEY_BASS_FREQUENCY_HZ, normalizeBassFrequencySlider(preset.bassFrequencyHz))
        editor.putInt(AudioEffectsService.KEY_BASS_TYPE, normalizeBassType(preset.bassType))
        editor.apply()
    }

    private fun quantizeBassGainToggle(rawValue: Float): Float =
        clamp(rawValue, BASS_GAIN_MIN_DB, BASS_GAIN_MAX_DB)

    private fun markPresetAsCustom() {
        val targetPresetId = resolveMutableCustomPresetId()
        val currentPreset = preferences.getString(KEY_SELECTED_PRESET, PRESET_DEFAULT)
        if (TextUtils.equals(currentPreset, targetPresetId)) return
        preferences.edit().putString(KEY_SELECTED_PRESET, targetPresetId).apply()
        tvSelectedPreset.text = presetLabelFromId(targetPresetId)
    }

    private fun findPresetById(presetId: String?): PresetConfig? {
        for (presetConfig in PRESET_CONFIGS) {
            if (presetConfig.id == presetId) return presetConfig
        }
        return null
    }

    private fun presetLabelFromId(presetId: String?): String {
        if (PRESET_CUSTOM == presetId) {
            val customName = customPresetNameForCurrentProfile()
            return if (TextUtils.isEmpty(customName)) "Personalizado" else customName!!
        }

        if (isUserPresetId(presetId)) {
            val userPreset = findUserPresetById(presetId)
            if (userPreset != null && userPreset.name.isNotEmpty()) return userPreset.name
            return "Preset usuario"
        }

        val preset = findPresetById(presetId)
        return preset?.label ?: "Predeterminado"
    }

    private fun deviceName(deviceInfo: AudioDeviceInfo, fallback: String): String {
        val productName = deviceInfo.productName ?: return fallback
        val value = productName.toString().trim()
        if (value.isEmpty() || "null".equals(value, ignoreCase = true)) return fallback
        return value
    }

    private fun startOrUpdateService() {
        if (isAdded && activity is MainActivity) {
            (activity as MainActivity).requestAudioEffectsApplyFromUi()
            return
        }

        val context = requireContext().applicationContext
        val intent = Intent(context, AudioEffectsService::class.java).apply {
            action = AudioEffectsService.ACTION_APPLY
        }
        try {
            context.startService(intent)
        } catch (_: Throwable) {
            // Fallback para OEMs que restringen foreground service de forma transitoria.
            context.startService(intent)
        }
    }

    private fun stopEqService() {
        if (isAdded && activity is MainActivity) {
            (activity as MainActivity).requestAudioEffectsStopFromUi()
            return
        }

        val context = requireContext().applicationContext
        val intent = Intent(context, AudioEffectsService::class.java).apply {
            action = AudioEffectsService.ACTION_STOP
        }
        runCatching { context.startService(intent) }
    }

    private fun formatDb(value: Float): String = String.format(Locale.US, "%.1f dB", value)

    private fun formatHz(value: Float): String = String.format(Locale.US, "%d Hz", Math.round(value))

    private fun normalizeBassFrequencySlider(rawValue: Float): Float =
        AudioEffectsService.normalizeBassFrequencySliderValue(rawValue)

    private fun withAlpha(color: Int, factor: Float): Int {
        val alpha = Math.round(Color.alpha(color) * factor)
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun clamp(value: Float, min: Float, max: Float): Float = maxOf(min, minOf(max, value))

    private fun normalizeBassType(rawType: Int): Int = when (rawType) {
        AudioEffectsService.BASS_TYPE_TRANSIENT_COMPRESSOR -> AudioEffectsService.BASS_TYPE_TRANSIENT_COMPRESSOR
        AudioEffectsService.BASS_TYPE_SUSTAIN_COMPRESSOR -> AudioEffectsService.BASS_TYPE_SUSTAIN_COMPRESSOR
        else -> AudioEffectsService.BASS_TYPE_NATURAL
    }

    private fun findBassTypeOption(bassType: Int): BassTypeOption? {
        val normalized = normalizeBassType(bassType)
        for (option in BASS_TYPE_OPTIONS) {
            if (option.value == normalized) return option
        }
        return null
    }

    private fun bassTypeLabelFromValue(bassType: Int): String =
        findBassTypeOption(bassType)?.label ?: BASS_TYPE_OPTIONS[0].label

    private fun dp(value: Int): Int = Math.round(value * resources.displayMetrics.density)

    private fun applyCardStyles(rootView: View?) {
        if (rootView == null) return
        val cardBg = 0xFF0D0D0D.toInt()
        val cardStroke = 0xFF1A1A1A.toInt()
        val cardIds = intArrayOf(
            R.id.cardPresetSelector,
            R.id.cardGraphicEq,
            R.id.cardBassTuner,
            R.id.cardBassTypeSelector
        )
        for (id in cardIds) {
            val v = rootView.findViewById<View>(id)
            if (v is MaterialCardView) {
                v.setCardBackgroundColor(ColorStateList.valueOf(cardBg))
                v.strokeColor = cardStroke
                v.strokeWidth = dp(1)
            }
        }
        val scrollContent = rootView.findViewById<View>(R.id.scrollEqContent)
        if (scrollContent is ViewGroup && scrollContent.childCount > 0) {
            val mainLinear = scrollContent.getChildAt(0)
            if (mainLinear is ViewGroup && mainLinear.childCount > 0) {
                val firstCard = mainLinear.getChildAt(0)
                if (firstCard is MaterialCardView) {
                    firstCard.setCardBackgroundColor(ColorStateList.valueOf(cardBg))
                    firstCard.strokeColor = cardStroke
                    firstCard.strokeWidth = dp(1)
                }
            }
        }
    }

    // ----- Data holders -----

    private class PresetConfig(
        val id: String,
        val label: String,
        val bands: FloatArray,
        val bassDb: Float = 0f,
        val bassFrequencyHz: Float = BASS_DEFAULT_FREQUENCY_HZ,
        val bassType: Int = BASS_TYPE_DEFAULT
    )

    private class BassTypeOption(val value: Int, val label: String)

    private class UserPresetEntry(
        val profileId: String,
        val id: String,
        val name: String,
        val bands: FloatArray
    )

    companion object {
        private const val KEY_SELECTED_PRESET = "selected_preset"
        private const val KEY_PROFILE_CUSTOM_PRESET_NAME_PREFIX = "profile_custom_preset_name_"
        private const val KEY_USER_PRESET_BAND_PREFIX = "user_preset_band_"
        private const val KEY_DEVICE_PROFILE_PREFIX = "device_profile_"
        private const val PRESET_DEFAULT = "default"
        private const val PRESET_CUSTOM = "custom"
        private const val PRESET_USER_PREFIX = "user_profile_"
        private const val PRESET_DARK = "dark"
        private const val PRESET_BRIGHT = "bright"
        private const val PRESET_BASS_BOOST = "bass_boost"
        private const val PRESET_BASS_REDUCTION = "bass_reduction"
        private const val PRESET_TREBLE_BOOST = "treble_boost"
        private const val PRESET_TREBLE_REDUCTION = "treble_reduction"
        private const val PRESET_VOCAL_BOOST = "vocal_boost"
        private const val PRESET_M_SHAPED = "m_shaped"
        private const val PRESET_U_SHAPED = "u_shaped"
        private const val PRESET_V_SHAPE = "v_shape"
        private const val PRESET_W_SHAPED = "w_shaped"
        private const val FORCE_SPEAKER_FALLBACK_WINDOW_MS = 4000L
        private const val OUTPUT_REFRESH_MIN_INTERVAL_MS = 900L

        private fun containsHeadphoneLikeOutput(devices: Array<AudioDeviceInfo>?): Boolean {
            if (devices.isNullOrEmpty()) return false
            val headphoneTypes = setOf(
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_HEARING_AID,
                AudioDeviceInfo.TYPE_USB_HEADSET
            )
            return devices.any { it.type in headphoneTypes }
        }
        private val EQ_BAND_COUNT = AudioEffectsService.EQ_BAND_COUNT
        private val BASS_GAIN_MAX_DB = AudioEffectsService.BASS_DB_MAX
        private const val BASS_GAIN_MIN_DB = 0f

        private val BASS_DEFAULT_FREQUENCY_HZ = AudioEffectsService.BASS_FREQUENCY_DEFAULT_HZ
        private val BASS_TYPE_DEFAULT = AudioEffectsService.BASS_TYPE_NATURAL

        private val GRAPHIC_EQ_DIALOG_FREQUENCIES_HZ = floatArrayOf(
            62.5f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f
        )

        private val PRESET_CONFIGS: Array<PresetConfig> = arrayOf(
            PresetConfig(PRESET_DEFAULT, "Plano", floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f), 0f, BASS_DEFAULT_FREQUENCY_HZ, AudioEffectsService.BASS_TYPE_NATURAL),
            PresetConfig(PRESET_DARK, "Oscuro", floatArrayOf(4.0f, 3.0f, 2.0f, 1.0f, 0.0f, -1.0f, -2.0f, -3.0f, -4.0f), 0f, BASS_DEFAULT_FREQUENCY_HZ, AudioEffectsService.BASS_TYPE_NATURAL),
            PresetConfig(PRESET_BRIGHT, "Brillante", floatArrayOf(-4.0f, -3.0f, -2.0f, -1.0f, 0.0f, 1.0f, 2.0f, 3.0f, 4.0f), 0f, BASS_DEFAULT_FREQUENCY_HZ, AudioEffectsService.BASS_TYPE_NATURAL),
            PresetConfig(PRESET_BASS_BOOST, "Refuerzo de graves", floatArrayOf(5.5f, 4.5f, 1.5f, 0.2f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f), 3.0f, AudioEffectsService.bassSliderFromCutoffHz(80f), AudioEffectsService.BASS_TYPE_NATURAL),
            PresetConfig(PRESET_BASS_REDUCTION, "Reduccion de graves", floatArrayOf(-5.5f, -4.5f, -1.5f, -0.2f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f), 0f, BASS_DEFAULT_FREQUENCY_HZ, AudioEffectsService.BASS_TYPE_NATURAL),
            PresetConfig(PRESET_TREBLE_BOOST, "Refuerzo de agudos", floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.2f, 1.5f, 5.3f, 3.8f), 0f, BASS_DEFAULT_FREQUENCY_HZ, AudioEffectsService.BASS_TYPE_NATURAL),
            PresetConfig(PRESET_TREBLE_REDUCTION, "Reduccion de agudos", floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, -0.2f, -1.5f, -5.3f, -3.8f), 0f, BASS_DEFAULT_FREQUENCY_HZ, AudioEffectsService.BASS_TYPE_NATURAL),
            PresetConfig(PRESET_VOCAL_BOOST, "Refuerzo vocal", floatArrayOf(-1.0f, -1.1f, -0.6f, 0.7f, 3.1f, 4.6f, 3.3f, 0.6f, 0.0f), 0f, BASS_DEFAULT_FREQUENCY_HZ, AudioEffectsService.BASS_TYPE_NATURAL),
            PresetConfig(PRESET_M_SHAPED, "Forma M", floatArrayOf(-3.0f, 0.0f, 2.8f, 0.5f, -2.0f, 0.5f, 2.8f, 0.0f, -3.0f), 0f, BASS_DEFAULT_FREQUENCY_HZ, AudioEffectsService.BASS_TYPE_NATURAL),
            PresetConfig(PRESET_U_SHAPED, "Forma U", floatArrayOf(4.0f, 0.0f, -2.0f, -2.6f, -2.7f, -2.6f, -2.0f, 0.0f, 4.0f), 0f, BASS_DEFAULT_FREQUENCY_HZ, AudioEffectsService.BASS_TYPE_NATURAL),
            PresetConfig(PRESET_V_SHAPE, "Forma V", floatArrayOf(4.0f, 2.9f, 0.2f, -2.3f, -4.0f, -2.3f, 0.2f, 2.9f, 4.0f), 0f, BASS_DEFAULT_FREQUENCY_HZ, AudioEffectsService.BASS_TYPE_NATURAL),
            PresetConfig(PRESET_W_SHAPED, "Forma W", floatArrayOf(3.0f, 0.0f, -2.8f, -0.5f, 2.0f, -0.5f, -2.8f, 0.0f, 3.0f), 0f, BASS_DEFAULT_FREQUENCY_HZ, AudioEffectsService.BASS_TYPE_NATURAL)
        )

        private val BASS_TYPE_OPTIONS: Array<BassTypeOption> = arrayOf(
            BassTypeOption(AudioEffectsService.BASS_TYPE_NATURAL, "Natural"),
            BassTypeOption(AudioEffectsService.BASS_TYPE_TRANSIENT_COMPRESSOR, "Compresor de transitorios"),
            BassTypeOption(AudioEffectsService.BASS_TYPE_SUSTAIN_COMPRESSOR, "Compresor de sustain")
        )
    }
}
