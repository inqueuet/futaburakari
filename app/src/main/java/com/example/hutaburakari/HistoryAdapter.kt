package com.example.hutaburakari

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.hutaburakari.databinding.ItemHistoryBinding
import java.text.DateFormat
import java.util.Date
import coil.load

class HistoryAdapter(
    private val onClick: (HistoryEntry) -> Unit
) : ListAdapter<HistoryEntry, HistoryAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<HistoryEntry>() {
            override fun areItemsTheSame(oldItem: HistoryEntry, newItem: HistoryEntry): Boolean = oldItem.key == newItem.key
            override fun areContentsTheSame(oldItem: HistoryEntry, newItem: HistoryEntry): Boolean = oldItem == newItem
        }
    }

    class VH(val binding: ItemHistoryBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.binding.titleTextView.text = item.title
        holder.binding.urlTextView.text = item.url
        holder.binding.timeTextView.text = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.lastViewedAt))
        // サムネイル
        val iv = holder.binding.thumbnailImageView
        if (item.thumbnailUrl.isNullOrBlank()) {
            iv.setImageDrawable(null)
            iv.setBackgroundResource(android.R.color.darker_gray)
        } else {
            iv.load(item.thumbnailUrl) {
                crossfade(true)
            }
        }
        holder.binding.root.setOnClickListener { onClick(item) }
    }
}
