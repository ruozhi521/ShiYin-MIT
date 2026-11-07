package com.example.subtitleplayer

import android.net.Uri
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
 * - 长按任意行：交给调用方弹菜单（2.14）
 *
 * 2.14 改动：长按从「目录=打开歌单 / 歌单=设封面」统一改为**弹菜单**。
 * 因为删除功能也要挂在长按上，而一个手势不能有两个含义——菜单里同时放
 * 「设封面」与「删除」，两种情况（目录/歌单）都由调用方按 node 决定菜单项。
 */
class FolderTreeAdapter(
    private val onOpenFolder: (TreeNode) -> Unit,
    private val onOpenPlaylist: (Playlist) -> Unit,
    private val onLongClickNode: (TreeNode) -> Unit
) : RecyclerView.Adapter<FolderTreeAdapter.Holder>() {

    private var nodes: List<TreeNode> = emptyList()
    private var uiSizeSp = 14f

    /**
     * 封面是否「完整显示」（3.0.5）。
     *
     * false（默认）= centerCrop 裁满，保持历史观感；true = fitCenter，整张图缩进容器不裁切，
     * 空出的部分露出 ImageView 的渐变/纯色底。派生自设置里的「封面显示方式」，见 [applyCoverScale]。
     */
    private var coverFullImage = false

    /** 设置变更后下发（3.0.5）。 */
    fun applyCoverFit(fullImage: Boolean) {
        if (coverFullImage == fullImage) return
        coverFullImage = fullImage
        notifyDataSetChanged()
    }

    /**
     * 这个节点该显示哪张文件夹图（3.0.6）。
     *
     * 先看本文件夹挑出的封面；自己没有就看子树 —— 常见情况是**根目录里放了一张图**，
     * 各个子文件夹里没有，这时子文件夹行也该显示那张。
     */
    private fun coverUriFor(node: TreeNode): Uri? {
        CoverLoader.folderCoverOf(node.path)?.let { return it }
        for (c in node.children) {
            coverUriFor(c)?.let { return it }
        }
        return null
    }


    /** 是否显示拖拽把手（3.0.6，仅当树形可拖动时开）。 */
    private var dragHandleVisible = false

    /**
     * 该项能否拖动（3.0.6）。
     *
     * 「本文件夹的歌曲」那一行是虚拟节点（path 与父目录相同、只为展示本层歌曲），
     * 它不参与排序 —— 拖了会在下次 [submit] 时被重新拼回第一位。
     */
    var canDragItem: ((Int) -> Boolean)? = null

    /** 把手按下 → 请求外部发起拖拽（与网格版同一约定）。 */
    var onStartDrag: ((RecyclerView.ViewHolder) -> Unit)? = null

    /** 显示当前层节点列表（由 MainActivity 按路径栈提供）。 */
    fun submit(list: List<TreeNode>) {
        nodes = list
        notifyDataSetChanged()
    }

    /** 是否显示拖拽把手（3.0.6）。 */
    fun setDragHandleVisible(visible: Boolean) {
        if (dragHandleVisible == visible) return
        dragHandleVisible = visible
        notifyDataSetChanged()
    }

    /**
     * 拖拽排序：移动指定项并刷新（3.0.6）。
     * @return 移动后的新列表——调用方必须用它同步自己的数据源（与网格版同一约定）。
     */
    fun move(from: Int, to: Int): List<TreeNode> {
        if (from !in nodes.indices || to !in nodes.indices) return nodes
        val list = nodes.toMutableList()
        val item = list.removeAt(from)
        list.add(to, item)
        nodes = list
        notifyItemMoved(from, to)
        return list
    }

    /** 当前展示的节点（拖拽松手时取它来保存顺序）。 */
    fun currentItems(): List<TreeNode> = nodes

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
        applyCoverScale(holder.cover, coverFullImage)
        holder.name.text = node.name
        holder.name.setTextSize(uiSizeSp)

        val isDir = node.isDir
        if (isDir) {
            // 目录：封面（若有）+ "›" 进入箭头
            // 3.0.2.1：与发现页 / 歌单网格一致 —— 无封面时铺低饱和渐变（按路径稳定选色，
            // 滚动 / Holder 复用都不跳色），不再是白底 + 文件夹图标（弱志反馈树形目录没有随机色）
            //
            // 3.0.6：**这里以前从不加载封面**，永远只画一个文件夹图标 ——
            // 粉丝「文件夹里有图也显示不出来」的根因就在这（不是扫描没识别到，
            // 而是树形的目录行压根没去取）。现在先铺渐变+图标当占位，
            // 取到文件夹封面就覆盖上去。
            holder.cover.setBackground(
                PlaylistGradient.drawable(holder.itemView.context, node.path, 20f)
            )
            holder.cover.setImageResource(R.drawable.ic_folder_tinted)
            holder.arrow.visibility = View.VISIBLE
            holder.arrow.text = "›"
            holder.count.visibility = View.INVISIBLE
            coverUriFor(node)?.let { imgUri ->
                CoverLoader.loadFolderCover(holder.itemView.context, imgUri, 120) { bmp ->
                    if (bmp != null && holder.bindingAdapterPosition == position) {
                        holder.cover.setImageBitmap(bmp)
                    }
                }
            }
        } else {
            // 歌单：封面（自定义/首曲）+ 歌曲数
            holder.arrow.visibility = View.INVISIBLE
            holder.count.visibility = View.VISIBLE
            holder.count.text = holder.itemView.context
                .getString(R.string.songs_count, node.playlist?.songs?.size ?: 0)
            // 3.0.2.1：同上 —— 渐变兜底，与歌单网格用同一个按名选色的色板
            holder.cover.setBackground(
                PlaylistGradient.drawableFor(holder.itemView.context, node.name)
            )
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
                        CoverLoader.load(holder.itemView.context, song.uri, 120, folder = song.folder, songSize = song.size) { bmp ->
                            if (bmp != null && holder.bindingAdapterPosition == position) {
                                holder.cover.setImageBitmap(bmp)
                            }
                        }
                    }
                }
            }
        }

        // 拖拽把手（3.0.6）：只有可排序时显示；按住它才拖，长按行仍归菜单
        val showHandle = dragHandleVisible && (canDragItem?.invoke(position) ?: true)
        holder.dragHandle.visibility = if (showHandle) View.VISIBLE else View.GONE
        if (showHandle) {
            holder.dragHandle.setOnTouchListener { _, ev ->
                if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                    onStartDrag?.invoke(holder)
                }
                false
            }
        } else {
            holder.dragHandle.setOnTouchListener(null)
        }

        holder.itemView.setOnClickListener {
            if (isDir) {
                onOpenFolder(node)
            } else {
                node.playlist?.let(onOpenPlaylist)
            }
        }
        holder.itemView.setOnLongClickListener {
            // 2.14：统一交给调用方弹菜单（目录行也能删）。原来的「目录=打开歌单」
            // 改由点击进入后自然看到，不再占用长按。
            onLongClickNode(node)
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
        val dragHandle: ImageView = itemView.findViewById(R.id.imgTreeDragHandle)
    }
}
