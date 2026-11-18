package es.antoniocm.photocollage.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import es.antoniocm.photocollage.utils.BitmapProcessor
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

// Clase para generar el collage final y guardarlo o prepararlo para compartir.
class CollageExporter(context: Context) {

    private val appContext = context.applicationContext

    // Crea el collage, lo guarda en caché y devuelve una URI compartible de forma segura.
    fun createShareUri(photos: List<Bitmap>, columns: Int): Uri {
        val collage = BitmapProcessor.createCollage(photos, columns)
        try {
            val directory = File(appContext.cacheDir, CACHE_DIRECTORY)
            if (!directory.exists() && !directory.mkdirs()) {
                throw IOException("No se pudo crear el directorio de caché.")
            }
            val file = File(directory, "photocollage_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { output ->
                if (!collage.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw IOException("No se pudo codificar el PNG.")
                }
            }
            return FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                file
            )
        } finally {
            collage.recycle()
        }
    }

    // Guarda la composición como JPEG en la galería usando la colección MediaStore.
    fun saveToGallery(photos: List<Bitmap>, columns: Int) {
        val collage = BitmapProcessor.createCollage(photos, columns)
        try {
            saveBitmapToGallery(collage)
        } finally {
            collage.recycle()
        }
    }

    // Publica el archivo en Imágenes/PhotoCollage solo después de escribirlo correctamente.
    private fun saveBitmapToGallery(bitmap: Bitmap) {
        val resolver = appContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "PhotoCollage_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, GALLERY_DIRECTORY)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore no pudo crear el archivo.")

        try {
            val output = resolver.openOutputStream(uri)
                ?: throw IOException("No se pudo abrir la salida de MediaStore.")
            output.use {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)) {
                    throw IOException("No se pudo codificar el JPEG.")
                }
            }
            val publishedValues = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            if (resolver.update(uri, publishedValues, null, null) == 0) {
                throw IOException("MediaStore no pudo publicar el archivo.")
            }
        } catch (exception: Exception) {
            resolver.delete(uri, null, null)
            throw exception
        }
    }

    private companion object {
        const val CACHE_DIRECTORY = "collages"
        const val GALLERY_DIRECTORY = "Pictures/PhotoCollage"
        const val JPEG_QUALITY = 95
    }
}
