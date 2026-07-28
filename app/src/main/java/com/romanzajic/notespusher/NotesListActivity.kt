package com.romanzajic.notespusher

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Properties

/**
 * Read-only: lists every ".md" file's full path anywhere in the repo (one
 * Git Trees API call, recursive), sorted alphabetically. Sorting by path
 * naturally groups notes by folder (e.g. everything under "Notes/Daily/"
 * sits together) without needing an actual expandable tree widget — kept
 * intentionally simple. Tapping a row opens NoteViewActivity to read it.
 */
class NotesListActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var emptyLabel: TextView
    private lateinit var progress: ProgressBar
    private lateinit var config: Properties
    private var notePaths: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notes_list)

        listView = findViewById(R.id.notesListView)
        emptyLabel = findViewById(R.id.emptyLabel)
        progress = findViewById(R.id.listProgress)
        config = loadConfig()

        listView.setOnItemClickListener { _, _, position, _ ->
            val path = notePaths[position]
            val intent = Intent(this, NoteViewActivity::class.java)
            intent.putExtra("path", path)
            startActivity(intent)
        }

        loadNotesList()
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

    private fun loadNotesList() {
        progress.visibility = View.VISIBLE
        emptyLabel.visibility = View.GONE
        Thread {
            val result = fetchNoteList()
            runOnUiThread {
                progress.visibility = View.GONE
                when (result) {
                    is ListResult.Success -> {
                        notePaths = result.paths
                        if (notePaths.isEmpty()) {
                            emptyLabel.text = getString(R.string.no_notes_found)
                            emptyLabel.visibility = View.VISIBLE
                        } else {
                            listView.adapter = ArrayAdapter(this, R.layout.list_item_note, R.id.itemPathText, notePaths)
                        }
                    }
                    is ListResult.Error -> {
                        emptyLabel.text = result.message
                        emptyLabel.visibility = View.VISIBLE
                    }
                }
            }
        }.start()
    }

    private sealed class ListResult {
        data class Success(val paths: List<String>) : ListResult()
        data class Error(val message: String) : ListResult()
    }

    /** Runs on a background thread. */
    private fun fetchNoteList(): ListResult {
        val token = config.getProperty("GH_TOKEN", "")
        val repo = config.getProperty("GH_REPO", "")
        val branch = config.getProperty("GH_BRANCH", "main").ifBlank { "main" }

        if (token.isBlank() || repo.isBlank() || !repo.contains("/")) {
            return ListResult.Error(getString(R.string.error_not_configured))
        }

        var connection: HttpURLConnection? = null
        return try {
            val url = URL("https://api.github.com/repos/$repo/git/trees/$branch?recursive=1")
            val conn = url.openConnection() as HttpURLConnection
            connection = conn
            conn.requestMethod = "GET"
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("Authorization", "token $token")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")

            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                val stream = conn.errorStream ?: conn.inputStream
                val errBody = stream?.bufferedReader()?.readText().orEmpty()
                return ListResult.Error("GitHub error ($code): ${errBody.take(200)}")
            }

            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val json = JSONObject(body)
            val tree: JSONArray = json.getJSONArray("tree")
            val paths = mutableListOf<String>()
            for (i in 0 until tree.length()) {
                val entry = tree.getJSONObject(i)
                if (entry.optString("type") == "blob") {
                    val path = entry.optString("path")
                    if (path.endsWith(".md")) paths.add(path)
                }
            }
            paths.sort()
            ListResult.Success(paths)
        } catch (e: Exception) {
            ListResult.Error("Network error: ${e.message}")
        } finally {
            connection?.disconnect()
        }
    }
}
