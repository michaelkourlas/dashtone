package net.kourlas.dashtone

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.preference.CheckBoxPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.kourlas.dashtone.service.data.BrowseCategory
import net.kourlas.dashtone.service.data.mediastore.AudioPermission
import net.kourlas.dashtone.service.data.mediastore.AudioPermissionMonitor
import net.kourlas.dashtone.service.data.mediastore.VolumeLabels
import net.kourlas.dashtone.service.data.prefs.AppPreferences
import net.kourlas.dashtone.service.data.repository.LibraryRepository
import net.kourlas.dashtone.service.shuffle.CategoryShuffleMode
import javax.inject.Inject

@AndroidEntryPoint
class SettingsFragment : PreferenceFragmentCompat() {

    @Inject
    lateinit var libraryRepository: LibraryRepository

    @Inject
    lateinit var appPreferences: AppPreferences

    @Inject
    lateinit var audioPermissionMonitor: AudioPermissionMonitor

    // False when this fragment instance is showing the root screen, true when it's showing the
    // nested "Included Folders" sub-screen (see SettingsActivity.onPreferenceStartScreen) -- the
    // two screens share this one Fragment class but have almost entirely disjoint preference
    // hierarchies (findPreference only searches the CURRENT root's subtree), so
    // onCreatePreferences/onResume branch on this rather than making every lookup nullable-safe
    // for both cases.
    private var isIncludedFoldersScreen = false

    private lateinit var permissionPreference: Preference
    private lateinit var rescanPreference: Preference
    private lateinit var includedFoldersPreference: PreferenceScreen
    private lateinit var browseCategoriesCategory: PreferenceCategory
    private lateinit var categoryShuffleModePreference: ListPreference
    private lateinit var foldersContainer: PreferenceGroup

    // If a request comes back denied, shouldRequestPermissionRationale distinguishes "denied,
    // but the system will still show the prompt again" from "denied permanently (don't ask
    // again)" -- only meaningful to check after at least one request has actually been made.
    private var wasDeniedOnce = false

