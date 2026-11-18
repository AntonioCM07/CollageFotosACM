package es.antoniocm.photocollage.model

import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.StringRes

// Clase para conservar el original y los ajustes de edición de cada foto.
data class CollagePhoto(
    val uri: Uri,
    val originalBitmap: Bitmap,
    val currentBitmap: Bitmap = originalBitmap,
    val filter: PhotoFilter = PhotoFilter.NONE,
    val rotationDegrees: Int = 0,
    val brightness: Int = 0,
    val saturation: Int = 100
)

// Enumeración de los filtros fotográficos disponibles.
enum class PhotoFilter {
    NONE,
    GRAYSCALE,
    SEPIA
}
