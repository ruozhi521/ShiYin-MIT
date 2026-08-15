package com.example.subtitleplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * 搜索结果列表：上半部分为匹配的歌单（item_playlist），
 * 下半部分为匹配的歌曲——按文件夹分组（分组头 item_search_group + 歌曲 item_song），
 * 点击分组头可折叠/展开该文件夹的歌曲。
 */
class SearchAdapter(
    private val hasLyric: (Song) -> Boolean,
    private val onPlaylistClick: (Int) -> Unit,
    private val onSongClick: (Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private class SongGroup(val folder: String, val songs: List<Song>)

    private sealed class Item {
        class PlaylistItem(val index: Int) : Item()
        class GroupHeader(val groupIndex: Int) : Item()
        class SongItem(val globalIndex: Int) : Item()
    }

    companion object {
        private const val TYPE_PLAYLIST = 0
        private const val TYPE_GROUP_HEADER = 1
        private const val TYPE_SONG = 2
    }

    private var playlists: List<Playlist> = emptyList()
    private var allSongs: List<Song> = emptyList()
    private var groups: List<SongGroup> = emptyList()
    private val collapsed = HashSet<String>()
    private var items: List<Item> = emptyList()
    private var uiSizeSp = 15f

    fun submit(pl: List<Playlist>, sg: List<Song>) {
        playlists = pl
        allSongs = sg
        groups = sg.groupBy { it.folder }.map { (folder, songs) -> SongGroup(folder, songs) }
        collapsed.clear()
        rebuildItems()
    }

    fun applyUiSize(sizeSp: Int) {
        uiSizeSp = sizeSp.toFloat()
        notifyDataSetChanged()
    }

    private fun rebuildItems() {
        val list = ArrayList<Item>()
        playlists.indices.forEach { list.add(Item.PlaylistItem(it)) }
        groups.forEachIndexed { gi, g ->
            list.add(Item.GroupHeader(gi))
            if (!collapsed.contains(g.folder)) {
                g.songs.forEach { song ->
                    val idx = allSongs.indexOf(song)
                    if (idx >= 0) list.add(Item.SongItem(idx))
                }
            }
        }
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is Item.PlaylistItem -> TYPE_PLAYLIST
        is Item.GroupHeader -> TYPE_GROUP_HEADER
        is Item.SongItem -> TYPE_SONG
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_PLAYLIST -> PlaylistHolder(inflater.inflate(R.layout.item_playlist, parent, false))
            TYPE_GROUP_HEADER -> GroupHeaderHolder(
                inflater.inflate(R.layout.item_search_group, parent, false)
            )
            else -> SongHolder(inflater.inflate(R.layout.item_song, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is Item.PlaylistItem -> {
                val pl = playlists[item.index]
                val h = holder as PlaylistHolder
                h.name.text = pl.name
                h.name.setTextSize(uiSizeSp)
                h.count.text = holder.itemView.context.getString(R.string.songs_count, pl.songs.size)
                h.itemView.setOnClickListener { onPlaylistClick(item.index) }
            }
            is Item.GroupHeader -> {
                val g = groups[item.groupIndex]
                val h = holder as GroupHeaderHolder
                h.name.text = g.folder
                h.count.text = holder.itemView.context.getString(R.string.songs_count, g.songs.size)
                h.arrow.text = if (collapsed.contains(g.folder)) "\u25B8" else "\u25BE"
                h.itemView.setOnClickListener {
                    if (collapsed.contains(g.folder)) collapsed.remove(g.folder)
                    else collapsed.add(g.folder)
                    rebuildItems()
                }
            }
            is Item.SongItem -> {
                val song = allSongs[item.globalIndex]
                val h = holder as SongHolder
                h.index.text = (item.globalIndex + 1).toString()
                h.title.text = song.title
                h.title.setTextSize(uiSizeSp)
                h.lyricMark.visibility = if (hasLyric(song)) View.VISIBLE else View.GONE
                h.cover.setImageResource(R.drawable.ic_music_tinted)
                CoverLoader.load(h.itemView.context, song.uri, 64) { bmp ->
                    if (bmp != null && h.bindingAdapterPosition == position) {
                        h.cover.setImageBitmap(bmp)
                    }
                }
                h.itemView.setOnClickListener { onSongClick(item.globalIndex) }
            }
        }
    }

    class PlaylistHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val name: TextView = itemView.findViewById(R.id.txtPlaylistName)
        val count: TextView = itemView.findViewById(R.id.txtPlaylistCount)
    }

    class GroupHeaderHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val name: TextView = itemView.findViewById(R.id.txtGroupName)
        val count: TextView = itemView.findViewById(R.id.txtGroupCount)
        val arrow: TextView = itemView.findViewById(R.id.txtGroupArrow)
    }

    class SongHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val index: TextView = itemView.findViewById(R.id.txtSongIndex)
        val title: TextView = itemView.findViewById(R.id.txtSongTitle)
        val lyricMark: TextView = itemView.findViewById(R.id.txtHasLyric)
        val cover: ImageView = itemView.findViewById(R.id.imgCover)
    }
}
