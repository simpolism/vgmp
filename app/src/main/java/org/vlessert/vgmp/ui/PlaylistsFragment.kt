package org.vlessert.vgmp.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.vlessert.vgmp.MainActivity
import org.vlessert.vgmp.R
import org.vlessert.vgmp.databinding.FragmentPlaylistsBinding
import org.vlessert.vgmp.playback.TrackRef
import org.vlessert.vgmp.playlists.Playlist
import org.vlessert.vgmp.playlists.PlaylistStore
import org.vlessert.vgmp.playlists.PlaylistTrack
import org.vlessert.vgmp.service.VgmPlaybackService

class PlaylistsFragment : Fragment() {
    private sealed interface Selection {
        data object Queue : Selection
        data class Named(val id: String) : Selection
    }

    private var _binding: FragmentPlaylistsBinding? = null
    private val binding get() = _binding!!
    private var selection: Selection? = null
    private var serviceJob: Job? = null
    private var currentTrack: TrackRef? = null
    private var touchHelper: ItemTouchHelper? = null

    private val service get() = (activity as? MainActivity)?.getService()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentPlaylistsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        binding.recyclerPlaylists.layoutManager = LinearLayoutManager(requireContext())
        binding.btnNewPlaylist.setOnClickListener { showCreateDialog() }
        binding.btnSaveQueue.setOnClickListener { saveQueueAsPlaylist() }
        binding.btnPlaylistBack.setOnClickListener { selection = null; refresh() }
        observeService()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) { observeService(); refresh() }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) { observeService(); refresh() }
    }

    private fun observeService() {
        val svc = service ?: return
        if (serviceJob?.isActive == true) return
        serviceJob = viewLifecycleOwner.lifecycleScope.launch {
            launch { svc.queueTracks.collect { if (selection == Selection.Queue) refresh() else refreshRootCounts() } }
            launch { svc.playbackInfo.collect { currentTrack = it.track; refreshVisibleTrackMarkers() } }
        }
    }

    fun onServiceConnected(service: VgmPlaybackService) {
        serviceJob?.cancel()
        serviceJob = null
        observeService()
        refresh()
    }

    private fun refresh() {
        touchHelper?.attachToRecyclerView(null)
        touchHelper = null
        when (val selected = selection) {
            null -> showPlaylists()
            Selection.Queue -> showQueue()
            is Selection.Named -> PlaylistStore.getAll(requireContext()).firstOrNull { it.id == selected.id }
                ?.let(::showNamedPlaylist) ?: run { selection = null; showPlaylists() }
        }
    }

    private fun showPlaylists() {
        val playlists = PlaylistStore.getAll(requireContext())
        val queueCount = service?.queueTracks?.value?.size ?: 0
        binding.tvPlaylistTitle.text = "Playlists"
        binding.btnPlaylistBack.visibility = View.GONE
        binding.btnNewPlaylist.visibility = View.VISIBLE
        binding.btnSaveQueue.visibility = View.GONE
        binding.tvPlaylistsEmpty.visibility = View.GONE
        val rows = listOf(RootRow(null, "Queue", queueCount)) + playlists.map { RootRow(it, it.name, it.tracks.size) }
        binding.recyclerPlaylists.adapter = RootAdapter(
            onClick = { row -> selection = row.playlist?.let { Selection.Named(it.id) } ?: Selection.Queue; refresh() },
            onLongClick = { row -> row.playlist?.let(::confirmDelete) }
        ).also { it.submitList(rows) }
    }

    private fun refreshRootCounts() {
        if (selection == null) showPlaylists()
    }

    private fun showQueue() {
        val tracks = service?.queueTracks?.value.orEmpty()
        binding.tvPlaylistTitle.text = "Queue"
        binding.btnPlaylistBack.visibility = View.VISIBLE
        binding.btnNewPlaylist.visibility = View.GONE
        binding.btnSaveQueue.visibility = if (tracks.isEmpty()) View.GONE else View.VISIBLE
        showTrackList(tracks, queue = true, playlist = null)
    }

    private fun showNamedPlaylist(playlist: Playlist) {
        binding.tvPlaylistTitle.text = playlist.name
        binding.btnPlaylistBack.visibility = View.VISIBLE
        binding.btnNewPlaylist.visibility = View.GONE
        binding.btnSaveQueue.visibility = View.GONE
        showTrackList(playlist.tracks.map(PlaylistTrack::toTrackRef), queue = false, playlist = playlist)
    }

    private fun showTrackList(tracks: List<TrackRef>, queue: Boolean, playlist: Playlist?) {
        binding.tvPlaylistsEmpty.text = if (queue) "The queue is empty" else "This playlist is empty"
        binding.tvPlaylistsEmpty.visibility = if (tracks.isEmpty()) View.VISIBLE else View.GONE
        val adapter = TrackListAdapter(
            current = { currentTrack },
            onClick = { index ->
                if (queue) service?.playQueueIndex(index)
                else {
                    val replaced = service?.queueTracks?.value?.isNotEmpty() == true
                    service?.playQueue(tracks, index)
                    if (replaced) Snackbar.make(binding.root, "Queue replaced", Snackbar.LENGTH_LONG)
                        .setAction("Undo") { service?.undoQueueReplacement() }.show()
                }
            },
            onLongClick = { index ->
                TrackContextMenu.show(
                    requireContext(), tracks[index], service,
                    removeLabel = if (queue) "Remove from queue" else "Remove from playlist",
                    onRemove = {
                        if (queue) service?.removeQueueAt(index)
                        else playlist?.let { PlaylistStore.removeTrackAt(requireContext(), it.id, index); refresh() }
                    }
                )
            }
        )
        binding.recyclerPlaylists.adapter = adapter
        adapter.submitList(tracks)
        touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(rv: RecyclerView, source: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = source.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from < 0 || to < 0) return false
                if (queue) service?.moveQueueItem(from, to)
                val mutableTracks = adapter.currentList.toMutableList()
                mutableTracks.add(to, mutableTracks.removeAt(from))
                if (!queue && playlist != null) {
                    PlaylistStore.replaceTracks(
                        requireContext(), playlist.id, mutableTracks.map(PlaylistTrack::from)
                    )
                }
                adapter.submitList(mutableTracks)
                return true
            }
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
        }).also { it.attachToRecyclerView(binding.recyclerPlaylists) }
    }

    private fun refreshVisibleTrackMarkers() {
        (binding.recyclerPlaylists.adapter as? TrackListAdapter)?.notifyItemRangeChanged(
            0, binding.recyclerPlaylists.adapter?.itemCount ?: 0
        )
    }

    private fun saveQueueAsPlaylist() {
        val tracks = service?.queueTracks?.value.orEmpty()
        if (tracks.isEmpty()) return
        val input = EditText(requireContext()).apply { hint = "Playlist name" }
        AlertDialog.Builder(requireContext()).setTitle("Save queue as playlist").setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) PlaylistStore.create(requireContext(), name, tracks.map(PlaylistTrack::from))
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showCreateDialog() {
        val input = EditText(requireContext()).apply { hint = "Playlist name" }
        AlertDialog.Builder(requireContext()).setTitle("New playlist").setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) { PlaylistStore.create(requireContext(), name); refresh() }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun confirmDelete(playlist: Playlist) {
        AlertDialog.Builder(requireContext()).setTitle("Delete ${playlist.name}?")
            .setPositiveButton("Delete") { _, _ -> PlaylistStore.delete(requireContext(), playlist.id); refresh() }
            .setNegativeButton("Cancel", null).show()
    }

    override fun onDestroyView() {
        serviceJob?.cancel()
        touchHelper?.attachToRecyclerView(null)
        super.onDestroyView()
        _binding = null
    }

    private data class RootRow(val playlist: Playlist?, val name: String, val count: Int)

    private class RootAdapter(
        private val onClick: (RootRow) -> Unit,
        private val onLongClick: (RootRow) -> Unit
    ) : ListAdapter<RootRow, TextHolder>(diff()) {
        override fun onCreateViewHolder(parent: ViewGroup, type: Int) = TextHolder.create(parent)
        override fun onBindViewHolder(holder: TextHolder, position: Int) {
            val row = getItem(position)
            holder.text.text = "${if (row.playlist == null) "▶" else "♫"}  ${row.name}\n${row.count} tracks"
            holder.text.setOnClickListener { onClick(row) }
            holder.text.setOnLongClickListener { onLongClick(row); row.playlist != null }
        }
        companion object { private fun diff() = object : DiffUtil.ItemCallback<RootRow>() {
            override fun areItemsTheSame(a: RootRow, b: RootRow) = a.playlist?.id == b.playlist?.id
            override fun areContentsTheSame(a: RootRow, b: RootRow) = a == b
        } }
    }

    private class TrackListAdapter(
        private val current: () -> TrackRef?,
        private val onClick: (Int) -> Unit,
        private val onLongClick: (Int) -> Unit
    ) : ListAdapter<TrackRef, TextHolder>(object : DiffUtil.ItemCallback<TrackRef>() {
        override fun areItemsTheSame(a: TrackRef, b: TrackRef) = a == b
        override fun areContentsTheSame(a: TrackRef, b: TrackRef) = a == b
    }) {
        override fun onCreateViewHolder(parent: ViewGroup, type: Int) = TextHolder.create(parent)
        override fun onBindViewHolder(holder: TextHolder, position: Int) {
            val track = getItem(position)
            holder.text.text = "${if (track == current()) "▶" else "♪"}  ${track.title}"
            holder.text.setOnClickListener { onClick(holder.bindingAdapterPosition) }
            holder.text.setOnLongClickListener { onLongClick(holder.bindingAdapterPosition); true }
        }
    }

    private class TextHolder(val text: TextView) : RecyclerView.ViewHolder(text) {
        companion object {
            fun create(parent: ViewGroup): TextHolder {
                val text = TextView(parent.context).apply {
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    val d = resources.displayMetrics.density
                    setPadding((12 * d).toInt(), (14 * d).toInt(), (12 * d).toInt(), (14 * d).toInt())
                    textSize = 16f
                    setTextColor(context.getColor(R.color.vgmp_text_primary))
                    val selectable = android.util.TypedValue()
                    context.theme.resolveAttribute(android.R.attr.selectableItemBackground, selectable, true)
                    setBackgroundResource(selectable.resourceId)
                }
                return TextHolder(text)
            }
        }
    }

    companion object { fun newInstance() = PlaylistsFragment() }
}
