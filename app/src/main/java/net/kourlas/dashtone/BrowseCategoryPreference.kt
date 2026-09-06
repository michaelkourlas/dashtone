package net.kourlas.dashtone

import android.content.Context
import android.widget.Button
import android.widget.CheckBox
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import net.kourlas.dashtone.service.data.BrowseCategory

/**
 * A settings row for one [BrowseCategory]: an enable/disable checkbox plus up/down buttons for
 * reordering. AndroidX Preference has no built-in drag-reorder support and a car settings screen
 * has no room for drag gestures anyway, so up/down buttons are the simplest reliable mechanism.
 */
class BrowseCategoryPreference(
    context: Context,
    val category: BrowseCategory,
    private val enabled: Boolean,
    private val canMoveUp: Boolean,
    private val canMoveDown: Boolean,
    private val onToggle: (Boolean) -> Unit,
    private val onMoveUp: () -> Unit,
    private val onMoveDown: () -> Unit,
) : Preference(context) {

    init {
        layoutResource = R.layout.preference_browse_category
        isSelectable = false
        isPersistent = false
        title = context.getString(category.displayNameRes)
        key = "pref_browse_category_${category.name}"
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val checkBox = holder.findViewById(R.id.browse_category_checkbox) as CheckBox
        checkBox.setOnCheckedChangeListener(null)
        checkBox.isChecked = enabled
        checkBox.setOnCheckedChangeListener { _, isChecked -> onToggle(isChecked) }

        val upButton = holder.findViewById(R.id.browse_category_up) as Button
        upButton.isEnabled = canMoveUp
        upButton.setOnClickListener { onMoveUp() }

        val downButton = holder.findViewById(R.id.browse_category_down) as Button
        downButton.isEnabled = canMoveDown
        downButton.setOnClickListener { onMoveDown() }
    }
}
