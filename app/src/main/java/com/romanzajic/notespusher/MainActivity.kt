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

/**
 * The entire app is this one screen: a text area and a button.
 *
 * Tapping the button pushes whatever's typed to the configured GitHub
 * repo's root directory (or GH_SUBDIR, if set — see build.gradle) as a
 * new file named with the current date and time, e.g.
 * "2026-07-27_14-32-05.md". There is no reading, listing, or editing of
 * existing notes here on purpose — this app only ever writes new ones.
 *
 * On success the text area is cleared, ready for the next note. On
 * failure the typed text is left exactly as-is so nothing is lost, and a
 * Toast explains what went wrong.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var noteInput: EditText
    private lateinit var pushButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        noteInput = findViewById(R.id.noteInput)
        pushButton = findViewById(R.id.pushButton)

        pushButton.setOnClickListener { onPushClicked() }
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
        val token = BuildConfig.GH_TOKEN
        val repo = BuildConfig.GH_REPO
        val branch = BuildConfig.GH_BRANCH.ifBlank { "main" }
        val subdir = BuildConfig.GH_SUBDIR.trim('/')

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
