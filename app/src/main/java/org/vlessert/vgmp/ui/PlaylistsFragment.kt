package org.vlessert.vgmp.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.vlessert.vgmp.MainActivity
import org.vlessert.vgmp.R
import org.vlessert.vgmp.databinding.FragmentPlaylistsBinding
import org.vlessert.vgmp.playlists.Playlist
import org.vlessert.vgmp.playlists.PlaylistStore
import org.vlessert.vgmp.playlists.PlaylistTrack
import org.vlessert.vgmp.playback.TrackRef
import org.vlessert.vgmp.service.VgmPlaybackService

class PlaylistsFragment : Fragment() {
    private var _binding: FragmentPlaylistsBinding? = null
    private val binding get() = _binding!!
    private var selected: Playlist? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentPlaylistsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        binding.recyclerPlaylists.layoutManager = LinearLayoutManager(requireContext())
        binding.btnNewPlaylist.setOnClickListener { showCreateDialog() }
        binding.btnPlaylistBack.setOnClickListener { selected = null; refresh() }
        refresh()
    }

    override fun onResume() { super.onResume(); if (_binding != null) refresh() }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) refresh()
    }

    private fun refresh() {
        val selectedPlaylist = selected?.let { old -> PlaylistStore.getAll(requireContext()).find { it.id == old.id } }
        selected = selectedPlaylist
        if (selectedPlaylist == null) showPlaylists() else showTracks(selectedPlaylist)
    }

    private fun showPlaylists() {
        val playlists = PlaylistStore.getAll(requireContext())
        binding.tvPlaylistTitle.text = "Playlists"
        binding.btnPlaylistBack.visibility = View.GONE
        binding.btnNewPlaylist.visibility = View.VISIBLE
        binding.tvPlaylistsEmpty.visibility = if (playlists.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerPlaylists.adapter = TextAdapter(
            playlists.map { "${it.name}\n${it.tracks.size} tracks" },
            onClick = { selected = playlists[it]; refresh() },
            onLongClick = { confirmDelete(playlists[it]) }
        )
    }

    private fun showTracks(playlist: Playlist) {
        binding.tvPlaylistTitle.text = playlist.name
        binding.btnPlaylistBack.visibility = View.VISIBLE
        binding.btnNewPlaylist.visibility = View.GONE
        binding.tvPlaylistsEmpty.text = "This playlist is empty"
        binding.tvPlaylistsEmpty.visibility = if (playlist.tracks.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerPlaylists.adapter = TextAdapter(
            playlist.tracks.map { "♪  ${it.displayName}" },
            onClick = { playPlaylist(playlist, it) },
            onLongClick = { removeTrack(playlist, playlist.tracks[it]) }
        )
    }

    private fun playPlaylist(playlist: Playlist, index: Int) {
        val queue = playlist.tracks.map { TrackRef(it.uri, it.displayName) }
        (activity as? MainActivity)?.getService()?.playQueue(queue, index)
        (activity as? MainActivity)?.selectPlayerTab()
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

    private fun removeTrack(playlist: Playlist, track: PlaylistTrack) {
        AlertDialog.Builder(requireContext()).setTitle("Remove ${track.displayName}?")
            .setPositiveButton("Remove") { _, _ -> PlaylistStore.removeTrack(requireContext(), playlist.id, track.uri); refresh() }
            .setNegativeButton("Cancel", null).show()
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }

    private class TextAdapter(
        private val rows: List<String>,
        private val onClick: (Int) -> Unit,
        private val onLongClick: (Int) -> Unit
    ) : RecyclerView.Adapter<TextAdapter.Holder>() {
        class Holder(val text: TextView) : RecyclerView.ViewHolder(text)
        override fun onCreateViewHolder(parent: ViewGroup, type: Int) = Holder(TextView(parent.context).apply {
            setPadding(20, 24, 20, 24); textSize = 16f; setTextColor(context.getColor(R.color.vgmp_text_primary))
        })
        override fun getItemCount() = rows.size
        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.text.text = rows[position]
            holder.text.setOnClickListener { onClick(position) }
            holder.text.setOnLongClickListener { onLongClick(position); true }
        }
    }

    companion object { fun newInstance() = PlaylistsFragment() }
}
