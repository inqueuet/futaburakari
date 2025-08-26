package com.valoser.futaburakari

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.valoser.futaburakari.databinding.ItemBookmarkBinding

class BookmarkAdapter(
    private val onEditClick: (Bookmark) -> Unit,
    private val onDeleteClick: (Bookmark) -> Unit,
    private val onItemClick: (Bookmark) -> Unit
) : ListAdapter<Bookmark, BookmarkAdapter.BookmarkViewHolder>(BookmarkDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookmarkViewHolder {
        val binding = ItemBookmarkBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return BookmarkViewHolder(binding)
    }

    override fun onBindViewHolder(holder: BookmarkViewHolder, position: Int) {
        val bookmark = getItem(position)
        holder.bind(bookmark, onEditClick, onDeleteClick, onItemClick)
    }

    class BookmarkViewHolder(private val binding: ItemBookmarkBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(
            bookmark: Bookmark,
            onEditClick: (Bookmark) -> Unit,
            onDeleteClick: (Bookmark) -> Unit,
            onItemClick: (Bookmark) -> Unit
        ) {
            binding.tvBookmarkName.text = bookmark.name
            binding.tvBookmarkUrl.text = bookmark.url
            binding.btnEditBookmark.setOnClickListener { onEditClick(bookmark) }
            binding.btnDeleteBookmark.setOnClickListener { onDeleteClick(bookmark) }
            itemView.setOnClickListener { onItemClick(bookmark) }
        }
    }

    private class BookmarkDiffCallback : DiffUtil.ItemCallback<Bookmark>() {
        override fun areItemsTheSame(oldItem: Bookmark, newItem: Bookmark): Boolean {
            return oldItem.url == newItem.url // Assuming URL is a unique identifier
        }

        override fun areContentsTheSame(oldItem: Bookmark, newItem: Bookmark): Boolean {
            return oldItem == newItem
        }
    }
}
