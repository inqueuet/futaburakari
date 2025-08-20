package com.example.hutaburakari

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load

class ImageAdapter : ListAdapter<ImageItem, ImageAdapter.ImageViewHolder>(DiffCallback()) {

    var onItemClick: ((ImageItem) -> Unit)? = null
    private var originalList: List<ImageItem> = emptyList() // フィルター前のオリジナルリスト

    // オリジナルリストをセットし、表示を更新するメソッド
    fun submitOriginalList(list: List<ImageItem>) {
        originalList = list
        submitList(list)
    }

    class ImageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val imageView: ImageView = view.findViewById(R.id.imageView)
        private val titleTextView: TextView = view.findViewById(R.id.titleTextView)
        private val replyCountTextView: TextView = view.findViewById(R.id.replyCountTextView)

        fun bind(item: ImageItem) {
            titleTextView.text = item.title
            replyCountTextView.text = item.replyCount
            imageView.contentDescription = item.title

            val urlToLoad = item.fullImageUrl ?: item.previewUrl
            imageView.load(urlToLoad) {
                crossfade(true)
                placeholder(R.drawable.ic_launcher_background)
                error(android.R.drawable.ic_dialog_alert)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.grid_item, parent, false)
        return ImageViewHolder(view)
    }

    override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item)
        holder.itemView.setOnClickListener {
            onItemClick?.invoke(item)
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<ImageItem>() {
        override fun areItemsTheSame(oldItem: ImageItem, newItem: ImageItem): Boolean {
            return oldItem.detailUrl == newItem.detailUrl
        }

        override fun areContentsTheSame(oldItem: ImageItem, newItem: ImageItem): Boolean {
            return oldItem == newItem
        }
    }
}
