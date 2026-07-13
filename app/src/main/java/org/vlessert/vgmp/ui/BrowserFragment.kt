package org.vlessert.vgmp.ui

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.vlessert.vgmp.MainActivity
import org.vlessert.vgmp.databinding.FragmentBrowserBinding
import org.vlessert.vgmp.playlists.PlaylistStore
import org.vlessert.vgmp.playlists.PlaylistTrack
import org.vlessert.vgmp.playback.TrackRef
import org.vlessert.vgmp.playback.SupportedFormats
import org.vlessert.vgmp.playback.ZipArchiveStore
import org.vlessert.vgmp.service.VgmPlaybackService
import org.vlessert.vgmp.settings.SettingsManager

class BrowserFragment : Fragment() {
    data class Entry(
        val uri: Uri,
        val name: String,
        val directory: Boolean,
        val playable: Boolean = false,
        val target: Location? = null,
        val track: TrackRef? = null
    )

    sealed interface Location {
        data class Document(val uri: Uri) : Location
        data class Zip(val archiveUri: Uri, val archiveName: String, val path: String = "") : Location
    }

    private var _binding: FragmentBrowserBinding? = null
    private val binding get() = _binding!!
    private val entries = mutableListOf<Entry>()
    private val history = ArrayDeque<Location>()
    private lateinit var adapter: EntryAdapter
    private var rootUri: Uri? = null
    private var currentLocation: Location? = null
    private var loadGeneration = 0

    private val chooseFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@registerForActivityResult
        requireContext().contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(
            uri, DocumentsContract.getTreeDocumentId(uri)
        )
        rootUri = documentUri
        currentLocation = Location.Document(documentUri)
        history.clear()
        prefs().edit().putString(KEY_ROOT, documentUri.toString()).putString(KEY_CURRENT, documentUri.toString()).apply()
        loadDirectory()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentBrowserBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        adapter = EntryAdapter(entries, ::openEntry, ::addToPlaylist)
        binding.recyclerEntries.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerEntries.adapter = adapter
        binding.btnChooseFolder.setOnClickListener { chooseFolder.launch(null) }
        binding.btnUp.setOnClickListener { navigateUp() }

