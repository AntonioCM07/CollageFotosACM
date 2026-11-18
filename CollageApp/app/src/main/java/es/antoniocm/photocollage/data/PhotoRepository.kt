package es.antoniocm.photocollage.data

import android.content.Context
import android.net.Uri
import android.util.Log
import es.antoniocm.photocollage.model.CollagePhoto
import es.antoniocm.photocollage.utils.BitmapProcessor

// Clase para cargar fotos desde sus Uri y respetar un presupuesto de memoria.
class PhotoRepository(context: Context) {

    private val appContext = context.applicationContext

    // Decodifica las fotos nuevas y descarta las que exceden el límite de memoria.
    fun loadPhotos(uris: List<Uri>, bytesAlreadyInUse: Long): PhotoLoadResult {
        val loadedPhotos = mutableListOf<CollagePhoto>()
        var usedBytes = bytesAlreadyInUse
        var skippedForMemory = 0
        var failedLoads = 0

        uris.forEach { uri ->
            try {
                val bitmap = BitmapProcessor.decodeScaled(appContext, uri)
                if (usedBytes + bitmap.allocationByteCount > MAX_SOURCE_BITMAP_BYTES) {
                    bitmap.recycle()
                    skippedForMemory++
                } else {
                    loadedPhotos += CollagePhoto(uri = uri, originalBitmap = bitmap)
                    usedBytes += bitmap.allocationByteCount
                }
            } catch (exception: Exception) {
                failedLoads++
                Log.w(TAG, "No se pudo cargar la imagen seleccionada: $uri", exception)
            } catch (error: OutOfMemoryError) {
                failedLoads++
                Log.e(TAG, "Memoria insuficiente al cargar la imagen: $uri", error)
            }
        }

        return PhotoLoadResult(loadedPhotos, skippedForMemory, failedLoads)
    }

    // Clase para resumir fotos cargadas y errores recuperables de lectura.
    data class PhotoLoadResult(
        val photos: List<CollagePhoto>,
        val skippedForMemory: Int,
        val failedLoads: Int
    )

    private companion object {
        const val TAG = "PhotoRepository"
        const val MAX_SOURCE_BITMAP_BYTES = 24L * 1024L * 1024L
    }
}
