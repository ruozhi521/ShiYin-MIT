package com.example.subtitleplayer

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * 选歌器的一行（3.0.6）。
 *
 * 弱志的设计：不要「把全库几千首拍平成一个多选框」，要**像音乐库一样浏览** ——
 * 单击文件夹进去挑歌、长按文件夹进入多选。
 *
 * ⚠️ 歌曲那项刻意**不叫** Song：那会在这个 sealed class 作用域里遮蔽外部的 [Song]，
 * `data class Song(val song: Song)` 会解析成递归类型。
 */
sealed class PickerItem {
    /** 子文件夹。[checked] 仅「文件夹多选」模式下有意义。 */
    data class Folder(val node: TreeNode, val checked: Boolean) : PickerItem()

    /** 一首歌。[checked] = 当前是否被勾选。 */
    data class Track(val song: Song, val checked: Boolean) : PickerItem()
}

/**
 * 选歌器列表适配器（3.0.6）。
 *
 * 勾选状态由调用方持有（[PickerItem.Track.checked] / [PickerItem.Folder.checked]），
 * 适配器只渲染 + 回调 —— 状态只有一份，不会出现「适配器与页面各存一套、对不上」。
 */
class SongPickerAdapter(
    private val onClick: (Int) -> Unit,
    private val onLongClick: (Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<PickerItem> = emptyList()
    private var uiSizeSp = 15f
    /** 是否处于「文件夹多选」模式（长按文件夹后开启，此时文件夹行显示勾选框）。 */
    private var folderSelectMode = false

    fun submit(list: List<PickerItem>) {
        items = list
        notifyDataSetChanged()
    }

    fun setFolderSelectMode(on: Boolean) {
        if (folderSelectMode == on) return
        folderSelectMode = on
        notifyDataSetChanged()
    }

    fun applyUiSize(sizeSp: Int) {
        uiSizeSp = sizeSp.toFloat()
        notifyDataSetChanged()
    }

    fun itemAt(position: Int): PickerItem? = items.getOrNull(position)

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int =
        if (items[position] is PickerItem.Folder) TYPE_FOLDER else TYPE_TRACK

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_FOLDER) {
            FolderHolder(inf.inflate(R.layout.item_picker_folder, parent, false))
        } else {
            TrackHolder(inf.inflate(R.layout.item_song, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val it = items[position]) {
            is PickerItem.Folder -> bindFolder(holder as FolderHolder, it)
            is PickerItem.Track -> bindTrack(holder as TrackHolder, it)
        }
    }

    private fun bindFolder(h: FolderHolder, item: PickerItem.Folder) {
        val node = item.node
        h.name.text = node.name
        // 显示「本层 + 子树」总首数，便于判断要不要整个加进去
        h.count.text = h.itemView.context.getString(R.string.picker_song_count, node.totalSongs)
        h.arrow.visibility = if (node.children.isNotEmpty()) View.VISIBLE else View.GONE
        // 勾选框只在文件夹多选模式下出现
        h.check.visibility = if (folderSelectMode) View.VISIBLE else View.GONE
        if (folderSelectMode) {
            h.check.setImageResource(
                if (item.checked) R.drawable.ic_check_on else R.drawable.ic_check_off
            )
            h.name.typeface = if (item.checked) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        } else {
            h.name.typeface = Typeface.DEFAULT
        }
        h.itemView.setOnClickListener { onClick(h.bindingAdapterPosition) }
        h.itemView.setOnLongClickListener { onLongClick(h.bindingAdapterPosition); true }
    }

    private fun bindTrack(h: TrackHolder, item: PickerItem.Track) {
        val song = item.song
        val ctx = h.itemView.context
        h.title.text = song.title
        h.title.setTextSize(uiSizeSp)
        h.title.typeface = if (item.checked) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        // 选歌界面不需要序号与拖拽把手；勾选框常显
        h.index.visibility = View.GONE
        h.dragHandle.visibility = View.GONE
        h.check.visibility = View.VISIBLE
        h.check.setImageResource(
            if (item.checked) R.drawable.ic_check_on else R.drawable.ic_check_off
        )
        // 无封面时给稳定彩色圆底（与全 app 其他列表同一套色板）
        h.cover.setBackground(PlaylistGradient.circleDrawable(ctx, song.uri.toString()))
        h.cover.setImageResource(R.drawable.ic_music_tinted)
        val pos = h.bindingAdapterPosition
        CoverLoader.load(ctx, song.uri, 64, folder = song.folder, songSize = song.size) { bmp ->
            if (bmp != null && h.bindingAdapterPosition == pos) h.cover.setImageBitmap(bmp)
        }
        h.itemView.setOnClickListener { onClick(h.bindingAdapterPosition) }
        h.itemView.setOnLongClickListener { onLongClick(h.bindingAdapterPosition); true }
    }

    class FolderHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val name: TextView = itemView.findViewById(R.id.txtPickerFolderName)
        val count: TextView = itemView.findViewById(R.id.txtPickerFolderCount)
        val arrow: ImageView = itemView.findViewById(R.id.imgPickerFolderArrow)
        val check: ImageView = itemView.findViewById(R.id.imgPickerFolderCheck)
    }

    class TrackHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val index: TextView = itemView.findViewById(R.id.txtSongIndex)
        val title: TextView = itemView.findViewById(R.id.txtSongTitle)
        val cover: ImageView = itemView.findViewById(R.id.imgCover)
        val dragHandle: ImageView = itemView.findViewById(R.id.imgDragHandle)
        val check: ImageView = itemView.findViewById(R.id.imgCheck)
    }

    private companion object {
        const val TYPE_FOLDER = 0
        const val TYPE_TRACK = 1
    }
}