        rootUri = prefs().getString(KEY_ROOT, null)?.let(Uri::parse)
        currentLocation = (prefs().getString(KEY_CURRENT, null)?.let(Uri::parse) ?: rootUri)
            ?.let(Location::Document)
        loadDirectory()
    }

    fun navigateUp(): Boolean {
        val parent = history.removeLastOrNull() ?: run {
            val document = currentLocation as? Location.Document ?: return false
            persistedParent()?.let(Location::Document) ?: return false
        }
        currentLocation = parent
        if (parent is Location.Document) {
            prefs().edit().putString(KEY_CURRENT, parent.uri.toString()).apply()
        }
        loadDirectory()
        return true
    }

    private fun loadDirectory() {
        val location = currentLocation
        val generation = ++loadGeneration
        binding.btnUp.isEnabled = when (location) {
            is Location.Zip -> true
            is Location.Document -> location.uri != rootUri
            null -> false
        }
        if (location == null) {
            binding.tvPath.text = "Browse"
            binding.tvEmpty.visibility = View.VISIBLE
            entries.clear()
            adapter.notifyDataSetChanged()
            return
        }
        entries.clear()
        adapter.notifyDataSetChanged()
        binding.recyclerEntries.visibility = View.GONE
        binding.progress.visibility = View.VISIBLE
        binding.tvEmpty.visibility = View.GONE
        viewLifecycleOwner.lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    when (location) {
                        is Location.Document -> queryChildren(location.uri)
                        is Location.Zip -> queryZip(location)
                    }
                }
            }
            if (generation != loadGeneration) return@launch
            if (loaded.isFailure) {
                binding.progress.visibility = View.GONE
                binding.recyclerEntries.visibility = View.VISIBLE
                Toast.makeText(requireContext(), "Could not open this folder or ZIP archive", Toast.LENGTH_LONG).show()
                return@launch
            }
            entries.clear()
            entries.addAll(loaded.getOrThrow())
            adapter.notifyDataSetChanged()
            binding.progress.visibility = View.GONE
            binding.recyclerEntries.visibility = View.VISIBLE
            binding.tvPath.text = when (location) {
                is Location.Document -> location.uri.lastPathSegment
                    ?.substringAfterLast(':')?.ifEmpty { "Music" } ?: "Music"
                is Location.Zip -> if (location.path.isEmpty()) location.archiveName
                    else "${location.archiveName}/${location.path}"
            }
            binding.tvEmpty.text = "This folder is empty"
            binding.tvEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun queryChildren(directory: Uri): List<Entry> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            directory, DocumentsContract.getDocumentId(directory)
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        val result = mutableListOf<Entry>()
        requireContext().contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val name = cursor.getString(1) ?: continue
                val mime = cursor.getString(2)
                val isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR
                val uri = DocumentsContract.buildDocumentUriUsingTree(directory, id)
                val browseZip = !isDirectory && name.endsWith(".zip", ignoreCase = true) &&
                    SettingsManager.isZipBrowsingEnabled(requireContext())
                val track = if (!isDirectory && SupportedFormats.supports(name)) TrackRef(uri, name) else null
                result += Entry(
                    uri = uri,
                    name = name,
                    directory = isDirectory || browseZip,
                    playable = track != null,
                    target = when {
                        isDirectory -> Location.Document(uri)
                        browseZip -> Location.Zip(uri, name)
                        else -> null
                    },
                    track = track
                )
            }
        }
        return result.sortedWith(compareBy<Entry> { !it.directory }.thenBy { it.name.lowercase() })
    }

    private fun queryZip(location: Location.Zip): List<Entry> =
        ZipArchiveStore(requireContext()).list(location.archiveUri, location.path).map { item ->
            val track = if (!item.directory && SupportedFormats.supports(item.displayName)) {
                TrackRef(location.archiveUri, item.displayName, archiveEntry = item.path)
            } else null
            Entry(
                uri = location.archiveUri,
                name = item.displayName,
                directory = item.directory,
                playable = track != null,
                target = if (item.directory) location.copy(path = item.path) else null,
                track = track
            )
        }

    private fun persistedParent(): Uri? {
        val current = (currentLocation as? Location.Document)?.uri ?: return null
        val root = rootUri ?: return null
        if (current == root) return null
        return runCatching {
            val rootId = DocumentsContract.getDocumentId(root)
            val currentId = DocumentsContract.getDocumentId(current)
            val parentId = parentDocumentId(rootId, currentId)
            DocumentsContract.buildDocumentUriUsingTree(root, parentId)
        }.getOrDefault(root)
    }

    private fun openEntry(entry: Entry) {
        entry.target?.let { target ->
            currentLocation?.let(history::addLast)
            currentLocation = target
            if (target is Location.Document) {
                prefs().edit().putString(KEY_CURRENT, target.uri.toString()).apply()
            }
            loadDirectory()
            return
        }
        val selectedTrack = entry.track
        if (selectedTrack == null) {
            Toast.makeText(requireContext(), "${entry.name} is not supported yet", Toast.LENGTH_SHORT).show()
            return
        }
        val tracks = entries.mapNotNull { it.track }
        val start = tracks.indexOf(selectedTrack)
        val queue = tracks
        (activity as? MainActivity)?.getService()?.playQueue(queue, start)
        if (SettingsManager.openPlayerOnSelection(requireContext())) {
            (activity as? MainActivity)?.selectPlayerTab()
        }
    }

    private fun addToPlaylist(entry: Entry) {
        val track = entry.track ?: return
        val playlists = PlaylistStore.getAll(requireContext())
        val labels = (playlists.map { it.name } + "＋ New playlist").toTypedArray()
        AlertDialog.Builder(requireContext()).setTitle("Add to playlist").setItems(labels) { _, index ->
            if (index == playlists.size) createPlaylistWith(entry)
            else {
                PlaylistStore.addTrack(
                    requireContext(), playlists[index].id,
                    PlaylistTrack(track.uri, track.displayName, track.archiveEntry)
                )
                Toast.makeText(requireContext(), "Added to ${playlists[index].name}", Toast.LENGTH_SHORT).show()
            }
        }.show()
    }

    private fun createPlaylistWith(entry: Entry) {
        val track = entry.track ?: return
        val input = EditText(requireContext()).apply { hint = "Playlist name" }
        AlertDialog.Builder(requireContext()).setTitle("New playlist").setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val playlist = PlaylistStore.create(requireContext(), name)
                    PlaylistStore.addTrack(
                        requireContext(), playlist.id,
                        PlaylistTrack(track.uri, track.displayName, track.archiveEntry)
                    )
                }
            }.setNegativeButton("Cancel", null).show()
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
    private fun prefs() = requireContext().getSharedPreferences("vgmp_browser", 0)

    private class EntryAdapter(
        private val items: List<Entry>,
        private val click: (Entry) -> Unit,
        private val longClick: (Entry) -> Unit
    ) : RecyclerView.Adapter<EntryAdapter.Holder>() {
        class Holder(val text: TextView) : RecyclerView.ViewHolder(text)
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): Holder {
            val text = TextView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setPadding(20, 24, 20, 24)
                textSize = 16f
                setTextColor(context.getColor(org.vlessert.vgmp.R.color.vgmp_text_primary))
            }
            return Holder(text)
        }
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.text.text = when {
                item.directory -> "📁  ${item.name}"
                item.playable -> "♪  ${item.name}"
                else -> "·  ${item.name}"
            }
            holder.text.alpha = if (!item.directory && !item.playable) 0.55f else 1f
            holder.text.setOnClickListener { click(item) }
            holder.text.setOnLongClickListener { longClick(item); true }
        }
    }

    companion object {
        private const val KEY_ROOT = "root_uri"
        private const val KEY_CURRENT = "current_uri"
        fun newInstance() = BrowserFragment()
    }
}

internal fun parentDocumentId(rootId: String, currentId: String): String {
    if (currentId == rootId) return rootId
    return currentId.substringBeforeLast('/', rootId)
        .takeIf { it == rootId || it.startsWith("$rootId/") }
        ?: rootId
}
