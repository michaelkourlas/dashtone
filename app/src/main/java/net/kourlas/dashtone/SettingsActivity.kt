package net.kourlas.dashtone

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import dagger.hilt.android.AndroidEntryPoint

/**
 * Dashtone's visible screen. Reachable via the car's native "app settings" affordance
 * (android.intent.action.APPLICATION_PREFERENCES) rather than a separate launcher entry: AAOS
 * media apps get a settings icon injected into their own media-browsing header for free once this
 * intent filter is declared, so a second app-grid icon alongside the media source isn't needed.
 * Music browsing itself is hosted by the car's Media Center from the MediaLibraryService in
 * :service, not from here.
 */
@AndroidEntryPoint
class SettingsActivity :
    AppCompatActivity(R.layout.activity_settings),
    PreferenceFragmentCompat.OnPreferenceStartScreenCallback {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
        // Restores the "Settings" title once back navigation returns to the root screen --
        // onPreferenceStartScreen below is the only place that ever changes it away from that.
        supportFragmentManager.addOnBackStackChangedListener {
            if (supportFragmentManager.backStackEntryCount == 0) {
                title = getString(R.string.settings_title)
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    // Pushes the tapped nested PreferenceScreen (e.g. "Included Folders") as its own
    // SettingsFragment instance, rooted at that screen via the standard ARG_PREFERENCE_ROOT
    // argument -- the AndroidX Preference framework's documented mechanism for sub-screens backed
    // by a single shared XML resource. Added to the back stack so the toolbar/system back action
    // returns to the parent screen rather than exiting Settings entirely.
    override fun onPreferenceStartScreen(caller: PreferenceFragmentCompat, pref: PreferenceScreen): Boolean {
        val fragment = SettingsFragment().apply {
            arguments = Bundle().apply { putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, pref.key) }
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.settings_container, fragment)
            .addToBackStack(pref.key)
            .commit()
        title = pref.title
        return true
    }
}
