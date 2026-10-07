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
    /** 是否显示拖拽把手（2.14）。只有「可排序的歌单页」才开。 */
    private var dragHandleVisible = false
    /**
     * 把手按下 → 请求外部发起拖拽（2.14）。
     * 由 MainActivity 接到后调 `ItemTouchHelper.startDrag(holder)`。
     * 这样「拖拽」只在按把手时触发，长按手势就空出来给菜单用了。
     */
    var onStartDrag: ((RecyclerView.ViewHolder) -> Unit)? = null

    /**
     * 多选模式（2.15 批量删除）。
     * 选择用**位置索引**记录——列表刷新（[submit]）时会清空，
     * 避免删除后索引错位选错东西。
     */
    private var batchMode = false
    private val selected = LinkedHashSet<Int>()

    /** 选择数量变化回调（顶部操作栏显示「已选 N 项」用）。 */
    var onSelectionChanged: ((Int) -> Unit)? = null

    fun submit(list: List<Song>) {
        items = list
        // 列表换了 → 位置索引失效，选择必须清掉（否则会选错/越界）
        selected.clear()
        notifyDataSetChanged()
        if (batchMode) onSelectionChanged?.invoke(0)
    }

    /** 进入/退出多选模式（退出时清空已选）。 */
    fun setBatchMode(on: Boolean) {
        if (batchMode == on) return
        batchMode = on
        selected.clear()
        notifyDataSetChanged()
        onSelectionChanged?.invoke(0)
    }

    fun isBatchMode(): Boolean = batchMode

    /** 点一下切换选中（多选模式下点整行就是勾选，不再播放）。 */
    fun toggleSelect(position: Int) {
        if (!batchMode || position !in items.indices) return
        if (!selected.remove(position)) selected.add(position)
        notifyItemChanged(position)
        onSelectionChanged?.invoke(selected.size)
    }

    fun selectedCount(): Int = selected.size

    /** 已选中的歌曲（按列表顺序）。 */
    fun selectedSongs(): List<Song> = selected.sorted().mapNotNull { items.getOrNull(it) }

    fun selectAll() {
        if (!batchMode) return
        selected.clear()
        selected.addAll(items.indices)
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selected.size)
    }

    fun clearSelection() {
        if (selected.isEmpty()) return
        selected.clear()
        notifyDataSetChanged()
        onSelectionChanged?.invoke(0)
    }

    /** 是否显示拖拽把手（与 dragEnabled 同步，2.14）。 */
    fun setDragHandleVisible(visible: Boolean) {
        if (dragHandleVisible == visible) return
        dragHandleVisible = visible
        notifyDataSetChanged()
    }

    /**
     * 歌单拖拽排序：移动指定项并刷新。
     * @return 移动后的新列表——调用方必须用它同步自己的数据源
     * （此前版本不返回新列表，页面外的 currentSongs 仍是旧顺序，
     * 导致排序保存的是旧顺序、播放队列也不跟随，重进歌单即还原）。
     */
    fun move(from: Int, to: Int): List<Song> {
        if (from !in items.indices || to !in items.indices) return items
        val list = items.toMutableList()
        val item = list.removeAt(from)
        list.add(to, item)
        items = list
        notifyItemMoved(from, to)
        return list
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
        CoverLoader.load(holder.itemView.context, song.uri, 64, folder = song.folder, songSize = song.size) { bmp ->
            if (bmp != null && holder.bindingAdapterPosition == position) {
                holder.cover.setImageBitmap(bmp)
            }
        }
        // 多选（2.15）：批量模式下左侧显示勾选框、隐藏序号（避免两个数字并排看混）
        holder.check.visibility = if (batchMode) View.VISIBLE else View.GONE
        holder.index.visibility = if (batchMode) View.GONE else View.VISIBLE
        if (batchMode) {
            holder.check.setImageResource(
                if (position in selected) R.drawable.ic_check_on else R.drawable.ic_check_off
            )
            holder.title.setTextColor(
                ContextCompat.getColor(holder.itemView.context, R.color.text_primary)
            )
            holder.title.typeface = if (position in selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        holder.itemView.setOnClickListener {
            // 批量模式下点整行 = 勾选/取消（不再播放，否则一边选一边跳走）
            if (batchMode) toggleSelect(position) else onClick(position)
        }
        holder.itemView.setOnLongClickListener {
            // 批量模式下长按不弹菜单（已在选择中，菜单会打断）
            if (batchMode) {
                true
            } else {
                onLongClick?.invoke(song)
                true
            }
        }
        // 拖拽把手（2.14）：按住它才能拖，长按其它区域留给菜单——两个手势各走各的。
        // 不可排序时（歌手页/搜索结果/收藏）整个把手隐藏，列表观感与以前一致。
        // 批量模式下也隐藏：正在选东西时不该能拖动。
        val showHandle = dragHandleVisible && !batchMode
        holder.dragHandle.visibility = if (showHandle) View.VISIBLE else View.GONE
        if (showHandle) {
            holder.dragHandle.setOnTouchListener { v, ev ->
                if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                    onStartDrag?.invoke(holder)
                }
                // 返回 false：把手自己不做拖动动画，交给 ItemTouchHelper 接管（它会重绘该项）
                false
            }
        } else {
            holder.dragHandle.setOnTouchListener(null)
        }
    }

    override fun getItemCount(): Int = items.size

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val index: TextView = itemView.findViewById(R.id.txtSongIndex)
        val title: TextView = itemView.findViewById(R.id.txtSongTitle)
        val lyricMark: TextView = itemView.findViewById(R.id.txtHasLyric)
        val cover: ImageView = itemView.findViewById(R.id.imgCover)
        val dragHandle: ImageView = itemView.findViewById(R.id.imgDragHandle)
        val check: ImageView = itemView.findViewById(R.id.imgCheck)
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
    private var transColor = -1 // -1 = 默认 text_hint（2.12.1：封面背景下换亮色）
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

    fun applyStyle(
        sizeSp: Int,
        font: Int,
        idleColorArgb: Int = -1,
        curColorArgb: Int = -1,
        transColorArgb: Int = -1
    ) {
        lyricSizeSp = sizeSp.toFloat()
        fontMode = font
        idleColor = idleColorArgb
        curColor = curColorArgb
        transColor = transColorArgb
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
            // 封面背景下 accentDark 会沉进封面（2.12.1）→ 统一用亮色译文
            holder.trans.setTextColor(
                if (transColor != -1) transColor else ThemeManager.accentDark(accent)
            )
        } else {
            holder.itemView.setBackgroundResource(0)
            val ctx = holder.text.context
            holder.text.setTextColor(
                if (idleColor != -1) idleColor
                else ctx.getColor(R.color.text_normal)
            )
            // 封面背景下 text_hint 会糊进封面（2.12.1）
            holder.trans.setTextColor(
                if (transColor != -1) transColor else ctx.getColor(R.color.text_hint)
            )
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
