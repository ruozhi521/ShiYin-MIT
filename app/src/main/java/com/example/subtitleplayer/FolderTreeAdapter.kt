package com.example.subtitleplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * 音乐库文件夹树（Poweramp 式缩进展开树）：
 * - 目录行：点击展开/收起，长按直接打开该文件夹歌单（若自身有歌）
 * - 歌单叶子：点击播放，长按设置封面
 */
class FolderTreeAdapter(
    private val onOpenPlaylist: (Playlist) -> Unit,
    private val onLongClickPlaylist: (Playlist) -> Unit
) : RecyclerView.Adapter<FolderTreeAdapter.Holder>() {

    private var roots: List<TreeNode> = emptyList()
    private val expanded = HashSet<String>()
    private var visible: List<TreeNode> = emptyList()
    private var uiSizeSp = 14f

    fun submit(playlists: List<Playlist>) {
        roots = LibraryTree.build(playlists)
        visible = LibraryTree.flatten(roots, expanded)
        notifyDataSetChanged()
    }

    fun applyUiSize(sizeSp: Int) {
        uiSizeSp = sizeSp.toFloat()
        notifyDataSetChanged()
    }

    private fun toggle(node: TreeNode) {
        if (!expanded.remove(node.path)) expanded.add(node.path)
        visible = LibraryTree.flatten(roots, expanded)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tree_node, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val node = visible[position]
        // 缩进 = 基础 4dp + 层级 * 18dp（顶层也留一点，箭头不贴边）
        val density = holder.itemView.resources.displayMetrics.density
        val indent = ((4 + node.depth * 18) * density).toInt()
        holder.row.setPadding(indent, holder.row.paddingTop, holder.row.paddingEnd, holder.row.paddingBottom)
        holder.name.text = node.name
        holder.name.setTextSize(uiSizeSp)

        val isLeaf = !node.isDir
        holder.arrow.visibility = if (isLeaf) View.GONE else View.VISIBLE
        holder.arrow.text = if (expanded.contains(node.path)) "▼" else "▶"
        holder.count.text = holder.itemView.context
            .getString(R.string.songs_count, if (isLeaf) node.playlist?.songs?.size ?: 0 else node.totalSongs)

        if (isLeaf) {
            // 叶子：先占位音符图标，再尝试歌单封面/首曲封面
            holder.cover.setImageResource(R.drawable.ic_music_tinted)
            val pl = node.playlist
            if (pl != null) {
                val custom = CoverManager.playlistCover(holder.itemView.context, pl.name)
                if (custom != null) {
                    CoverLoader.loadFile(holder.itemView.context, custom, "pl:" + pl.name, 120) { bmp ->
                        if (bmp != null && holder.bindingAdapterPosition == position) {
                            holder.cover.setImageBitmap(bmp)
                        }
                    }
                } else {
                    val song = pl.songs.firstOrNull()
                    if (song != null) {
                        CoverLoader.load(holder.itemView.context, song.uri, 120, folder = song.folder) { bmp ->
                            if (bmp != null && holder.bindingAdapterPosition == position) {
                                holder.cover.setImageBitmap(bmp)
                            }
                        }
                    }
                }
            }
        } else {
            holder.cover.setImageResource(R.drawable.ic_folder_tinted)
        }

        holder.itemView.setOnClickListener {
            if (node.isDir) {
                toggle(node)
            } else {
                node.playlist?.let(onOpenPlaylist)
            }
        }
        holder.itemView.setOnLongClickListener {
            if (node.isDir) {
                // 目录自身有歌：长按直接打开该歌单（与短按展开区分）
                node.playlist?.let(onOpenPlaylist)
            } else {
                node.playlist?.let(onLongClickPlaylist)
            }
            true
        }
    }

    override fun getItemCount(): Int = visible.size

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val row: View = itemView
        val arrow: TextView = itemView.findViewById(R.id.txtTreeArrow)
        val cover: ImageView = itemView.findViewById(R.id.imgTreeCover)
        val name: TextView = itemView.findViewById(R.id.txtTreeName)
        val count: TextView = itemView.findViewById(R.id.txtTreeCount)
    }
}
