package com.adf.pvjointage.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.adf.pvjointage.data.Photo
import com.adf.pvjointage.databinding.ItemPhotoBinding

class PhotoAdapter(
    private val onDelete: (Photo) -> Unit
) : RecyclerView.Adapter<PhotoAdapter.VH>() {

    private var photos: List<Photo> = emptyList()

    fun submit(newPhotos: List<Photo>) {
        photos = newPhotos
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemPhotoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val photo = photos[position]
        holder.binding.imgPhoto.load(photo.filePath)
        holder.binding.btnSupprimer.setOnClickListener { onDelete(photo) }
    }

    override fun getItemCount() = photos.size

    class VH(val binding: ItemPhotoBinding) : RecyclerView.ViewHolder(binding.root)
}
