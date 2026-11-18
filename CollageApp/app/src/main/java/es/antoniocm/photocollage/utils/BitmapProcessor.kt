package es.antoniocm.photocollage.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import es.antoniocm.photocollage.model.PhotoFilter
import java.io.IOException
import kotlin.math.roundToInt

// Reúne decodificación con muestreo, efectos no destructivos y composición del collage.
object BitmapProcessor {

    const val MAX_SOURCE_EDGE = 1_024
    private const val COLLAGE_CELL_SIZE = 480
    private const val COLLAGE_GUTTER = 12

    // Lee primero las dimensiones para elegir un factor de muestreo antes de asignar memoria.
    @Throws(IOException::class)
    fun decodeScaled(context: Context, uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("No se pudo abrir la imagen.")
        boundsStream.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("El archivo seleccionado no es una imagen válida.")
        }

        var sampleSize = 1
        while (bounds.outWidth / sampleSize > MAX_SOURCE_EDGE ||
            bounds.outHeight / sampleSize > MAX_SOURCE_EDGE
        ) {
            sampleSize *= 2
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmapStream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("No se pudo volver a abrir la imagen.")
        return bitmapStream.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        } ?: throw IOException("No se pudo decodificar la imagen.")
    }

    // Crea un bitmap nuevo solo si hay efectos y nunca modifica el bitmap original.
    fun render(
        original: Bitmap,
        filter: PhotoFilter,
        rotationDegrees: Int,
        brightness: Int,
        saturation: Int
    ): Bitmap {
        val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
        if (filter == PhotoFilter.NONE &&
            normalizedRotation == 0 &&
            brightness == 0 &&
            saturation == 100
        ) {
            return original
        }

        val colorMatrix = createColorMatrix(filter, brightness, saturation)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(colorMatrix)
        }
        val colored = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
        Canvas(colored).drawBitmap(original, 0f, 0f, paint)

        if (normalizedRotation == 0) return colored

        val rotation = Matrix().apply { postRotate(normalizedRotation.toFloat()) }
        val rotated = Bitmap.createBitmap(
            colored,
            0,
            0,
            colored.width,
            colored.height,
            rotation,
            true
        )
        if (rotated !== colored) colored.recycle()
        return rotated
    }

    // Compone la matriz de color con parámetros acotados para mantener resultados previsibles.
    private fun createColorMatrix(
        filter: PhotoFilter,
        brightness: Int,
        saturation: Int
    ): ColorMatrix {
        val matrix = ColorMatrix()
        when (filter) {
            PhotoFilter.NONE -> matrix.reset()
            PhotoFilter.GRAYSCALE -> matrix.setSaturation(0f)
            PhotoFilter.SEPIA -> matrix.set(
                floatArrayOf(
                    0.393f, 0.769f, 0.189f, 0f, 0f,
                    0.349f, 0.686f, 0.168f, 0f, 0f,
                    0.272f, 0.534f, 0.131f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        }

        // Saturación queda desactivada para el filtro monocromático elegido explícitamente.
        if (filter != PhotoFilter.GRAYSCALE) {
            matrix.postConcat(ColorMatrix().apply { setSaturation(saturation.coerceIn(0, 200) / 100f) })
        }

        // El brillo del control se traduce a un desplazamiento RGB entre -255 y +255.
        val offset = brightness.coerceIn(-100, 100) * 2.55f
        matrix.postConcat(
            ColorMatrix(
                floatArrayOf(
                    1f, 0f, 0f, 0f, offset,
                    0f, 1f, 0f, 0f, offset,
                    0f, 0f, 1f, 0f, offset,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        return matrix
    }

    // Dibuja cada foto en una celda cuadrada usando center-crop y un margen uniforme.
    fun createCollage(photos: List<Bitmap>, columns: Int): Bitmap {
        require(photos.isNotEmpty()) { "No hay imágenes para componer." }
        val safeColumns = columns.coerceIn(2, 3)
        val rows = (photos.size + safeColumns - 1) / safeColumns
        val width = safeColumns * COLLAGE_CELL_SIZE + (safeColumns + 1) * COLLAGE_GUTTER
        val height = rows * COLLAGE_CELL_SIZE + (rows + 1) * COLLAGE_GUTTER
        val collage = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(collage).apply { drawColor(Color.WHITE) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        photos.forEachIndexed { index, bitmap ->
            val column = index % safeColumns
            val row = index / safeColumns
            val left = (COLLAGE_GUTTER + column * (COLLAGE_CELL_SIZE + COLLAGE_GUTTER)).toFloat()
            val top = (COLLAGE_GUTTER + row * (COLLAGE_CELL_SIZE + COLLAGE_GUTTER)).toFloat()
            val destination = RectF(
                left,
                top,
                left + COLLAGE_CELL_SIZE,
                top + COLLAGE_CELL_SIZE
            )
            drawCenterCrop(canvas, bitmap, destination, paint)
        }
        return collage
    }

    // Calcula el recorte central sin crear una copia escalada temporal de cada fotografía.
    private fun drawCenterCrop(canvas: Canvas, bitmap: Bitmap, destination: RectF, paint: Paint) {
        val destinationRatio = destination.width() / destination.height()
        val bitmapRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
        val source = if (bitmapRatio > destinationRatio) {
            val croppedWidth = bitmap.height * destinationRatio
            val left = (bitmap.width - croppedWidth) / 2f
            Rect(left.roundToInt(), 0, (left + croppedWidth).roundToInt(), bitmap.height)
        } else {
            val croppedHeight = bitmap.width / destinationRatio
            val top = (bitmap.height - croppedHeight) / 2f
            Rect(0, top.roundToInt(), bitmap.width, (top + croppedHeight).roundToInt())
        }
        canvas.drawBitmap(bitmap, source, destination, paint)
    }
}
