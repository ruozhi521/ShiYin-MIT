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
        holder.index.text = (position + 1).toString()
        holder.title.text = song.title
        holder.title.setTextSize(uiSizeSp)
        holder.artist.text = song.folder
        holder.cover.setImageResource(R.drawable.ic_music_tinted)
        CoverLoader.load(holder.itemView.context, song.uri, 200) { bmp ->
            if (bmp != null && holder.bindingAdapterPosition == position) {
                holder.cover.setImageBitmap(bmp)
            }
        }
        holder.itemView.setOnClickListener { onClick(position) }
    }

    override fun getItemCount(): Int = items.size

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val cover: ImageView = itemView.findViewById(R.id.imgCardCover)
        val index: TextView = itemView.findViewById(R.id.txtCardIndex)
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
            .inflate(R.layout.item_playlist_grid, parent, false)
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
        val custom = CoverManager.playlistCover(holder.itemView.context, playlist.name)
        if (custom != null) {
            CoverLoader.loadFile(holder.itemView.context, custom, "pl:" + playlist.name, 200) { bmp ->
                if (bmp != null && holder.bindingAdapterPosition == position) {
                    holder.cover.setImageBitmap(bmp)
                }
            }
        } else if (song != null) {
            CoverLoader.load(holder.itemView.context, song.uri, 200) { bmp ->
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
    }

    override fun getItemCount(): Int = items.size

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val cover: ImageView = itemView.findViewById(R.id.imgGridCover)
        val name: TextView = itemView.findViewById(R.id.txtGridName)
        val count: TextView = itemView.findViewById(R.id.txtGridCount)
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
        holder.itemView.setOnClickListener { onClick(position) }
    }

    override fun getItemCount(): Int = artists.size

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val avatar: ImageView = itemView.findViewById(R.id.imgArtistAvatar)
        val name: TextView = itemView.findViewById(R.id.txtArtistName)
        val count: TextView = itemView.findViewById(R.id.txtArtistCount)
    }
}
