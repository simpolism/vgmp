package org.vlessert.vgmp.ui

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.Editable
import android.text.TextWatcher
import android.graphics.drawable.ColorDrawable
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
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.vlessert.vgmp.MainActivity
import org.vlessert.vgmp.databinding.FragmentBrowserBinding
import org.vlessert.vgmp.playback.ArtworkRef
import org.vlessert.vgmp.playback.isArtwork
import org.vlessert.vgmp.playback.selectArtwork
import org.vlessert.vgmp.playlists.PlaylistStore
import org.vlessert.vgmp.playlists.PlaylistTrack
import org.vlessert.vgmp.playback.TrackRef
import org.vlessert.vgmp.playback.SupportedFormats
import org.vlessert.vgmp.playback.ZipArchiveStore
import org.vlessert.vgmp.playback.NaturalSort
import org.vlessert.vgmp.engine.VgmEngine
import java.io.File
import java.security.MessageDigest
import org.vlessert.vgmp.service.VgmPlaybackService
import org.vlessert.vgmp.settings.SettingsManager
import com.google.android.material.snackbar.Snackbar

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
        data class Subtracks(
            val track: TrackRef,
            val localPath: String,
            val firstIndex: Int,
            val count: Int,
            val titles: List<String>
        ) : Location
    }

    private var _binding: FragmentBrowserBinding? = null
    private val binding get() = _binding!!
    private var entries: List<Entry> = emptyList()
    private val history = ArrayDeque<Location>()
    private lateinit var adapter: EntryAdapter
    private var rootUri: Uri? = null
    private var currentLocation: Location? = null
    private var loadGeneration = 0
    private var filterJob: Job? = null
    private var currentTrack: TrackRef? = null
    private var serviceJob: Job? = null

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
        prefs().edit().putString(KEY_ROOT, documentUri.toString())
            .putString(KEY_CURRENT, documentUri.toString())
            .putString(KEY_LOCATION_TYPE, "document").apply()
        loadDirectory()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentBrowserBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        adapter = EntryAdapter(::openEntry, ::showContextMenu)
        binding.recyclerEntries.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerEntries.adapter = adapter
        binding.btnChooseFolder.setOnClickListener { chooseFolder.launch(null) }
        binding.btnUp.setOnClickListener { navigateUp() }
        binding.editFilter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterJob?.cancel()
                filterJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(150)
                    applyFilter()
                }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        rootUri = prefs().getString(KEY_ROOT, null)?.let(Uri::parse)
        currentLocation = restoredLocation() ?: rootUri?.let(Location::Document)
        loadDirectory()
        (activity as? MainActivity)?.getService()?.let(::onServiceConnected)
    }

    fun onServiceConnected(service: VgmPlaybackService) {
        serviceJob?.cancel()
        serviceJob = viewLifecycleOwner.lifecycleScope.launch {
            service.playbackInfo.collect { info ->
                currentTrack = info.track
                adapter.currentTrack = info.track
                adapter.notifyItemRangeChanged(0, adapter.itemCount)
            }
        }
    }

    fun navigateUp(): Boolean {
        val parent = history.removeLastOrNull() ?: run {
            when (val location = currentLocation) {
                is Location.Document -> persistedParent(location.uri)?.let(Location::Document)
                is Location.Zip -> if (location.path.isNotEmpty()) {
                    location.copy(path = location.path.substringBeforeLast('/', ""))
                } else {
                    persistedParent(location.archiveUri)?.let(Location::Document)
                }
                is Location.Subtracks -> null
                null -> null
            } ?: return false
        }
        currentLocation = parent
        persistLocation(parent)
        binding.editFilter.setText("")
        loadDirectory()
        return true
    }

    private fun loadDirectory() {
        val location = currentLocation
        val generation = ++loadGeneration
        binding.btnUp.isEnabled = when (location) {
            is Location.Zip -> true
            is Location.Subtracks -> true
            is Location.Document -> location.uri != rootUri
            null -> false
        }
        if (location == null) {
            binding.tvPath.text = "Browse"
            binding.tvEmpty.visibility = View.VISIBLE
            entries = emptyList()
            adapter.submitList(emptyList())
            return
        }
        entries = emptyList()
        adapter.submitList(emptyList())
        binding.recyclerEntries.visibility = View.GONE
        binding.progress.visibility = View.VISIBLE
        binding.tvEmpty.visibility = View.GONE
        viewLifecycleOwner.lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    when (location) {
                        is Location.Document -> queryChildren(location.uri)
                        is Location.Zip -> queryZip(location)
                        is Location.Subtracks -> querySubtracks(location)
                    }
                }
            }
            if (generation != loadGeneration) return@launch
            if (loaded.isFailure) {
                binding.progress.visibility = View.GONE
                binding.recyclerEntries.visibility = View.VISIBLE
                Toast.makeText(requireContext(), "Could not open this folder or archive", Toast.LENGTH_LONG).show()
                return@launch
            }
            entries = loaded.getOrThrow()
            applyFilter()
            binding.progress.visibility = View.GONE
            binding.recyclerEntries.visibility = View.VISIBLE
            binding.tvPath.text = when (location) {
                is Location.Document -> location.uri.lastPathSegment
                    ?.substringAfterLast(':')?.ifEmpty { "Music" } ?: "Music"
                is Location.Zip -> if (location.path.isEmpty()) location.archiveName
                    else "${location.archiveName}/${location.path}"
                is Location.Subtracks -> location.track.displayName
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
        val artwork = mutableListOf<Pair<String, Uri>>()
        requireContext().contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val name = cursor.getString(1) ?: continue
                val mime = cursor.getString(2)
                val isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR
                val uri = DocumentsContract.buildDocumentUriUsingTree(directory, id)
                if (!isDirectory && isArtwork(name)) artwork += name to uri
                val browseZip = !isDirectory && (
                    name.endsWith(".zip", ignoreCase = true) || name.endsWith(".7z", ignoreCase = true)
                ) &&
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
        val artworkNames = artwork.map { it.first }
        return result.map { entry ->
            val track = entry.track ?: return@map entry
            val selected = selectArtwork(track.displayName, artworkNames)
                ?.let { name -> artwork.first { it.first == name }.second }
            entry.copy(track = track.copy(artwork = selected?.let { ArtworkRef(it) }))
        }.sortedWith(compareBy<Entry> { !it.directory }.thenComparator { a, b ->
            NaturalSort.compare(a.name, b.name)
        })
    }

    private fun queryZip(location: Location.Zip): List<Entry> {
        val items = ZipArchiveStore(requireContext()).list(location.archiveUri, location.path)
        val artwork = items.filter { !it.directory && isArtwork(it.displayName) }
        val artworkNames = artwork.map { it.displayName }
        return items.map { item ->
            val artworkEntry = selectArtwork(item.displayName, artworkNames)
                ?.let { name -> artwork.first { it.displayName == name }.path }
            val track = if (!item.directory && SupportedFormats.supports(item.displayName)) {
                TrackRef(
                    location.archiveUri,
                    item.displayName,
                    archiveEntry = item.path,
                    artwork = artworkEntry?.let { ArtworkRef(location.archiveUri, it) }
                )
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
    }

    private fun querySubtracks(location: Location.Subtracks): List<Entry> =
        List(location.count) { offset ->
            val index = location.firstIndex + offset
            val title = location.titles.getOrNull(offset).orEmpty()
            val label = title.ifEmpty { "Track ${offset + 1}" }
            val extension = location.track.displayName.substringAfterLast('.', "")
            Entry(
                uri = location.track.uri,
                name = label,
                directory = false,
                playable = true,
                track = location.track.copy(
                    displayName = if (extension.isEmpty()) label else "$label.$extension",
                    subtrackIndex = index
                )
            )
        }

    private fun persistedParent(current: Uri): Uri? {
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
            persistLocation(target)
            binding.editFilter.setText("")
            loadDirectory()
            return
        }
        val selectedTrack = entry.track
        if (selectedTrack == null) {
            Toast.makeText(requireContext(), "${entry.name} is not supported yet", Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedTrack.subtrackIndex < 0 && isPotentialMultiTrack(selectedTrack.displayName)) {
            inspectSubtracks(selectedTrack)
            return
        }
        playTrack(selectedTrack)
    }

    private fun playTrack(selectedTrack: TrackRef) {
        val tracks = adapter.currentList.mapNotNull { it.track }
        val start = tracks.indexOf(selectedTrack)
        val service = (activity as? MainActivity)?.getService() ?: return
        val replacedExisting = service.queueTracks.value.isNotEmpty()
        service.playQueue(tracks, start)
        if (replacedExisting) {
            Snackbar.make(binding.root, "Queue replaced", Snackbar.LENGTH_LONG)
                .setAction("Undo") { service.undoQueueReplacement() }
                .show()
        }
        if (SettingsManager.openPlayerOnSelection(requireContext())) {
            (activity as? MainActivity)?.selectPlayerTab()
        }
    }

    private fun inspectSubtracks(track: TrackRef) {
        val parent = currentLocation ?: return
        binding.progress.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            val inspected = withContext(Dispatchers.IO) {
                runCatching {
                    val path = materializeForInspection(track)
                    val count = VgmEngine.getTrackCountDirect(path)
                    val first = if (track.displayName.endsWith(".kss", true) ||
                        track.displayName.endsWith(".mgs", true)) {
                        VgmEngine.getKssTrackRange(path).firstOrNull() ?: 0
                    } else 0
                    val titles = if (count > 1) List(count) { offset ->
                        VgmEngine.getTrackTitleDirect(path, first + offset)
                    } else emptyList()
                    Location.Subtracks(track, path, first, count, titles)
                }
            }
            binding.progress.visibility = View.GONE
            val location = inspected.getOrElse {
                Toast.makeText(requireContext(), "Could not inspect ${track.displayName}", Toast.LENGTH_LONG).show()
                return@launch
            }
            if (location.count <= 1) {
                playTrack(track)
            } else {
                history.addLast(parent)
                currentLocation = location
                binding.editFilter.setText("")
                loadDirectory()
            }
        }
    }

    private fun materializeForInspection(track: TrackRef): String {
        val dir = File(requireContext().cacheDir, "subtrack-inspection").also { it.mkdirs() }
        val hash = MessageDigest.getInstance("SHA-256")
            .digest("${track.uri}|${track.archiveEntry}".toByteArray())
            .take(12).joinToString("") { "%02x".format(it) }
        val safeName = track.displayName.replace(Regex("[^A-Za-z0-9._ -]"), "_")
        val destination = File(dir, "$hash-$safeName")
        if (!destination.exists()) {
            destination.outputStream().use { output ->
                if (track.archiveEntry != null) {
                    ZipArchiveStore(requireContext()).copyEntry(track.uri, track.archiveEntry, output)
                } else {
                    requireContext().contentResolver.openInputStream(track.uri)?.use { it.copyTo(output) }
                        ?: error("Could not open track")
                }
            }
        }
        return destination.absolutePath
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
                    PlaylistTrack.from(track)
                )
                Toast.makeText(requireContext(), "Added to ${playlists[index].name}", Toast.LENGTH_SHORT).show()
            }
        }.show()
    }

    private fun showContextMenu(entry: Entry) {
        val track = entry.track ?: return
        TrackContextMenu.show(
            requireContext(),
            track,
            (activity as? MainActivity)?.getService()
        )
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
                        PlaylistTrack.from(track)
                    )
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun applyFilter() {
        if (_binding == null) return
        val query = binding.editFilter.text?.toString()?.trim().orEmpty()
        val filtered = if (query.isEmpty()) entries else entries.filter {
            it.name.contains(query, ignoreCase = true)
        }
        adapter.submitList(filtered)
        binding.tvEmpty.text = if (query.isEmpty()) "This folder is empty" else "No matching tracks"
        binding.tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun persistLocation(location: Location) {
        val edit = prefs().edit()
        when (location) {
            is Location.Document -> edit.putString(KEY_LOCATION_TYPE, "document")
                .putString(KEY_CURRENT, location.uri.toString())
                .remove(KEY_ARCHIVE_URI).remove(KEY_ARCHIVE_NAME).remove(KEY_ARCHIVE_PATH)
            is Location.Zip -> edit.putString(KEY_LOCATION_TYPE, "zip")
                .putString(KEY_ARCHIVE_URI, location.archiveUri.toString())
                .putString(KEY_ARCHIVE_NAME, location.archiveName)
                .putString(KEY_ARCHIVE_PATH, location.path)
            is Location.Subtracks -> return
        }
        edit.apply()
    }

    private fun restoredLocation(): Location? {
        val prefs = prefs()
        return if (prefs.getString(KEY_LOCATION_TYPE, "document") == "zip") {
            val uri = prefs.getString(KEY_ARCHIVE_URI, null)?.let(Uri::parse) ?: return null
            Location.Zip(
                uri,
                prefs.getString(KEY_ARCHIVE_NAME, null) ?: "Archive",
                prefs.getString(KEY_ARCHIVE_PATH, "").orEmpty()
            )
        } else {
            prefs.getString(KEY_CURRENT, null)?.let(Uri::parse)?.let(Location::Document)
        }
    }

    private fun isPotentialMultiTrack(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in setOf(
            "nsf", "nsfe", "gbs", "hes", "kss", "mgs", "sap", "ay"
        )

    override fun onDestroyView() {
        filterJob?.cancel()
        serviceJob?.cancel()
        super.onDestroyView()
        _binding = null
    }
    private fun prefs() = requireContext().getSharedPreferences("vgmp_browser", 0)

    private class EntryAdapter(
        private val click: (Entry) -> Unit,
        private val longClick: (Entry) -> Unit
    ) : ListAdapter<Entry, EntryAdapter.Holder>(object : DiffUtil.ItemCallback<Entry>() {
        override fun areItemsTheSame(old: Entry, new: Entry) =
            old.uri == new.uri && old.name == new.name && old.track?.subtrackIndex == new.track?.subtrackIndex
        override fun areContentsTheSame(old: Entry, new: Entry) = old == new
    }) {
        var currentTrack: TrackRef? = null
        class Holder(val text: TextView) : RecyclerView.ViewHolder(text)
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): Holder {
            val text = TextView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                val density = resources.displayMetrics.density
                setPadding((12 * density).toInt(), (14 * density).toInt(),
                    (12 * density).toInt(), (14 * density).toInt())
                textSize = 16f
                setTextColor(context.getColor(org.vlessert.vgmp.R.color.vgmp_text_primary))
                val selectable = android.util.TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, selectable, true)
                setBackgroundResource(selectable.resourceId)
            }
            return Holder(text)
        }
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = getItem(position)
            val playing = item.track != null && item.track == currentTrack
            holder.text.text = when {
                item.directory -> "📁  ${item.name}"
                playing -> "▶  ${item.name}"
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
        private const val KEY_LOCATION_TYPE = "location_type"
        private const val KEY_ARCHIVE_URI = "archive_uri"
        private const val KEY_ARCHIVE_NAME = "archive_name"
        private const val KEY_ARCHIVE_PATH = "archive_path"
        fun newInstance() = BrowserFragment()
    }
}

internal fun parentDocumentId(rootId: String, currentId: String): String {
    if (currentId == rootId) return rootId
    return currentId.substringBeforeLast('/', rootId)
        .takeIf { it == rootId || it.startsWith("$rootId/") }
        ?: rootId
}
