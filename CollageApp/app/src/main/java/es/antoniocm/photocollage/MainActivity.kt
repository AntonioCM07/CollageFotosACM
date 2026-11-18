package es.antoniocm.photocollage

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import es.antoniocm.photocollage.adapter.PhotoGridAdapter
import es.antoniocm.photocollage.databinding.ActivityMainBinding
import es.antoniocm.photocollage.export.CollageExporter
import es.antoniocm.photocollage.model.CollageUiState
import es.antoniocm.photocollage.model.PhotoFilter
import es.antoniocm.photocollage.model.UiMessage
import es.antoniocm.photocollage.viewmodel.CollageViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Clase para conectar los controles de la pantalla con el estado del collage.
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var photoAdapter: PhotoGridAdapter
    private val viewModel: CollageViewModel by viewModels()
    private val collageExporter by lazy { CollageExporter(this) }

    // Abre el selector nativo y entrega al ViewModel las fotos elegidas.
    private val photoPicker = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK_COUNT)
    ) { selectedUris ->
        if (selectedUris.isNotEmpty()) viewModel.addPhotos(selectedUris)
    }

    // Infla las vistas y conecta los eventos de interfaz con sus acciones.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        photoAdapter = PhotoGridAdapter(viewModel::selectPhoto)
        binding.photosRecyclerView.layoutManager = GridLayoutManager(this, INITIAL_COLUMN_COUNT)
        binding.photosRecyclerView.adapter = photoAdapter
        bindControls()
        observeViewModel()
    }

    // Enlaza botones y sliders sin incluir aquí la lógica de edición de imágenes.
    private fun bindControls() {
        binding.addButton.setOnClickListener {
            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        binding.deleteButton.setOnClickListener { viewModel.deleteSelectedPhoto() }
        binding.clearButton.setOnClickListener { confirmClearPhotos() }
        binding.grayscaleButton.setOnClickListener { viewModel.setFilter(PhotoFilter.GRAYSCALE) }
        binding.sepiaButton.setOnClickListener { viewModel.setFilter(PhotoFilter.SEPIA) }
        binding.resetButton.setOnClickListener { viewModel.resetSelectedPhoto() }
        binding.rotateButton.setOnClickListener { viewModel.rotateSelectedPhoto() }
        binding.columnsButton.setOnClickListener { viewModel.toggleColumns() }
        binding.shareButton.setOnClickListener { shareCollage() }
        binding.saveButton.setOnClickListener { saveCollage() }

        // Los cambios manuales de los sliders se aplican solo a la foto seleccionada.
        binding.brightnessSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) viewModel.setBrightness(value.toInt())
        }
        binding.saturationSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) viewModel.setSaturation(value.toInt())
        }
    }

    // Recoge los cambios de estado y mensajes mientras la Activity está visible.
    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect(::renderState)
                }
                launch {
                    viewModel.messages.collect(::showMessage)
                }
            }
        }
    }

    // Actualiza cuadrícula, selección, sliders y estado vacío desde una única fuente de verdad.
    private fun renderState(state: CollageUiState) {
        photoAdapter.submitPhotos(state.photos, state.selectedUri)
        binding.addButton.isEnabled = !state.isLoading
        binding.emptyText.visibility = if (state.photos.isEmpty()) View.VISIBLE else View.GONE
        binding.photosRecyclerView.visibility =
            if (state.photos.isEmpty()) View.INVISIBLE else View.VISIBLE

        val selected = state.photos.firstOrNull { it.uri == state.selectedUri }
        val brightness = selected?.brightness ?: 0
        val saturation = selected?.saturation ?: DEFAULT_SATURATION
        if (binding.brightnessSlider.value.toInt() != brightness) {
            binding.brightnessSlider.value = brightness.toFloat()
        }
        if (binding.saturationSlider.value.toInt() != saturation) {
            binding.saturationSlider.value = saturation.toFloat()
        }
        binding.brightnessLabel.text = getString(R.string.label_brightness, brightness)
        binding.saturationLabel.text = getString(R.string.label_saturation, saturation)
        binding.columnsButton.setText(
            if (state.columnCount == 2) R.string.action_columns_2 else R.string.action_columns_3
        )
        (binding.photosRecyclerView.layoutManager as? GridLayoutManager)?.spanCount =
            state.columnCount
    }

    // Solicita confirmación antes de vaciar el collage completo.
    private fun confirmClearPhotos() {
        if (viewModel.uiState.value.photos.isEmpty()) {
            showMessage(UiMessage(R.string.no_photos))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_clear_title)
            .setMessage(R.string.confirm_clear_message)
            .setPositiveButton(R.string.confirm_clear) { _, _ -> viewModel.clearPhotos() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // Compone las miniaturas actuales fuera del hilo principal y abre el selector para compartir.
    private fun shareCollage() {
        val snapshot = exportSnapshot() ?: return
        lifecycleScope.launch {
            try {
                val uri = withContext(Dispatchers.IO) {
                    collageExporter.createShareUri(snapshot.bitmaps, snapshot.columnCount)
                }
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newUri(contentResolver, getString(R.string.app_name), uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, getString(R.string.share_chooser_title)))
            } catch (exception: CancellationException) {
                throw exception
            } catch (error: OutOfMemoryError) {
                showMessage(UiMessage(R.string.share_error))
            } catch (exception: Exception) {
                showMessage(UiMessage(R.string.share_error))
            }
        }
    }

    // Renderiza y guarda la composición final en la galería del dispositivo.
    private fun saveCollage() {
        val snapshot = exportSnapshot() ?: return
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    collageExporter.saveToGallery(snapshot.bitmaps, snapshot.columnCount)
                }
                showMessage(UiMessage(R.string.save_success))
            } catch (exception: CancellationException) {
                throw exception
            } catch (error: OutOfMemoryError) {
                showMessage(UiMessage(R.string.save_error))
            } catch (exception: Exception) {
                showMessage(UiMessage(R.string.save_error))
            }
        }
    }

    // Captura una lista estable de bitmaps para que las ediciones posteriores no cambien la exportación.
    private fun exportSnapshot(): ExportSnapshot? {
        val state = viewModel.uiState.value
        if (state.photos.isEmpty()) {
            showMessage(UiMessage(R.string.no_photos))
            return null
        }
        return ExportSnapshot(state.photos.map { it.currentBitmap }, state.columnCount)
    }

    // Muestra los avisos localizados emitidos por el estado o por una operación de exportación.
    private fun showMessage(message: UiMessage) {
        val text = message.count?.let { getString(message.resourceId, it) }
            ?: getString(message.resourceId)
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    // Mantiene juntos los datos inmutables usados durante una exportación asíncrona.
    private data class ExportSnapshot(
        val bitmaps: List<Bitmap>,
        val columnCount: Int
    )

    private companion object {
        const val MAX_PICK_COUNT = 20
        const val INITIAL_COLUMN_COUNT = 2
        const val DEFAULT_SATURATION = 100
    }
}
