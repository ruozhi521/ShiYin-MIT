package com.example.subtitleplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** 发现页随机推荐卡片：大封面 + 歌名 + 歌手。 */
class DiscoverAdapter(
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<DiscoverAdapter.Holder>() {

    private var items: List<Song> = emptyList()
    private var uiSizeSp = 14f

    fun submit(list: List<Song>) {
        items = list
        notifyDataSetChanged()
    }

    fun applyUiSize(sizeSp: Int) {
        uiSizeSp = sizeSp.toFloat()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_discover_card, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val song = items[position]
        holder.title.text = song.title
        holder.title.setTextSize(uiSizeSp)
        holder.artist.text = song.artist.ifBlank { song.folder }
        // 3.0：无封面时给一个**稳定的彩色圆底**，不再是一块白/空心图标。
        // 按歌曲 uri 选色（不是随机数）——同一首歌每次进出颜色一致，
        // 否则列表滚动、RecyclerView 复用 Holder 时颜色会乱跳。
        // 与音乐库歌单网格共用同一套 8 组素雅色板，全 app 占位观感统一。
        val ctx = holder.itemView.context
        holder.cover.setBackground(
            PlaylistGradient.circleDrawable(ctx, song.uri.toString())
        )
        holder.cover.setImageResource(R.drawable.ic_music_tinted)
        CoverLoader.load(ctx, song.uri, 200, folder = song.folder, songSize = song.size) { bmp ->
            if (bmp != null && holder.bindingAdapterPosition == position) {
                holder.cover.setImageBitmap(bmp)
            }
        }
        holder.itemView.setOnClickListener { onClick(position) }
    }

    override fun getItemCount(): Int = items.size

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val cover: ImageView = itemView.findViewById(R.id.imgCardCover)
        val title: TextView = itemView.findViewById(R.id.txtCardTitle)
        val artist: TextView = itemView.findViewById(R.id.txtCardArtist)
    }
}

