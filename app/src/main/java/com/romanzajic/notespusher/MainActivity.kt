package com.romanzajic.notespusher

import android.os.Bundle
import android.util.Base64
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

/**
 * The entire app is this one screen: a text area and a button.
 *
 * Tapping the button pushes whatever's typed to the configured GitHub
 * repo's root directory (or GH_SUBDIR, if set) as a new file named with
 * the current date and time, e.g. "2026-07-27_14-32-05.md". There is no
 * reading, listing, or editing of existing notes here on purpose — this
 * app only ever writes new ones.
 *
 * Configuration (token/repo/branch/subdir) is read at RUNTIME from a
 * plain properties file bundled in the app's assets
 * ("assets/config.properties") — not baked in at compile time via
 * BuildConfig. This is deliberately the simplest possible mechanism:
 * assets are just files copied verbatim into the APK by the standard
 * build pipeline, so there's no Gradle codegen involved at all. See
 * config.properties.example for the format, and
 * .github/workflows/build-apk.yml for how CI writes the real file from
 * encrypted repo secrets before building.
 *
 * On success the text area is cleared, ready for the next note. On
 * failure the typed text is left exactly as-is so nothing is lost, and a
 * Toast explains what went wrong.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var noteInput: EditText
    private lateinit var pushButton: Button
    private lateinit var config: Properties

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        noteInput = findViewById(R.id.noteInput)
        pushButton = findViewById(R.id.pushButton)
        config = loadConfig()

        pushButton.setOnClickListener { onPushClicked() }
    }

    /** Reads assets/config.properties, if present. Returns an empty
     *  Properties object (not an exception) if the file is missing or
     *  unreadable — handled as "not configured" in pushNoteToGitHub(). */
    private fun loadConfig(): Properties {
        val props = Properties()
        try {
            assets.open("config.properties").use { props.load(it) }
        } catch (e: Exception) {
            // No config bundled — left empty on purpose, see above.
        }
        return props
    }

    private fun onPushClicked() {
        val text = noteInput.text.toString()
        if (text.isBlank()) {
            Toast.makeText(this, getString(R.string.toast_empty), Toast.LENGTH_SHORT).show()
            return
        }

        setBusy(true)
        Thread {
            val errorMessage = pushNoteToGitHub(text)
            runOnUiThread {
                setBusy(false)
                if (errorMessage == null) {
                    noteInput.text.clear()
                    Toast.makeText(this, getString(R.string.toast_saved), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun setBusy(busy: Boolean) {
        pushButton.isEnabled = !busy
        pushButton.text = getString(if (busy) R.string.btn_pushing else R.string.btn_push)
        noteInput.isEnabled = !busy
    }

    /** Runs on a background thread. Returns null on success, or a
     *  human-readable error message to show in a Toast on failure. */
    private fun pushNoteToGitHub(text: String): String? {
        val token = config.getProperty("GH_TOKEN", "")
        val repo = config.getProperty("GH_REPO", "")
        val branch = config.getProperty("GH_BRANCH", "main").ifBlank { "main" }
        val subdir = config.getProperty("GH_SUBDIR", "").trim('/')

        if (token.isBlank() || repo.isBlank() || !repo.contains("/")) {
            return getString(R.string.error_not_configured)
        }

        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val filename = "$stamp.md"
        val path = if (subdir.isNotEmpty()) "$subdir/$filename" else filename

        var connection: HttpURLConnection? = null
        return try {
            val url = URL("https://api.github.com/repos/$repo/contents/$path")
            val conn = url.openConnection() as HttpURLConnection
            connection = conn
            conn.requestMethod = "PUT"
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("Authorization", "token $token")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")

            val encodedContent = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val body = JSONObject()
                .put("message", "Add note $stamp via Notes app")
                .put("content", encodedContent)
                .put("branch", branch)

            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_CREATED || code == HttpURLConnection.HTTP_OK) {
                null
            } else {
                val stream = conn.errorStream ?: conn.inputStream
                val errBody = stream?.bufferedReader()?.readText().orEmpty()
                "GitHub error ($code): ${errBody.take(200)}"
            }
        } catch (e: Exception) {
            "Network error: ${e.message}"
        } finally {
            connection?.disconnect()
        }
    }
}
