package com.example.subtitleplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * 搜索结果列表：上半部分为匹配的歌单（item_playlist），
 * 下半部分为匹配的单曲（item_song）。
 */
class SearchAdapter(
    private val hasLyric: (Song) -> Boolean,
    private val onPlaylistClick: (Int) -> Unit,
    private val onSongClick: (Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_PLAYLIST = 0
        private const val TYPE_SONG = 1
    }

    private var playlists: List<Playlist> = emptyList()
    private var songs: List<Song> = emptyList()
    private var uiSizeSp = 15f

    fun submit(pl: List<Playlist>, sg: List<Song>) {
        playlists = pl
        songs = sg
        notifyDataSetChanged()
    }

    fun applyUiSize(sizeSp: Int) {
        uiSizeSp = sizeSp.toFloat()
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = playlists.size + songs.size

    override fun getItemViewType(position: Int): Int =
        if (position < playlists.size) TYPE_PLAYLIST else TYPE_SONG

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_PLAYLIST) {
            PlaylistHolder(inflater.inflate(R.layout.item_playlist, parent, false))
        } else {
            SongHolder(inflater.inflate(R.layout.item_song, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is PlaylistHolder) {
            val pl = playlists[position]
            holder.name.text = pl.name
            holder.name.setTextSize(uiSizeSp)
            holder.count.text = holder.itemView.context
                .getString(R.string.songs_count, pl.songs.size)
            holder.itemView.setOnClickListener { onPlaylistClick(position) }
        } else {
            val songIndex = position - playlists.size
            val song = songs[songIndex]
            val h = holder as SongHolder
            h.index.text = (songIndex + 1).toString()
            h.title.text = song.title
            h.title.setTextSize(uiSizeSp)
            h.lyricMark.visibility =
                if (hasLyric(song)) View.VISIBLE else View.GONE
            h.cover.setImageResource(R.drawable.ic_music_tinted)
            CoverLoader.load(h.itemView.context, song.uri, 64) { bmp ->
                if (bmp != null && h.bindingAdapterPosition == position) {
                    h.cover.setImageBitmap(bmp)
                }
            }
            h.itemView.setOnClickListener { onSongClick(songIndex) }
        }
    }

    class PlaylistHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val name: TextView = itemView.findViewById(R.id.txtPlaylistName)
        val count: TextView = itemView.findViewById(R.id.txtPlaylistCount)
    }

    class SongHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val index: TextView = itemView.findViewById(R.id.txtSongIndex)
        val title: TextView = itemView.findViewById(R.id.txtSongTitle)
        val lyricMark: TextView = itemView.findViewById(R.id.txtHasLyric)
        val cover: ImageView = itemView.findViewById(R.id.imgCover)
    }
}