/** 音乐库歌单网格卡片：封面 + 名称 + 歌曲数。支持自定义封面与长按。 */
class PlaylistGridAdapter(
    private val onClick: (Int) -> Unit,
    private val onLongClick: (Int) -> Unit
) : RecyclerView.Adapter<PlaylistGridAdapter.Holder>() {

    private var items: List<Playlist> = emptyList()
    private var uiSizeSp = 14f
    /**
     * 封面高度（px）。0 = 用布局里的默认 172dp。
     * 3.0.2.1：歌单每行个数可调（粉丝反馈一列两个太大、翻找麻烦）——列数一变，
     * 卡片变窄，封面高度要跟着缩，否则卡片会被拉成竖长条。
     */
    private var coverHeightPx = 0
    /** 是否显示拖拽把手（3.0）。只有音乐库页的「默认顺序」下才开。 */
    private var dragHandleVisible = false
    /**
     * 把手按下 → 请求外部发起拖拽（3.0）。
     * 与 [SongAdapter.onStartDrag] 同一套约定：**按住把手**才拖，长按留给「设置封面」菜单。
     * 全部用长按会让「排序」与「菜单」抢同一个手势（2.14 在歌曲列表上踩过这个坑）。
     */
    var onStartDrag: ((RecyclerView.ViewHolder) -> Unit)? = null
    /**
     * 该项是否可拖（3.0）。
     * 首项是「全部歌曲」（音乐库页）或「收藏」（歌单页）——都是虚拟固定项，
     * 不参与排序，拖了也会被下次刷新摆回原位，所以直接不给拖。
     */
    var canDragItem: ((Int) -> Boolean)? = null

    fun submit(list: List<Playlist>) {
        items = list
        notifyDataSetChanged()
    }

    /** 是否显示拖拽把手（与页面是否可排序同步，3.0）。 */
    fun setDragHandleVisible(visible: Boolean) {
        if (dragHandleVisible == visible) return
        dragHandleVisible = visible
        notifyDataSetChanged()
    }

    /** 设置封面高度（px）；0 = 用布局默认值。列数变化时由 Activity 调用（3.0.2.1）。 */
    fun setCoverHeightPx(px: Int) {
        if (coverHeightPx == px) return
        coverHeightPx = px
        notifyDataSetChanged()
    }

    /**
     * 拖拽排序：移动指定项并刷新。
     * @return 移动后的新列表——调用方必须用它同步自己的数据源
     * （与 [SongAdapter.move] 同一约定：不返回新列表的话，页面外的列表仍是旧顺序，
     * 保存下去的顺序就是错的，重进页面即还原）。
     */
    fun move(from: Int, to: Int): List<Playlist> {
        if (from !in items.indices || to !in items.indices) return items
        val list = items.toMutableList()
        val item = list.removeAt(from)
        list.add(to, item)
        items = list
        notifyItemMoved(from, to)
        return list
    }

    fun applyUiSize(sizeSp: Int) {
        uiSizeSp = sizeSp.toFloat()
        notifyDataSetChanged()
    }

    /**
     * 当前展示的列表（3.0）。
     * 拖拽结束时要拿它来保存顺序——适配器内部的 items 才是拖动后的真实顺序，
     * 页面外重新算出来的列表还是旧的。
     */
    fun currentItems(): List<Playlist> = items

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_playlist_grid, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val playlist = items[position]
        // 3.0.2.1：按「歌单每行个数」调整封面高度（列多 → 卡片窄 → 封面跟着变矮，保持方形）
        if (coverHeightPx > 0) {
            val lp = holder.coverBox.layoutParams
            if (lp.height != coverHeightPx) {
                lp.height = coverHeightPx
                holder.coverBox.layoutParams = lp
            }
        }
        holder.name.text = playlist.name
        holder.name.setTextSize(uiSizeSp)
        holder.count.text = holder.itemView.context
            .getString(R.string.songs_count, playlist.displayCount ?: playlist.songs.size)
        // 3.0：先用该歌单的素雅渐变铺底（概念图里没有封面的歌单就是一块渐变，不是图标）。
        // 有自设封面 / 能取到歌曲封面时，下面加载出来的位图会盖住它，渐变自然让位；
        // 两者都没有（或加载失败）就保持渐变——比原来的纯白 + 文件夹图标更贴合目标稿。
        // ⚠️ 必须先 setImageDrawable(null)：RecyclerView 复用 Holder 时会残留上一项的封面图。
        val ctx = holder.itemView.context
        holder.cover.setBackground(PlaylistGradient.drawableFor(ctx, playlist.name))
        holder.cover.setImageDrawable(null)
        val song = playlist.songs.firstOrNull()
        val custom = CoverManager.playlistCover(ctx, playlist.name)
        if (custom != null) {
            CoverLoader.loadFile(ctx, custom, "pl:" + playlist.name, 200) { bmp ->
                if (bmp != null && holder.bindingAdapterPosition == position) {
                    holder.cover.setImageBitmap(bmp)
                }
            }
        } else if (song != null) {
            CoverLoader.load(ctx, song.uri, 200, folder = song.folder, songSize = song.size) { bmp ->
                if (bmp != null && holder.bindingAdapterPosition == position) {
                    holder.cover.setImageBitmap(bmp)
                }
            }
        }
        holder.itemView.setOnClickListener { onClick(position) }
        holder.itemView.setOnLongClickListener {
            onLongClick(position)
            true
        }
        // 拖拽把手（3.0）：按住它才拖，长按卡片仍走「设置封面」菜单——两个手势各走各的。
        // 不可排序时（非默认顺序 / 系统固定项）整个把手隐藏，观感与 3.0 之前一致。
        val showHandle = dragHandleVisible && (canDragItem?.invoke(position) ?: true)
        holder.dragHandle.visibility = if (showHandle) View.VISIBLE else View.GONE
        if (showHandle) {
            holder.dragHandle.setOnTouchListener { _, ev ->
                if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                    onStartDrag?.invoke(holder)
                }
                // 返回 false：把手自身不做拖动动画，交给 ItemTouchHelper 接管
                false
            }
        } else {
            holder.dragHandle.setOnTouchListener(null)
        }
    }

    override fun getItemCount(): Int = items.size

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val coverBox: View = itemView.findViewById(R.id.gridCoverBox)
        val cover: ImageView = itemView.findViewById(R.id.imgGridCover)
        val name: TextView = itemView.findViewById(R.id.txtGridName)
        val count: TextView = itemView.findViewById(R.id.txtGridCount)
        val dragHandle: ImageView = itemView.findViewById(R.id.imgGridDragHandle)
    }
}

/** 歌手列表：圆形音符头像 + 歌手名 + 歌曲数。 */
class ArtistAdapter(
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<ArtistAdapter.Holder>() {

    private var artists: List<Pair<String, Int>> = emptyList()

    fun submit(list: List<Pair<String, Int>>) {
        artists = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_artist, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val (name, count) = artists[position]
        holder.name.text = name
        holder.count.text = holder.itemView.context
            .getString(R.string.songs_count, count)
        holder.avatar.backgroundTintList = android.content.res.ColorStateList.valueOf(
            ThemeManager.accent(holder.itemView.context)
        )
        holder.itemView.setOnClickListener { onClick(position) }
    }

    override fun getItemCount(): Int = artists.size

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val avatar: ImageView = itemView.findViewById(R.id.imgArtistAvatar)
        val name: TextView = itemView.findViewById(R.id.txtArtistName)
        val count: TextView = itemView.findViewById(R.id.txtArtistCount)
    }
}
