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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class LanguagePacksSheet(
    private val activity: AppCompatActivity,
    private val onChanged: () -> Unit,
) {
    private val dialog = BottomSheetDialog(activity)
    private val list: LinearLayout
    private var downloaded: Set<String> = emptySet()

    init {
        val view = activity.layoutInflater.inflate(R.layout.sheet_languages, null)
        list = view.findViewById(R.id.languageList)
        dialog.setContentView(view)
        dialog.behavior.skipCollapsed = true
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    fun show() {
        dialog.show()
        refresh()
    }

    private fun refresh() {
        activity.lifecycleScope.launch {
            downloaded = runCatching { Translator.downloadedLanguages() }.getOrDefault(emptySet())
            render()
        }
    }

    private fun render() {
        val target = Translator.defaultTarget(activity)

        val codes = Translator.languages.sortedWith(
            compareBy<String> { it !in downloaded }.thenBy { Translator.displayName(it) }
        )
        list.removeAllViews()
        for (code in codes) list.addView(row(code, code == target))
    }

    private fun row(code: String, isTarget: Boolean): View {
        val row = activity.layoutInflater.inflate(R.layout.item_language, list, false) as ViewGroup
        val name = Translator.displayName(code)
        val status = row.findViewById<TextView>(R.id.langStatus)
        val action = row.findViewById<ImageButton>(R.id.langAction)
        val progress = row.findViewById<CircularProgressIndicator>(R.id.langProgress)

        row.findViewById<ImageView>(R.id.langCheck).isInvisible = !isTarget
        row.findViewById<TextView>(R.id.langName).text = name

        val busy = code in Translator.busyLanguages
        progress.isVisible = busy
        action.isInvisible = busy
        when {
            busy -> status.setText(R.string.language_downloading)
            Translator.isBuiltIn(code) -> {
                status.setText(R.string.language_built_in)
                action.isInvisible = true
            }
            code in downloaded -> {
                status.setText(R.string.language_downloaded)
                action.setImageResource(R.drawable.ic_delete)
                action.contentDescription = activity.getString(R.string.language_delete)
                action.setOnClickListener { confirmDelete(code, name) }
            }
            else -> {
                status.setText(R.string.language_not_downloaded)
                action.setImageResource(R.drawable.ic_download)
                action.contentDescription = activity.getString(R.string.language_download)
                action.setOnClickListener { download(code) }
            }
        }

        row.setOnClickListener {
            Translator.saveTarget(activity, code)
            onChanged()
            render()
        }
        return row
    }

    private fun download(code: String) {
        Translator.busyLanguages.add(code)
        render()
        activity.lifecycleScope.launch {
            try {
                Translator.downloadLanguage(code)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("TextGrab", "Model download failed for $code", e)
                Snackbar.make(
                    dialog.window?.decorView ?: list,
                    activity.getString(R.string.language_download_failed, e.localizedMessage ?: ""),
                    Snackbar.LENGTH_LONG
                ).show()
            } finally {
                Translator.busyLanguages.remove(code)
            }
            onChanged()
            refresh()
        }
    }

    private fun confirmDelete(code: String, name: String) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.language_delete_confirm, name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.language_delete) { _, _ ->
                activity.lifecycleScope.launch {
                    runCatching { Translator.deleteLanguage(code) }
                        .onFailure { android.util.Log.e("TextGrab", "Model delete failed for $code", it) }
                    onChanged()
                    refresh()
                }
            }
            .show()
    }
}
