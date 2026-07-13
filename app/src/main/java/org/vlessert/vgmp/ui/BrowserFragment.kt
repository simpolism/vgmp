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
import org.vlessert.vgmp.service.VgmPlaybackService

class BrowserFragment : Fragment() {
    data class Entry(val uri: Uri, val name: String, val directory: Boolean)

    private var _binding: FragmentBrowserBinding? = null
    private val binding get() = _binding!!
    private val entries = mutableListOf<Entry>()
    private val history = ArrayDeque<Uri>()
    private lateinit var adapter: EntryAdapter
    private var rootUri: Uri? = null
    private var currentUri: Uri? = null

    private val chooseFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@registerForActivityResult
        requireContext().contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(
            uri, DocumentsContract.getTreeDocumentId(uri)
        )
        rootUri = documentUri
        currentUri = documentUri
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
        currentUri = prefs().getString(KEY_CURRENT, null)?.let(Uri::parse) ?: rootUri
        loadDirectory()
    }

    fun navigateUp(): Boolean {
        val parent = history.removeLastOrNull() ?: return false
        currentUri = parent
        prefs().edit().putString(KEY_CURRENT, parent.toString()).apply()
        loadDirectory()
        return true
    }

    private fun loadDirectory() {
        val directory = currentUri
        binding.btnUp.isEnabled = history.isNotEmpty()
        if (directory == null) {
            binding.tvPath.text = "Browse"
            binding.tvEmpty.visibility = View.VISIBLE
            entries.clear()
            adapter.notifyDataSetChanged()
            return
        }
        binding.progress.visibility = View.VISIBLE
        binding.tvEmpty.visibility = View.GONE
        viewLifecycleOwner.lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { runCatching { queryChildren(directory) } }
            if (loaded.isFailure) {
                binding.progress.visibility = View.GONE
                Toast.makeText(requireContext(), "This folder is no longer available", Toast.LENGTH_LONG).show()
                return@launch
            }
            entries.clear()
            entries.addAll(loaded.getOrThrow())
            adapter.notifyDataSetChanged()
            binding.progress.visibility = View.GONE
            binding.tvPath.text = directory.lastPathSegment?.substringAfterLast(':')?.ifEmpty { "Music" } ?: "Music"
            binding.tvEmpty.text = "No supported tracks or folders here"
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
                if (isDirectory || isSupported(name)) {
                    result += Entry(DocumentsContract.buildDocumentUriUsingTree(directory, id), name, isDirectory)
                }
            }
        }
        return result.sortedWith(compareBy<Entry> { !it.directory }.thenBy { it.name.lowercase() })
    }

    private fun isSupported(name: String): Boolean =
        (activity as? MainActivity)?.getService()?.isSupportedDocument(name)
            ?: name.substringAfterLast('.', "").lowercase() in FALLBACK_EXTENSIONS

    private fun openEntry(entry: Entry) {
        if (entry.directory) {
            currentUri?.let(history::addLast)
            currentUri = entry.uri
            prefs().edit().putString(KEY_CURRENT, entry.uri.toString()).apply()
            loadDirectory()
            return
        }
        val tracks = entries.filterNot { it.directory }
        val start = tracks.indexOf(entry)
        val queue = tracks.map { VgmPlaybackService.DocumentTrack(it.uri, it.name) }
        (activity as? MainActivity)?.getService()?.playDocumentQueue(queue, start)
    }

    private fun addToPlaylist(entry: Entry) {
        if (entry.directory) return
        val playlists = PlaylistStore.getAll(requireContext())
        val labels = (playlists.map { it.name } + "＋ New playlist").toTypedArray()
        AlertDialog.Builder(requireContext()).setTitle("Add to playlist").setItems(labels) { _, index ->
            if (index == playlists.size) createPlaylistWith(entry)
            else {
                PlaylistStore.addTrack(requireContext(), playlists[index].id, PlaylistTrack(entry.uri, entry.name))
                Toast.makeText(requireContext(), "Added to ${playlists[index].name}", Toast.LENGTH_SHORT).show()
            }
        }.show()
    }

    private fun createPlaylistWith(entry: Entry) {
        val input = EditText(requireContext()).apply { hint = "Playlist name" }
        AlertDialog.Builder(requireContext()).setTitle("New playlist").setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val playlist = PlaylistStore.create(requireContext(), name)
                    PlaylistStore.addTrack(requireContext(), playlist.id, PlaylistTrack(entry.uri, entry.name))
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
                setPadding(20, 24, 20, 24)
                textSize = 16f
                setTextColor(context.getColor(org.vlessert.vgmp.R.color.vgmp_text_primary))
            }
            return Holder(text)
        }
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.text.text = if (item.directory) "📁  ${item.name}" else "♪  ${item.name}"
            holder.text.setOnClickListener { click(item) }
            holder.text.setOnLongClickListener { longClick(item); true }
        }
    }

    companion object {
        private const val KEY_ROOT = "root_uri"
        private const val KEY_CURRENT = "current_uri"
        private val FALLBACK_EXTENSIONS = setOf("vgm", "vgz", "nsf", "nsfe", "gbs", "gym", "hes", "ay", "sap", "spc", "kss", "mgs", "bgm", "opx", "mpk", "mbm", "mod", "xm", "s3m", "it", "mptm", "mid", "midi", "mus", "lmp", "psf", "psf1", "psf2")
        fun newInstance() = BrowserFragment()
    }
}
