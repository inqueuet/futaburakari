package com.valoser.futaburakari

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.valoser.futaburakari.databinding.ItemHistoryBinding
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
        val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val timeText = if (item.isArchived && item.archivedAt > 0L) {
            val t = df.format(Date(item.archivedAt))
            "アーカイブ: $t"
        } else if (item.unreadCount > 0 && item.lastUpdatedAt > 0L) {
            // 未読ありは更新時刻を優先表示
            val t = df.format(Date(item.lastUpdatedAt))
            "更新: $t  •  未読 ${item.unreadCount}"
        } else {
            val t = df.format(Date(item.lastViewedAt))
            "閲覧: $t"
        }
        holder.binding.timeTextView.text = timeText

        // 未読バッジ
        val badge = holder.binding.unreadBadge
        if (item.unreadCount > 0) {
            badge.visibility = android.view.View.VISIBLE
            badge.text = if (item.unreadCount > 999) "999+" else item.unreadCount.toString()
        } else {
            badge.visibility = android.view.View.GONE
        }

        // アーカイブバッジは廃止（表示しない）
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
