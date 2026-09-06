package net.kourlas.dashtone

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import com.mukesh.MarkDown

/**
 * Renders one of the documents linked from the About section (changelog, license, privacy
 * policy, third-party notices) as Markdown. Reached only via an explicit intent carrying a
 * [DocumentType] name in the "document" extra -- see preferences.xml's About category -- so an
 * unrecognized value here means the intent was built wrong, not something a real user can trigger.
 */
class MarkdownActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val documentType = DocumentType.fromName(intent.getStringExtra(EXTRA_DOCUMENT))
        if (documentType == null) {
            finish()
            return
        }

        setContentView(R.layout.activity_markdown)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(documentType.titleRes)

        val markdown = applicationContext.assets.open(documentType.fileName)
            .bufferedReader()
            .use { it.readText() }
        findViewById<ComposeView>(R.id.compose_view).setContent {
            MarkDown(text = markdown)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private enum class DocumentType(val titleRes: Int, val fileName: String) {
        CHANGELOG(R.string.preferences_about_changelog_title, "CHANGES.md"),
        LICENSE(R.string.preferences_about_license_title, "LICENSE.md"),
        NOTICE(R.string.preferences_about_third_party_embedded_title, "NOTICE"),
        PACKAGE_LICENSES(R.string.preferences_about_third_party_packages_title, "PACKAGE_LICENSES.md"),
        PRIVACY(R.string.preferences_about_privacy_title, "PRIVACY.md");

        companion object {
            fun fromName(name: String?) = entries.firstOrNull { it.name == name }
        }
    }

    private companion object {
        const val EXTRA_DOCUMENT = "document"
    }
}
