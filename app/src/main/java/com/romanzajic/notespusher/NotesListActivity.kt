package com.romanzajic.notespusher

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Properties

/**
 * Lists every ".md" file in the repo as an expandable folder tree, mirroring
 * the desktop Notes module's sidebar: folders are built from "/" in each
 * file's path, nested to any depth, sorted alphabetically (with a "Daily"
 * folder pinned first at the root), and tapping a folder toggles it open
 * or closed. Tapping a file opens NoteViewActivity.
 *
 * Search filters the tree two ways at once, same as the desktop app:
 *  - instantly, by filename/path (done locally, no network call)
 *  - by phrase-in-content, using GitHub's code search API (one extra
 *    network call, debounced) -- this is the Android equivalent of the
 *    desktop app's server-side full-text search, without needing to
 *    download and grep every note on the phone.
 * While a search is active every folder is force-expanded so matches are
 * visible without manual expanding, same as the desktop behavior.
 */
class NotesListActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var emptyLabel: TextView
    private lateinit var progress: ProgressBar
    private lateinit var searchInput: EditText
    private lateinit var config: Properties
    private lateinit var adapter: TreeAdapter

    private var files: List<String> = emptyList()
    private val expandedFolders = mutableSetOf<String>()
    private var matchingFiles: Set<String>? = null // null = no active search filter
    private var searchQuery: String = ""

    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null
    private var searchToken = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notes_list)

        listView = findViewById(R.id.notesListView)
        emptyLabel = findViewById(R.id.emptyLabel)
        progress = findViewById(R.id.listProgress)
        searchInput = findViewById(R.id.noteSearchInput)
        config = loadConfig()

        adapter = TreeAdapter()
        listView.adapter = adapter

        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString().orEmpty().trim()
                searchRunnable?.let { searchHandler.removeCallbacks(it) }
                val r = Runnable { runSearch(q) }
                searchRunnable = r
                searchHandler.postDelayed(r, 200)
            }
        })

        loadNotesList()
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

    // ── Fetch file list ─────────────────────────────────────────────

    private fun loadNotesList() {
        progress.visibility = View.VISIBLE
        emptyLabel.visibility = View.GONE
        Thread {
            val result = fetchNoteList()
            runOnUiThread {
                progress.visibility = View.GONE
                when (result) {
                    is ListResult.Success -> {
                        files = result.paths
                        rebuildRows()
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

    // ── Search ───────────────────────────────────────────────────────

    private fun runSearch(query: String) {
        searchQuery = query
        searchToken++
        if (query.isEmpty()) {
            matchingFiles = null
            rebuildRows()
            return
        }
        val local = files.filter { it.contains(query, ignoreCase = true) }.toSet()
        matchingFiles = local
        rebuildRows()

        val token = searchToken
        Thread {
            val remote = fetchContentSearchMatches(query)
            if (remote.isNotEmpty()) {
                runOnUiThread {
                    if (token == searchToken) {
                        matchingFiles = local + remote
                        rebuildRows()
                    }
                }
            }
        }.start()
    }

    /** Runs on a background thread. Uses GitHub's code search (searches file
     *  contents, not just filenames) as the phone-side equivalent of the
     *  desktop app's server-side full-text search. Best-effort: on any
     *  error (rate limit, no results, etc.) it just returns nothing extra,
     *  since the local filename filter above already covers the fast path. */
    private fun fetchContentSearchMatches(query: String): Set<String> {
        val token = config.getProperty("GH_TOKEN", "")
        val repo = config.getProperty("GH_REPO", "")
        if (token.isBlank() || repo.isBlank()) return emptySet()

        var connection: HttpURLConnection? = null
        return try {
            val q = "\"$query\" in:file repo:$repo"
            val url = URL("https://api.github.com/search/code?q=" + URLEncoder.encode(q, "UTF-8"))
            val conn = url.openConnection() as HttpURLConnection
            connection = conn
            conn.requestMethod = "GET"
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("Authorization", "token $token")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")

            if (conn.responseCode != HttpURLConnection.HTTP_OK) return emptySet()
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val items = JSONObject(body).optJSONArray("items") ?: JSONArray()
            val out = mutableSetOf<String>()
            for (i in 0 until items.length()) {
                val p = items.getJSONObject(i).optString("path")
                if (p.endsWith(".md")) out.add(p)
            }
            out
        } catch (e: Exception) {
            emptySet()
        } finally {
            connection?.disconnect()
        }
    }

    // ── Tree building ────────────────────────────────────────────────

    private class TreeNode {
        val children = LinkedHashMap<String, TreeNode>()
        val items = mutableListOf<Pair<String, String>>() // path, label
    }

    private sealed class Row {
        data class Folder(val name: String, val fullPath: String, val depth: Int, val expanded: Boolean) : Row()
        data class FileRow(val path: String, val label: String, val depth: Int) : Row()
    }

    private fun rebuildRows() {
        val visibleFiles = matchingFiles?.let { m -> files.filter { m.contains(it) } } ?: files

        val root = TreeNode()
        visibleFiles.forEach { path ->
            val parts = path.split("/")
            val label = parts.last().removeSuffix(".md")
            var node = root
            for (i in 0 until parts.size - 1) {
                node = node.children.getOrPut(parts[i]) { TreeNode() }
            }
            node.items.add(path to label)
        }

        val forceOpen = matchingFiles != null
        val rows = mutableListOf<Row>()
        flatten(root, "", 0, forceOpen, rows)
        adapter.rows = rows
        adapter.notifyDataSetChanged()

        if (forceOpen && visibleFiles.isEmpty()) {
            emptyLabel.text = getString(R.string.no_matching_notes)
            emptyLabel.visibility = View.VISIBLE
        } else if (visibleFiles.isEmpty()) {
            emptyLabel.text = getString(R.string.no_notes_found)
            emptyLabel.visibility = View.VISIBLE
        } else {
            emptyLabel.visibility = View.GONE
        }
    }

    private fun flatten(node: TreeNode, folderPath: String, depth: Int, forceOpen: Boolean, out: MutableList<Row>) {
        node.children.keys.sortedWith(compareBy(
            { if (folderPath.isEmpty() && it != DAILY_FOLDER_NAME) 1 else 0 },
            { it.lowercase() }
        )).forEach { name ->
            val fullPath = if (folderPath.isEmpty()) name else "$folderPath/$name"
            val expanded = forceOpen || expandedFolders.contains(fullPath)
            out.add(Row.Folder(name, fullPath, depth, expanded))
            if (expanded) {
                flatten(node.children.getValue(name), fullPath, depth + 1, forceOpen, out)
            }
        }
        node.items.sortedBy { it.second.lowercase() }.forEach { (path, label) ->
            out.add(Row.FileRow(path, label, depth))
        }
    }

    private fun toggleFolder(fullPath: String) {
        if (!expandedFolders.add(fullPath)) expandedFolders.remove(fullPath)
        rebuildRows()
    }

    private fun openNote(path: String) {
        val intent = Intent(this, NoteViewActivity::class.java)
        intent.putExtra("path", path)
        startActivity(intent)
    }

    // ── List rendering ───────────────────────────────────────────────

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun highlighted(label: String): CharSequence {
        if (matchingFiles == null || searchQuery.isEmpty()) return label
        val spannable = SpannableString(label)
        val lower = label.lowercase()
        val q = searchQuery.lowercase()
        var idx = lower.indexOf(q)
        while (idx >= 0) {
            spannable.setSpan(
                BackgroundColorSpan(ContextCompat.getColor(this, R.color.amber)),
                idx, idx + q.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            idx = lower.indexOf(q, idx + q.length)
        }
        return spannable
    }

    private inner class TreeAdapter : BaseAdapter() {
        var rows: List<Row> = emptyList()

        override fun getCount() = rows.size
        override fun getItem(position: Int): Row = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Folder) 0 else 1

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = rows[position]
            val view = convertView ?: LayoutInflater.from(this@NotesListActivity)
                .inflate(R.layout.list_item_note, parent, false)
            val tv = view.findViewById<TextView>(R.id.itemPathText)

            when (row) {
                is Row.Folder -> {
                    tv.text = (if (row.expanded) "\u25BE  " else "\u25B8  ") + row.name
                    tv.setTypeface(null, Typeface.BOLD)
                    tv.setTextColor(ContextCompat.getColor(this@NotesListActivity, R.color.ink))
                    tv.setPadding(dp(16 + row.depth * 16), dp(10), dp(16), dp(10))
                    view.setBackgroundColor(ContextCompat.getColor(this@NotesListActivity, R.color.surface_page))
                    view.setOnClickListener { toggleFolder(row.fullPath) }
                }
                is Row.FileRow -> {
                    tv.text = highlighted(row.label)
                    tv.setTypeface(null, Typeface.NORMAL)
                    tv.setTextColor(ContextCompat.getColor(this@NotesListActivity, R.color.ink))
                    tv.setPadding(dp(16 + row.depth * 16 + 16), dp(12), dp(16), dp(12))
                    view.setBackgroundColor(ContextCompat.getColor(this@NotesListActivity, R.color.surface_card))
                    view.setOnClickListener { openNote(row.path) }
                }
            }
            return view
        }
    }

    companion object {
        private const val DAILY_FOLDER_NAME = "Daily"
    }
}
