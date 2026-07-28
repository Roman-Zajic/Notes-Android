package com.romanzajic.notespusher

import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Properties

/**
 * Read-only: fetches one note's raw text via the Contents API (using the
 * "raw" Accept header, so the response body IS the file content directly
 * — no base64/JSON unwrapping needed) and shows it in a scrollable,
 * selectable (but not editable) text view.
 */
class NoteViewActivity : AppCompatActivity() {

    private lateinit var titleBar: TextView
    private lateinit var contentView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var config: Properties

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_note_view)

        titleBar = findViewById(R.id.noteTitleBar)
        contentView = findViewById(R.id.noteContentView)
        progress = findViewById(R.id.viewProgress)
        config = loadConfig()

        val path = intent.getStringExtra("path").orEmpty()
        titleBar.text = path

        loadNoteContent(path)
    }

    private fun loadConfig(): Properties {
        val props = Properties()
        try {
            assets.open("config.properties").use { props.load(it) }
        } catch (e: Exception) {
            // No config bundled — handled as "not configured" below.
        }
        return props
    }

    private fun loadNoteContent(path: String) {
        progress.visibility = View.VISIBLE
        Thread {
            val text = fetchRawContent(path)
            runOnUiThread {
                progress.visibility = View.GONE
                contentView.text = text
            }
        }.start()
    }

    /** Runs on a background thread. Returns the note's text, or a
     *  human-readable error message to display in place of it. */
    private fun fetchRawContent(path: String): String {
        val token = config.getProperty("GH_TOKEN", "")
        val repo = config.getProperty("GH_REPO", "")
        val branch = config.getProperty("GH_BRANCH", "main").ifBlank { "main" }

        if (token.isBlank() || repo.isBlank()) {
            return getString(R.string.error_not_configured)
        }

        var connection: HttpURLConnection? = null
        return try {
            // Percent-encode each path segment (not the "/" separators).
            // URLEncoder turns spaces into "+", which is wrong for a URL
            // PATH (only correct in a query string), so it's corrected
            // back to "%20" afterwards.
            val encodedPath = path.split("/").joinToString("/") { segment ->
                URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
            }
            val url = URL("https://api.github.com/repos/$repo/contents/$encodedPath?ref=$branch")
            val conn = url.openConnection() as HttpURLConnection
            connection = conn
            conn.requestMethod = "GET"
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("Authorization", "token $token")
            conn.setRequestProperty("Accept", "application/vnd.github.raw")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")

            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_OK) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
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
