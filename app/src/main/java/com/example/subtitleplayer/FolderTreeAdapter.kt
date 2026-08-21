package com.example.subtitleplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * 音乐库文件夹浏览（逐级进入，1.27）：
 * 每行 = 左侧封面 + 右侧名称；目录行右侧 "›"，歌单行右侧歌曲数。
 * - 点击目录：进入下一级（MainActivity 维护路径栈）
 * - 点击歌单：打开歌曲列表
 * - 长按目录（自身有歌）：打开该文件夹歌单；长按歌单：设置封面
 */
class FolderTreeAdapter(
    private val onOpenFolder: (TreeNode) -> Unit,
    private val onOpenPlaylist: (Playlist) -> Unit,
    private val onLongClickPlaylist: (Playlist) -> Unit
) : RecyclerView.Adapter<FolderTreeAdapter.Holder>() {

    private var nodes: List<TreeNode> = emptyList()
    private var uiSizeSp = 14f

    /** 显示当前层节点列表（由 MainActivity 按路径栈提供）。 */
    fun submit(list: List<TreeNode>) {
        nodes = list
        notifyDataSetChanged()
    }

    fun applyUiSize(sizeSp: Int) {
        uiSizeSp = sizeSp.toFloat()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tree_node, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val node = nodes[position]
        holder.name.text = node.name
        holder.name.setTextSize(uiSizeSp)

        val isDir = node.isDir
        if (isDir) {
            // 目录：文件夹图标 + "›" 进入箭头
            holder.cover.setImageResource(R.drawable.ic_folder_tinted)
            holder.arrow.visibility = View.VISIBLE
            holder.arrow.text = "›"
            holder.count.visibility = View.INVISIBLE
        } else {
            // 歌单：封面（自定义/首曲）+ 歌曲数
            holder.arrow.visibility = View.INVISIBLE
            holder.count.visibility = View.VISIBLE
            holder.count.text = holder.itemView.context
                .getString(R.string.songs_count, node.playlist?.songs?.size ?: 0)
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
        }

        holder.itemView.setOnClickListener {
            if (isDir) {
                onOpenFolder(node)
            } else {
                node.playlist?.let(onOpenPlaylist)
            }
        }
        holder.itemView.setOnLongClickListener {
            if (isDir) {
                // 目录自身有歌：长按直接打开该文件夹歌单
                node.playlist?.let(onOpenPlaylist)
            } else {
                node.playlist?.let(onLongClickPlaylist)
            }
            true
        }
    }

    override fun getItemCount(): Int = nodes.size

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val row: View = itemView
        val arrow: TextView = itemView.findViewById(R.id.txtTreeArrow)
        val cover: ImageView = itemView.findViewById(R.id.imgTreeCover)
        val name: TextView = itemView.findViewById(R.id.txtTreeName)
        val count: TextView = itemView.findViewById(R.id.txtTreeCount)
    }
}
