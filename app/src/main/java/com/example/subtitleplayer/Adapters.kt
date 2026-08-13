package com.example.subtitleplayer

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

/** 歌单列表适配器。 */
class PlaylistAdapter(
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<PlaylistAdapter.Holder>() {

    private var items: List<Playlist> = emptyList()
    private var uiSizeSp = 15f

    fun submit(list: List<Playlist>) {
        items = list
        notifyDataSetChanged()
    }

    fun applyUiSize(sizeSp: Int) {
        uiSizeSp = sizeSp.toFloat()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_playlist, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val playlist = items[position]
        holder.name.text = playlist.name
        holder.name.setTextSize(uiSizeSp)
        holder.count.text = holder.itemView.context
            .getString(R.string.songs_count, playlist.songs.size)
        holder.cover.setImageResource(R.drawable.ic_folder_tinted)
        val song = playlist.songs.firstOrNull()
        if (song != null) {
            CoverLoader.load(holder.itemView.context, song.uri, 96) { bmp ->
                if (bmp != null && holder.bindingAdapterPosition == position) {
                    holder.cover.setImageBitmap(bmp)
                }
            }
        }
        holder.itemView.setOnClickListener { onClick(position) }
    }

    override fun getItemCount(): Int = items.size

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val name: TextView = itemView.findViewById(R.id.txtPlaylistName)
        val count: TextView = itemView.findViewById(R.id.txtPlaylistCount)
        val cover: ImageView = itemView.findViewById(R.id.imgCover)
    }
}

/** 歌曲列表适配器。 */
class SongAdapter(
    private val hasLyric: (Song) -> Boolean,
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<SongAdapter.Holder>() {

    private var items: List<Song> = emptyList()
    private var uiSizeSp = 15f
    private var currentIndex = -1

    fun submit(list: List<Song>) {
        items = list
        notifyDataSetChanged()
    }

    /** 歌单拖拽排序：移动指定项并刷新。 */
    fun move(from: Int, to: Int) {
        if (from !in items.indices || to !in items.indices) return
        val list = items.toMutableList()
        val item = list.removeAt(from)
        list.add(to, item)
        items = list
        notifyItemMoved(from, to)
    }

    fun setCurrentIndex(index: Int) {
        currentIndex = index
        notifyDataSetChanged()
    }

    fun applyUiSize(sizeSp: Int) {
        uiSizeSp = sizeSp.toFloat()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_song, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val song = items[position]
        holder.index.text = (position + 1).toString()
        holder.title.text = song.title
        holder.title.setTextSize(uiSizeSp)
        if (position == currentIndex) {
            holder.title.setTextColor(ThemeManager.accent(holder.itemView.context))
            holder.title.typeface = Typeface.DEFAULT_BOLD
        } else {
            holder.title.setTextColor(
                ContextCompat.getColor(holder.itemView.context, R.color.text_primary)
            )
            holder.title.typeface = Typeface.DEFAULT
        }
        holder.lyricMark.visibility =
            if (hasLyric(song)) View.VISIBLE else View.GONE
        holder.lyricMark.setTextColor(ThemeManager.accent(holder.itemView.context))
        holder.cover.setImageResource(R.drawable.ic_music_tinted)
        CoverLoader.load(holder.itemView.context, song.uri, 64) { bmp ->
            if (bmp != null && holder.bindingAdapterPosition == position) {
                holder.cover.setImageBitmap(bmp)
            }
        }
        holder.itemView.setOnClickListener { onClick(position) }
    }

    override fun getItemCount(): Int = items.size

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val index: TextView = itemView.findViewById(R.id.txtSongIndex)
        val title: TextView = itemView.findViewById(R.id.txtSongTitle)
        val lyricMark: TextView = itemView.findViewById(R.id.txtHasLyric)
        val cover: ImageView = itemView.findViewById(R.id.imgCover)
    }
}

/** 播放页歌词适配器，支持当前行高亮。 */
class LyricAdapter(
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<LyricAdapter.Holder>() {

    private var items: List<SubtitleLine> = emptyList()
    var current: Int = -1
        private set
    private var lyricSizeSp = 18f
    private var fontMode = 0
    private var translations: Map<Int, String> = emptyMap()

    fun submit(list: List<SubtitleLine>) {
        items = list
        current = -1
        notifyDataSetChanged()
    }

    fun setTranslations(map: Map<Int, String>) {
        translations = map
        notifyDataSetChanged()
    }

    fun applyStyle(sizeSp: Int, font: Int) {
        lyricSizeSp = sizeSp.toFloat()
        fontMode = font
        notifyDataSetChanged()
    }

    fun setCurrent(index: Int) {
        if (index == current) return
        current = index
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_lyric, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val line = items[position]
        holder.text.text = line.text
        val isCurrent = position == current
        holder.text.setTextSize(if (isCurrent) lyricSizeSp + 4f else lyricSizeSp)
        holder.text.typeface = if (isCurrent) {
            Typeface.DEFAULT_BOLD
        } else {
            when (fontMode) {
                1 -> Typeface.SERIF
                2 -> Typeface.MONOSPACE
                else -> Typeface.DEFAULT
            }
        }
        val trans = translations[position]
        if (trans != null) {
            holder.trans.text = trans
            holder.trans.visibility = View.VISIBLE
        } else {
            holder.trans.visibility = View.GONE
        }
        if (isCurrent) {
            holder.itemView.setBackgroundResource(R.drawable.bg_current_line)
            val accent = ThemeManager.accent(holder.text.context)
            holder.text.setTextColor(accent)
            holder.trans.setTextColor(ThemeManager.accentDark(accent))
        } else {
            holder.itemView.setBackgroundResource(0)
            holder.text.setTextColor(holder.text.context.getColor(R.color.text_normal))
            holder.trans.setTextColor(holder.text.context.getColor(R.color.text_hint))
        }
        // 当前行淡入，更沉浸
        if (isCurrent) {
            holder.itemView.alpha = 0.4f
            holder.itemView.animate().alpha(1f).setDuration(220).start()
        }
        holder.itemView.setOnClickListener { onClick(position) }
    }

    override fun getItemCount(): Int = items.size

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val text: TextView = itemView.findViewById(R.id.tvLine)
        val trans: TextView = itemView.findViewById(R.id.tvLineTrans)
    }
}
