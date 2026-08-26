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
/** 歌曲列表适配器。 */
class SongAdapter(
    private val hasLyric: (Song) -> Boolean,
    private val onClick: (Int) -> Unit,
    private val onLongClick: ((Song) -> Unit)? = null
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
        CoverLoader.load(holder.itemView.context, song.uri, 64, folder = song.folder) { bmp ->
            if (bmp != null && holder.bindingAdapterPosition == position) {
                holder.cover.setImageBitmap(bmp)
            }
        }
        holder.itemView.setOnClickListener { onClick(position) }
        holder.itemView.setOnLongClickListener {
            onLongClick?.invoke(song)
            true
        }
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
    private var idleColor = -1 // -1 = 默认 text_normal
    private var curColor = -1 // -1 = 跟随主题色（1.30）
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

    fun applyStyle(sizeSp: Int, font: Int, idleColorArgb: Int = -1, curColorArgb: Int = -1) {
        lyricSizeSp = sizeSp.toFloat()
        fontMode = font
        idleColor = idleColorArgb
        curColor = curColorArgb
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
            // 1.30：自定义播放中歌词色优先，未设置时跟随主题色
            val accent = if (curColor != -1) curColor else ThemeManager.accent(holder.text.context)
            holder.text.setTextColor(accent)
            holder.trans.setTextColor(ThemeManager.accentDark(accent))
        } else {
            holder.itemView.setBackgroundResource(0)
            val ctx = holder.text.context
            holder.text.setTextColor(
                if (idleColor != -1) idleColor
                else ctx.getColor(R.color.text_normal)
            )
            holder.trans.setTextColor(ctx.getColor(R.color.text_hint))
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
