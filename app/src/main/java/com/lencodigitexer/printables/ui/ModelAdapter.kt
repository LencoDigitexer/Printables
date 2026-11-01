package com.lencodigitexer.printables.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lencodigitexer.printables.R
import com.lencodigitexer.printables.data.PrintableModel
import com.lencodigitexer.printables.databinding.ItemModelCardBinding
import com.lencodigitexer.printables.util.PrintablesImageLoader
import kotlinx.coroutines.CoroutineScope

class ModelAdapter(
    private val scope: CoroutineScope,
    private val onModelClick: (PrintableModel) -> Unit,
    private val onBookmarkToggle: (PrintableModel) -> Unit
) : ListAdapter<PrintableModel, ModelAdapter.ModelViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ModelViewHolder {
        val binding = ItemModelCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ModelViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ModelViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ModelViewHolder(
        private val binding: ItemModelCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(model: PrintableModel) {
            val context = binding.root.context
            val imageLoader = PrintablesImageLoader.getInstance(context)

            binding.tvModelName.text = model.name
            binding.tvAuthorName.text = model.author ?: "Printables Maker"

            // Author avatar initial
            val initial = model.author?.firstOrNull()?.uppercaseChar()?.toString() ?: "P"
            binding.tvAuthorAvatar.text = initial

            // Stats
            binding.tvLikes.text = model.formattedLikes
            binding.tvDownloads.text = model.formattedDownloads

            // Rating
            if (model.ratingAvg != null && model.ratingAvg > 0) {
                binding.layoutRatingBadge.visibility = View.VISIBLE
                binding.tvRating.text = model.formattedRating
            } else {
                binding.layoutRatingBadge.visibility = View.GONE
            }

            // Bookmark icon
            updateBookmarkIcon(model.isBookmarked)

            binding.btnBookmark.setOnClickListener {
                onBookmarkToggle(model)
                updateBookmarkIcon(model.isBookmarked)
            }

            // Cover Image
            imageLoader.load(
                url = model.mainImageUrl,
                imageView = binding.ivCover,
                placeholderRes = R.drawable.bg_image_placeholder,
                scope = scope
            )

            // Click listener
            binding.cardModel.setOnClickListener {
                onModelClick(model)
            }
        }

        private fun updateBookmarkIcon(isBookmarked: Boolean) {
            val iconRes = if (isBookmarked) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark
            val iconTint = if (isBookmarked) {
                ContextCompat.getColor(binding.root.context, R.color.printables_orange)
            } else {
                ContextCompat.getColor(binding.root.context, R.color.white)
            }
            binding.btnBookmark.setImageResource(iconRes)
            binding.btnBookmark.setColorFilter(iconTint)
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<PrintableModel>() {
        override fun areItemsTheSame(oldItem: PrintableModel, newItem: PrintableModel): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: PrintableModel, newItem: PrintableModel): Boolean {
            return oldItem == newItem && oldItem.isBookmarked == newItem.isBookmarked
        }
    }
}
