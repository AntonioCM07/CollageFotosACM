package es.antoniocm.photocollage.adapter

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import es.antoniocm.photocollage.R
import es.antoniocm.photocollage.databinding.ItemPhotoBinding
import es.antoniocm.photocollage.model.CollagePhoto

// Clase para presentar las fotos y notificar cuál ha pulsado el usuario.
class PhotoGridAdapter(
    private val onPhotoSelected: (Uri) -> Unit
) : RecyclerView.Adapter<PhotoGridAdapter.PhotoViewHolder>() {

    private var photos: List<CollagePhoto> = emptyList()
    private var selectedUri: Uri? = null

    // Actualiza las fotos y refleja el elemento seleccionado por el ViewModel.
    fun submitPhotos(newPhotos: List<CollagePhoto>, newSelectedUri: Uri?) {
        photos = newPhotos
        selectedUri = newSelectedUri
        notifyDataSetChanged()
    }

    // Crea una celda vinculada con ViewBinding para cada foto de la cuadrícula.
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val binding = ItemPhotoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PhotoViewHolder(binding)
    }

    // Actualiza la imagen y el marco de selección de la celda visible.
    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        holder.bind(photos[position], photos[position].uri == selectedUri, onPhotoSelected)
    }

    override fun getItemCount(): Int = photos.size

    // Clase para enlazar una foto con las vistas de una celda.
    class PhotoViewHolder(
        private val binding: ItemPhotoBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        // Muestra la miniatura y delega la selección usando el identificador de la foto.
        fun bind(photo: CollagePhoto, isSelected: Boolean, onPhotoSelected: (Uri) -> Unit) {
            binding.photoImage.setImageBitmap(photo.currentBitmap)
            binding.photoFrame.setBackgroundResource(
                if (isSelected) R.drawable.photo_frame_selected else R.drawable.photo_frame_normal
            )
            binding.root.setOnClickListener { onPhotoSelected(photo.uri) }
        }
    }
}
