package com.romanzajic.notespusher

import android.os.Bundle
import android.util.Base64
import android.view.View
import android.webkit.WebView
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Properties

/**
 * Shows one note and lets it be edited and pushed back.
 *
 * Two modes, toggled by a single button in the title bar:
 *  - Preview: markdown rendered as HTML in a WebView, styled with the
 *    same teal/amber "financial models" palette used by the desktop
 *    Notes module (see PREVIEW_CSS below). Rendering itself is done by
 *    marked.js loaded from a CDN inside the WebView -- no markdown
 *    parser is bundled into the app, keeping this simple.
 *  - Edit: a plain multi-line EditText with the raw markdown text.
 *
 * Loading a note fetches it via the Contents API with the default
 * (JSON) Accept header rather than "raw", because the JSON response
 * carries the file's blob "sha" alongside its base64 content -- and
 * that sha is required to PUT an update back to the same file later.
 * One GET now saves a second round trip before every save.
 */
class NoteViewActivity : AppCompatActivity() {

    private lateinit var titleText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var editText: EditText
    private lateinit var previewWeb: WebView
    private lateinit var toggleModeBtn: TextView
    private lateinit var saveBtn: TextView
    private lateinit var config: Properties

    private var path: String = ""
    private var sha: String? = null
    private var isPreview = true
    private var loaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_note_view)

        titleText = findViewById(R.id.noteTitleText)
        progress = findViewById(R.id.viewProgress)
        editText = findViewById(R.id.noteEditText)
        previewWeb = findViewById(R.id.notePreviewWeb)
        toggleModeBtn = findViewById(R.id.toggleModeBtn)
        saveBtn = findViewById(R.id.saveBtn)
        config = loadConfig()

        previewWeb.settings.javaScriptEnabled = true

        path = intent.getStringExtra("path").orEmpty()
        titleText.text = path

        toggleModeBtn.setOnClickListener { toggleMode() }
        saveBtn.setOnClickListener { onSaveClicked() }

        loadNote(path)
    }

    private fun loadConfig(): Properties {
        val props = Properties()
        try {
            assets.open("config.properties").use { props.load(it) }
        } catch (e: Exception) {
            // No config bundled -- handled as "not configured" below.
        }
        return props
    }

    // ── Mode toggle ──────────────────────────────────────────────────

    private fun toggleMode() {
        isPreview = !isPreview
        applyModeVisibility()
        if (isPreview) renderPreview(editText.text.toString())
    }

    /** Button always shows the mode you'd switch TO, matching the desktop app. */
    private fun applyModeVisibility() {
        editText.visibility = if (isPreview) View.GONE else View.VISIBLE
        previewWeb.visibility = if (isPreview) View.VISIBLE else View.GONE
        saveBtn.visibility = if (isPreview) View.GONE else View.VISIBLE
        toggleModeBtn.text = getString(if (isPreview) R.string.btn_edit else R.string.btn_preview)
    }

    // ── Load ─────────────────────────────────────────────────────────

    private fun loadNote(path: String) {
        progress.visibility = View.VISIBLE
        Thread {
            val result = fetchNote(path)
            runOnUiThread {
                progress.visibility = View.GONE
                loaded = result.error == null
                if (result.error != null) {
                    editText.setText(result.error)
                    isPreview = false
                    applyModeVisibility()
                } else {
                    sha = result.sha
                    editText.setText(result.text)
                    isPreview = true
                    applyModeVisibility()
                    renderPreview(result.text.orEmpty())
                }
            }
        }.start()
    }

    private data class NoteResult(val text: String?, val sha: String?, val error: String?)

    /** Runs on a background thread. */
    private fun fetchNote(path: String): NoteResult {
        val token = config.getProperty("GH_TOKEN", "")
        val repo = config.getProperty("GH_REPO", "")
        val branch = config.getProperty("GH_BRANCH", "main").ifBlank { "main" }

        if (token.isBlank() || repo.isBlank()) {
            return NoteResult(null, null, getString(R.string.error_not_configured))
        }

        var connection: HttpURLConnection? = null
        return try {
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
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")

            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_OK) {
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                val json = JSONObject(body)
                val b64 = json.optString("content").replace("\n", "")
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                NoteResult(String(bytes, Charsets.UTF_8), json.optString("sha"), null)
            } else {
                val stream = conn.errorStream ?: conn.inputStream
                val errBody = stream?.bufferedReader()?.readText().orEmpty()
                NoteResult(null, null, "GitHub error ($code): ${errBody.take(200)}")
            }
        } catch (e: Exception) {
            NoteResult(null, null, "Network error: ${e.message}")
        } finally {
            connection?.disconnect()
        }
    }

    // ── Save ─────────────────────────────────────────────────────────

    private fun onSaveClicked() {
        if (!loaded) {
            Toast.makeText(this, R.string.error_save_failed, Toast.LENGTH_LONG).show()
            return
        }
        val text = editText.text.toString()
        saveBtn.isEnabled = false
        saveBtn.text = getString(R.string.btn_saving)
        Thread {
            val result = saveNoteToGitHub(path, text, sha)
            runOnUiThread {
                saveBtn.isEnabled = true
                saveBtn.text = getString(R.string.btn_save)
                if (result == null) {
                    Toast.makeText(this, R.string.toast_note_saved, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, result, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** Runs on a background thread. Returns null on success (and updates
     *  `sha` to the new blob sha so a second save doesn't 409), or a
     *  human-readable error message on failure. */
    private fun saveNoteToGitHub(path: String, text: String, currentSha: String?): String? {
        val token = config.getProperty("GH_TOKEN", "")
        val repo = config.getProperty("GH_REPO", "")
        val branch = config.getProperty("GH_BRANCH", "main").ifBlank { "main" }

        if (token.isBlank() || repo.isBlank() || currentSha.isNullOrBlank()) {
            return getString(R.string.error_not_configured)
        }

        var connection: HttpURLConnection? = null
        return try {
            val encodedPath = path.split("/").joinToString("/") { segment ->
                URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
            }
            val url = URL("https://api.github.com/repos/$repo/contents/$encodedPath")
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
                .put("message", "Edit note $path via Notes app")
                .put("content", encodedContent)
                .put("sha", currentSha)
                .put("branch", branch)

            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_CREATED) {
                val respBody = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                sha = JSONObject(respBody).optJSONObject("content")?.optString("sha") ?: sha
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

    // ── Markdown preview ────────────────────────────────────────────

    private fun renderPreview(markdown: String) {
        val jsString = JSONObject.quote(markdown) // safely-escaped JS string literal
        val html = """
            <!DOCTYPE html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>$PREVIEW_CSS</style>
            </head><body>
            <div id="content">Loading…</div>
            <script src="https://cdnjs.cloudflare.com/ajax/libs/marked/12.0.0/marked.min.js"></script>
            <script>
              try {
                document.getElementById('content').innerHTML = marked.parse($jsString);
              } catch (e) {
                document.getElementById('content').textContent = $jsString;
              }
            </script>
            </body></html>
        """.trimIndent()
        previewWeb.loadDataWithBaseURL("https://notes-app.local/", html, "text/html", "UTF-8", null)
    }

    companion object {
        // Trimmed-down version of the desktop app's ".preview-area" rules,
        // using the same teal/amber "financial models" palette (see
        // colors.xml) so notes look consistent across both apps.
        private const val PREVIEW_CSS = """
            body { margin: 0; padding: 16px; background: #FFFFFF; color: #012D2C;
                   font-family: -apple-system, Roboto, "Segoe UI", sans-serif;
                   font-size: 15px; line-height: 1.6; }
            h1, h2, h3 { font-weight: 600; margin: 1em 0 0.5em; }
            h1 { font-size: 1.4rem; }
            h2 { font-size: 1.15rem; padding-bottom: 6px; border-bottom: 2px solid #95DFDB; }
            h3 { font-size: 1rem; }
            p { margin: 0 0 0.8em; }
            ul, ol { padding-left: 1.4em; margin: 0 0 0.8em; }
            li { margin: 0.25em 0; }
            hr { border: none; height: 2px; background: #D0ECEB; border-radius: 2px; margin: 1em 0; }
            blockquote { margin: 0 0 0.8em; padding: 10px 16px; border-left: 4px solid #E1A01E;
                         background: #F0FAFA; font-style: italic; color: #7A9E9E; }
            a { color: #008282; }
            table { width: 100%; border-collapse: collapse; margin: 0 0 1em; font-size: 0.9em; }
            th { background: #005A5A; color: #fff; text-align: left; padding: 8px 12px; }
            td { padding: 8px 12px; border-bottom: 1px solid #D0ECEB; }
            pre { background: #F0FAFA; border: 1px solid #D0ECEB; border-radius: 8px;
                  padding: 12px; overflow-x: auto; white-space: pre-wrap; }
            code { font-family: monospace; font-size: 0.85em; }
            pre code { background: none; padding: 0; }
            input[type="checkbox"] { margin-right: 6px; }
            mark { background: #E1A01E; color: #012D2C; border-radius: 2px; }
        """
    }
}