    private val requestPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            lifecycleScope.launch {
                // force = true: this callback only ever fires from the user actively responding
                // to the system permission dialog right now, never from an automatic trigger --
                // the same deliberate, in-the-moment category as tapping "Rescan Library," which
                // expects the library to reflect the file system immediately, not after the delay
                // meant for an automatic scan that might be racing a storage-mount timing issue.
                libraryRepository.rescan(force = true)
                // This call is what actually updates the shared AudioPermissionMonitor --
                // checking AudioPermission.isGranted() again for this fragment's own UI does not.
                // Must run after the rescan completes, not before -- see
                // DashtoneMediaLibraryService.updatePermissionState's collector, which reacts to
                // this flip by telling the browse tree to re-fetch. Without this call, the car's
                // media session keeps reporting the permission error until something else happens
                // to call refresh().
                audioPermissionMonitor.refresh()
                refreshRescanSummary()
            }
        } else {
            wasDeniedOnce = true
            audioPermissionMonitor.refresh()
        }
        refreshPermissionState()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        if (rootKey == KEY_INCLUDED_FOLDERS_SCREEN) {
            isIncludedFoldersScreen = true
            foldersContainer = preferenceScreen
            return
        }

        permissionPreference = findPreference("pref_permission")!!
        rescanPreference = findPreference("pref_rescan")!!
        includedFoldersPreference = findPreference(KEY_INCLUDED_FOLDERS_SCREEN)!!
        browseCategoriesCategory = findPreference("pref_category_browse_categories")!!
        categoryShuffleModePreference = findPreference("pref_category_shuffle_mode")!!
        categoryShuffleModePreference.setOnPreferenceChangeListener { _, newValue ->
            lifecycleScope.launch {
                libraryRepository.setCategoryShuffleMode(CategoryShuffleMode.valueOf(newValue as String))
            }
            true
        }
        // PreferenceScreen.onClick() -- verified straight from the AndroidX Preference source --
        // silently no-ops (never calls the navigation listener) if getPreferenceCount() == 0.
        // Since the real folder checkboxes are only populated once the user has *already*
        // navigated into this sub-screen (refreshFolderCheckboxes(), a separate fragment
        // instance's separate preference hierarchy -- populating them here wouldn't even be
        // visible there), this screen is empty at the moment it's tapped on the root screen
        // unless something is added to it now. A single throwaway placeholder is enough to
        // satisfy that count check and let the tap actually navigate.
        if (includedFoldersPreference.preferenceCount == 0) {
            includedFoldersPreference.addPreference(Preference(requireContext()))
        }
        rescanPreference.setOnPreferenceClickListener {
            rescanPreference.summary = getString(R.string.syncing_library_message)
            lifecycleScope.launch {
                // force = true: an explicit, deliberate tap on "Rescan Library" means sync to the
                // file system right now, not after the delay an automatic scan waits out in case
                // it's racing a storage-mount timing issue -- see LibraryScanner.scanLibrary's own
                // doc comment.
                libraryRepository.rescan(force = true)
                refreshRescanSummary()
                refreshIncludedFoldersSummary()
            }
            true
        }

        findPreference<Preference>("pref_about_changelog")!!.summary =
            getString(R.string.preferences_about_version_summary, BuildConfig.VERSION_NAME)
    }

    override fun onResume() {
        super.onResume()
        if (isIncludedFoldersScreen) {
            lifecycleScope.launch { refreshFolderCheckboxes() }
            return
        }
        // The user may have granted the permission from system Settings and come back.
        refreshPermissionState()
        lifecycleScope.launch {
            refreshRescanSummary()
            refreshIncludedFoldersSummary()
            refreshBrowseCategories()
            refreshCategoryShuffleMode()
        }
    }

    private fun refreshPermissionState() {
        val context = requireContext()
        val granted = AudioPermission.isGranted(context)
        val canRequestAgain = !wasDeniedOnce || shouldShowRequestPermissionRationale(AudioPermission.PERMISSION)
        when {
            granted -> {
                permissionPreference.summary = getString(R.string.permission_granted_message)
                permissionPreference.onPreferenceClickListener = null
            }
            canRequestAgain -> {
                permissionPreference.summary = getString(R.string.permission_rationale_message)
                permissionPreference.setOnPreferenceClickListener {
                    requestPermission.launch(AudioPermission.PERMISSION)
                    true
                }
            }
            else -> {
                permissionPreference.summary = getString(R.string.permission_denied_message)
                permissionPreference.setOnPreferenceClickListener {
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        ),
                    )
                    true
                }
            }
        }
    }

    private suspend fun refreshRescanSummary() {
        val lastScan = appPreferences.lastScanTimestampMillis.first()
        rescanPreference.summary = if (lastScan == 0L) {
            getString(R.string.settings_last_synced_never)
        } else {
            val relative = DateUtils.getRelativeTimeSpanString(lastScan, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            getString(R.string.settings_last_synced_format, relative)
        }
    }

    // Shown on the root screen's "Included Folders" row so the user can see at a glance whether
    // anything's excluded without having to open the sub-screen.
    private suspend fun refreshIncludedFoldersSummary() {
        val folders = libraryRepository.availableFolders()
        val excluded = libraryRepository.excludedFolders.first()
        val includedCount = folders.count { it.key !in excluded }
        includedFoldersPreference.summary = if (folders.isEmpty() || includedCount == folders.size) {
            resources.getQuantityString(R.plurals.settings_included_folders_summary_all, folders.size, folders.size)
        } else {
            resources.getQuantityString(
                R.plurals.settings_included_folders_summary_some, folders.size, includedCount, folders.size,
            )
        }
    }

    // Rebuilt (not diffed) each time: the folder list itself can change between scans (a folder
    // appearing/disappearing on disk), and this screen is only visited occasionally, so simplicity
    // wins over preserving individual Preference view instances across a refresh.
    private suspend fun refreshFolderCheckboxes() {
        val folders = libraryRepository.availableFolders()
        val excluded = libraryRepository.excludedFolders.first()
        foldersContainer.removeAll()
        if (folders.isEmpty()) {
            foldersContainer.addPreference(
                Preference(requireContext()).apply {
                    title = getString(R.string.settings_no_folders_found)
                    isSelectable = false
                },
            )
            return
        }
        // Two different volumes (internal storage, an SD card/USB drive) can each have a
        // same-named top-level folder (e.g. "Music") -- only label which physical storage a
        // folder lives on when that's actually ambiguous; in the common single-volume case it'd
        // just be redundant noise on every row.
        val showVolumeLabel = folders.map { it.volumeName }.distinct().size > 1
        for (folder in folders) {
            foldersContainer.addPreference(
                CheckBoxPreference(requireContext()).apply {
                    key = "pref_folder_${folder.key}"
                    title = folder.topLevelFolder
                    if (showVolumeLabel) summary = VolumeLabels.resolve(requireContext(), folder.volumeName)
                    isPersistent = false
                    isChecked = folder.key !in excluded
                    setOnPreferenceChangeListener { _, newValue ->
                        onFolderToggled(folder.key, included = newValue as Boolean)
                        true
                    }
                },
            )
        }
    }

    // rescanPreference/refreshRescanSummary aren't reachable from here -- this screen is a
    // separate SettingsFragment instance rooted at the folders sub-screen, so the root screen's
    // preferences aren't part of its hierarchy. That's fine: setExcludedFolders() below triggers
    // its own rescan, and navigating back to the root screen re-runs its onResume(), which
    // re-reads the now-current last-synced timestamp fresh -- no cross-screen signaling needed.
    private fun onFolderToggled(folderKey: String, included: Boolean) {
        lifecycleScope.launch {
            val current = libraryRepository.excludedFolders.first()
            val updated = if (included) current - folderKey else current + folderKey
            libraryRepository.setExcludedFolders(updated)
        }
    }

    // Rebuilt (not diffed) on every change, same rationale as refreshFolders: this screen is
    // visited rarely, so simplicity wins over preserving individual Preference view instances.
    private suspend fun refreshBrowseCategories() {
        val order = libraryRepository.categoryOrder.first()
        val disabled = libraryRepository.disabledCategories.first()
        browseCategoriesCategory.removeAll()
        order.forEachIndexed { index, category ->
            browseCategoriesCategory.addPreference(
                BrowseCategoryPreference(
                    context = requireContext(),
                    category = category,
                    enabled = category !in disabled,
                    canMoveUp = index > 0,
                    canMoveDown = index < order.lastIndex,
                    onToggle = { isEnabled -> onCategoryToggled(category, isEnabled, disabled) },
                    onMoveUp = { onCategoryMoved(order, index, index - 1) },
                    onMoveDown = { onCategoryMoved(order, index, index + 1) },
                ),
            )
        }
    }

    private fun onCategoryToggled(category: BrowseCategory, enabled: Boolean, currentDisabled: Set<BrowseCategory>) {
        // Disabling the last enabled category would leave the browse tree with no root content at
        // all -- refuse rather than let the user paint themselves into that corner.
        if (!enabled && currentDisabled.size >= browseCategoryCount() - 1) {
            lifecycleScope.launch { refreshBrowseCategories() }
            return
        }
        lifecycleScope.launch {
            val updated = if (enabled) currentDisabled - category else currentDisabled + category
            libraryRepository.setDisabledCategories(updated)
            refreshBrowseCategories()
        }
    }

    private fun onCategoryMoved(order: List<BrowseCategory>, from: Int, to: Int) {
        if (to !in order.indices) return
        lifecycleScope.launch {
            val mutable = order.toMutableList()
            mutable.add(to, mutable.removeAt(from))
            libraryRepository.setCategoryOrder(mutable)
            refreshBrowseCategories()
        }
    }

    private fun browseCategoryCount(): Int = BrowseCategory.entries.size

    // ListPreference's own "%s" summary auto-fills from whichever entry matches the current
    // value, so setting the value here is all that's needed to keep the summary in sync too.
    private suspend fun refreshCategoryShuffleMode() {
        categoryShuffleModePreference.value = libraryRepository.categoryShuffleMode.first().name
    }

    companion object {
        const val KEY_INCLUDED_FOLDERS_SCREEN = "pref_screen_included_folders"
    }
}
