package com.bearinmind.equalizer314.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import com.bearinmind.equalizer314.R
import org.json.JSONObject

/**
 * Adapter for every preset dropdown on the Audio Output screen: name left,
 * [PresetCurveView] preview right; sentinel rows ("(none)", "<name> (missing)")
 * render the empty grid. Extends [ArrayAdapter] so MaterialAutoCompleteTextView
 * filtering keeps working.
 */
class PresetDropdownAdapter(
    context: Context,
    private val entries: List<Entry>,
) : ArrayAdapter<String>(
    context,
    R.layout.item_preset_dropdown,
    R.id.presetRowName,
    entries.map { it.displayName },
) {

    /** One dropdown row.
     *  @param displayName row name text
     *  @param presetJson  full preset JSON for the curve (includes
     *                     `channelSideEqEnabled` so CSE presets stack L/R);
     *                     null for sentinel rows. */
    data class Entry(
        val displayName: String,
        val presetJson: JSONObject?,
        /** "Disable EQ" sentinel row — rendered with red text and an
         *  "N/A" placeholder instead of a curve preview. */
        val isDisable: Boolean = false,
    )

    private val inflater = LayoutInflater.from(context)

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
        bind(position, convertView, parent)

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
        bind(position, convertView, parent)

    private fun bind(position: Int, convertView: View?, parent: ViewGroup): View {
        val row = convertView ?: inflater.inflate(R.layout.item_preset_dropdown, parent, false)
        val entry = entries[position]
        val nameView = row.findViewById<TextView>(R.id.presetRowName)
        val curve = row.findViewById<PresetCurveView>(R.id.presetRowCurve)
        val naView = row.findViewById<TextView>(R.id.presetRowNA)
        val preampView = row.findViewById<TextView>(R.id.presetRowPreamp)
        nameView.text = entry.displayName

        if (entry.isDisable) {
            // "Disable EQ": red name, "N/A" instead of curve, no preamp line.
            // Rows are recycled — the non-disable branch must restore defaults.
            nameView.setTextColor(0xFFEF9A9A.toInt())
            curve.visibility = View.GONE
            naView.visibility = View.VISIBLE
            preampView.visibility = View.GONE
            return row
        }

        // Default (non-disable) styling — restore in case this view was
        // recycled from a disable row.
        nameView.setTextColor(
            com.google.android.material.color.MaterialColors.getColor(
                nameView, com.google.android.material.R.attr.colorOnSurface,
            )
        )
        naView.visibility = View.GONE
        curve.visibility = View.VISIBLE
        curve.setPreset(entry.presetJson)
        // Preamp subtitle only for real presets; sentinel rows have no JSON —
        // hide the line rather than misleadingly show "0.0 dB".
        val json = entry.presetJson
        if (json == null) {
            preampView.visibility = View.GONE
        } else {
            preampView.visibility = View.VISIBLE
            preampView.text = formatPreamp(json.optDouble("preamp", 0.0))
        }
        return row
    }

    companion object {
        /** Renders a preamp value as "+8.0 dB" / "-7.0 dB" / "0.0 dB".
         *  Always one decimal place; positive values explicitly carry
         *  a `+` so the dropdown subtitle reads naturally next to the
         *  preset name. */
        fun formatPreamp(value: Double): String =
            if (value == 0.0) "0.0 dB" else "%+.1f dB".format(value)
    }
}
