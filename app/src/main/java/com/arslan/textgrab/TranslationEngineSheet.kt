package com.arslan.textgrab

import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Lets the user pick which backend translates, and give the cloud ones their
 * API key. Tapping a row selects the engine; the gear opens its settings.
 * [onChanged] fires whenever the selection or a configuration changes.
 */
class TranslationEngineSheet(
    private val activity: AppCompatActivity,
    private val onChanged: () -> Unit,
) {
    private val dialog = BottomSheetDialog(activity)
    private val list: LinearLayout

    init {
        val view = activity.layoutInflater.inflate(R.layout.sheet_engines, null)
        list = view.findViewById(R.id.engineList)
        dialog.setContentView(view)
        dialog.behavior.skipCollapsed = true
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    fun show() {
        render()
        dialog.show()
    }

    private fun render() {
        val selected = EngineSettings.selectedId(activity)
        list.removeAllViews()
        for (info in EngineSettings.engines) list.addView(row(info, info.id == selected))
    }

    private fun row(info: EngineSettings.Info, isSelected: Boolean): View {
        val row = activity.layoutInflater.inflate(R.layout.item_engine, list, false) as ViewGroup
        val ready = EngineSettings.isReady(activity, info.id)

        row.findViewById<ImageView>(R.id.engineCheck).isInvisible = !isSelected
        row.findViewById<TextView>(R.id.engineName).text = activity.getString(info.title)
        row.findViewById<TextView>(R.id.engineSummary).setText(info.summary)
        row.findViewById<TextView>(R.id.engineStatus).setText(
            when {
                !info.isCloud -> R.string.engine_offline
                ready -> R.string.engine_configured
                else -> R.string.engine_not_configured
            }
        )

        val configure = row.findViewById<ImageButton>(R.id.engineConfigure)
        configure.isVisible = info.isCloud
        configure.setOnClickListener { configure(info) }

        row.setOnClickListener {
            // Selecting an engine that cannot run yet goes straight to its setup.
            if (!ready) {
                configure(info)
            } else {
                EngineSettings.select(activity, info.id)
                render()
                onChanged()
            }
        }
        return row
    }

    /** Endpoint, model and key of one cloud engine, with a live "Test" button. */
    private fun configure(info: EngineSettings.Info) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_engine_config, null)
        val config = EngineSettings.config(activity, info.id)

        val keyLayout = view.findViewById<TextInputLayout>(R.id.keyLayout)
        val keyInput = view.findViewById<TextInputEditText>(R.id.keyInput)
        val modelLayout = view.findViewById<TextInputLayout>(R.id.modelLayout)
        val modelInput = view.findViewById<TextInputEditText>(R.id.modelInput)
        val endpointLayout = view.findViewById<TextInputLayout>(R.id.endpointLayout)
        val endpointInput = view.findViewById<TextInputEditText>(R.id.endpointInput)
        val presets = view.findViewById<ChipGroup>(R.id.presetGroup)
        val testButton = view.findViewById<MaterialButton>(R.id.btnTest)
        val testResult = view.findViewById<TextView>(R.id.testResult)
        val help = view.findViewById<TextView>(R.id.configHelp)

        help.isVisible = info.keyHelp != 0
        if (info.keyHelp != 0) help.setText(info.keyHelp)
        keyLayout.isVisible = info.needsKey
        modelLayout.isVisible = info.hasModel
        endpointLayout.isVisible = info.hasEndpoint
        keyInput.setText(config.key)
        modelInput.setText(config.model)
        endpointInput.setText(config.endpoint)
        // An empty endpoint means "pick it from the key" (DeepL free vs pro).
        endpointLayout.placeholderText =
            info.defaultEndpoint.ifBlank { activity.getString(R.string.engine_endpoint_auto) }

        view.findViewById<View>(R.id.presetScroll).isVisible = info.presets.isNotEmpty()
        for (preset in info.presets) {
            presets.addView(
                Chip(activity).apply {
                    text = preset.label
                    isCheckable = false
                    setOnClickListener {
                        endpointInput.setText(preset.endpoint)
                        modelInput.setText(preset.model)
                    }
                }
            )
        }

        fun current() = EngineSettings.Config(
            endpoint = endpointInput.text?.toString()?.trim().orEmpty()
                .ifBlank { info.defaultEndpoint },
            model = modelInput.text?.toString()?.trim().orEmpty().ifBlank { info.defaultModel },
            key = keyInput.text?.toString()?.trim().orEmpty(),
        )

        var testJob: Job? = null
        testButton.setOnClickListener {
            val config = current()
            if (!EngineSettings.isUsable(info, config)) {
                testResult.text = activity.getString(R.string.engine_not_configured)
                return@setOnClickListener
            }
            testJob?.cancel()
            testButton.isEnabled = false
            testResult.setText(R.string.engine_testing)
            testJob = activity.lifecycleScope.launch {
                val target = Translator.defaultTarget(activity)
                val result = runCatching {
                    engineFor(info.id, config).translate(
                        TranslationRequest(
                            texts = listOf(SAMPLE),
                            sources = listOf("en"),
                            target = target,
                        )
                    ).firstOrNull().orEmpty()
                }
                testButton.isEnabled = true
                result.onSuccess {
                    testResult.text = activity.getString(R.string.engine_test_ok, it.trim())
                }.onFailure { e ->
                    if (e is CancellationException) throw e
                    android.util.Log.w("TextGrab", "Engine test failed", e)
                    testResult.text = activity.getString(
                        R.string.engine_test_failed, e.localizedMessage ?: e.javaClass.simpleName
                    )
                }
            }
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle(info.title)
            .setView(view)
            .setNeutralButton(R.string.engine_clear) { _, _ ->
                EngineSettings.clear(activity, info.id)
                if (EngineSettings.selectedId(activity) == info.id) {
                    EngineSettings.select(activity, EngineSettings.ON_DEVICE)
                }
                render()
                onChanged()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> testJob?.cancel() }
            .setPositiveButton(R.string.engine_save) { _, _ ->
                testJob?.cancel()
                val config = current()
                EngineSettings.saveConfig(
                    activity, info.id, config.endpoint, config.model, config.key
                )
                // Saving a working key is the moment the user means to use it.
                if (EngineSettings.isReady(activity, info.id)) {
                    EngineSettings.select(activity, info.id)
                }
                render()
                onChanged()
            }
            .setOnDismissListener { testJob?.cancel() }
            .show()
    }

    /** Builds an engine straight from unsaved dialog values, for the test run. */
    private fun engineFor(id: String, config: EngineSettings.Config): TranslationEngine =
        when (id) {
            EngineSettings.DEEPL -> DeepLEngine(config)
            EngineSettings.GOOGLE -> GoogleTranslateEngine(config)
            EngineSettings.OPENAI -> OpenAiEngine(config)
            EngineSettings.ANTHROPIC -> AnthropicEngine(config)
            else -> MlKitEngine
        }

    private companion object {
        const val SAMPLE = "Settings are saved automatically."
    }
}
