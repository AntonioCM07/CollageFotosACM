package es.antoniocm.photocollage.viewmodel

import android.app.Application
import android.net.Uri
import androidx.annotation.MainThread
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import es.antoniocm.photocollage.R
import es.antoniocm.photocollage.data.PhotoRepository
import es.antoniocm.photocollage.model.CollagePhoto
import es.antoniocm.photocollage.model.CollageUiState
import es.antoniocm.photocollage.model.PhotoFilter
import es.antoniocm.photocollage.model.UiMessage
import es.antoniocm.photocollage.utils.BitmapProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// Clase para gestionar la selección, las ediciones y el estado del collage.
class CollageViewModel(application: Application) : AndroidViewModel(application) {

    private val photoRepository = PhotoRepository(application)
    private val loadMutex = Mutex()
    private val loadingUris = mutableSetOf<Uri>()
    private val renderJobs = mutableMapOf<Uri, Job>()
    private val renderRevisions = mutableMapOf<Uri, Int>()

    private val _uiState = MutableStateFlow(CollageUiState())
    val uiState = _uiState.asStateFlow()

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    // Carga solo Uri nuevas y evita duplicados incluso mientras otra carga está en curso.
    @MainThread
    fun addPhotos(uris: List<Uri>) {
        val knownUris = _uiState.value.photos.mapTo(mutableSetOf()) { it.uri }
        knownUris.addAll(loadingUris)
        val newUris = uris.distinct().filterNot { it in knownUris }
        val duplicateCount = uris.size - newUris.size

        if (newUris.isEmpty()) {
            if (duplicateCount > 0) postMessage(R.string.duplicate_photos, duplicateCount)
            else postMessage(R.string.nothing_added)
            return
        }

        loadingUris.addAll(newUris)
        publishLoadingState()
        val loadRevision = loadRevision

        viewModelScope.launch {
            try {
                val result = loadMutex.withLock {
                    val bytesInUse = _uiState.value.photos.sumOf {
                        it.originalBitmap.allocationByteCount.toLong()
                    }
                    val loaded = withContext(Dispatchers.IO) {
                        photoRepository.loadPhotos(newUris, bytesInUse)
                    }
                    if (loadRevision != this@CollageViewModel.loadRevision) {
                        loaded.photos.forEach { it.originalBitmap.recycle() }
                        null
                    } else {
                        _uiState.value = _uiState.value.copy(
                            photos = _uiState.value.photos + loaded.photos
                        )
                        loaded
                    }
                }
                if (result != null) {
                    if (result.photos.isNotEmpty()) {
                        postMessage(R.string.photos_added, result.photos.size)
                    }
                    if (result.skippedForMemory > 0) {
                        postMessage(R.string.photos_skipped_memory, result.skippedForMemory)
                    }
                    if (result.failedLoads > 0) {
                        postMessage(R.string.photo_load_error, result.failedLoads)
                    }
                    if (duplicateCount > 0) {
                        postMessage(R.string.duplicate_photos, duplicateCount)
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                postMessage(R.string.photo_load_error, newUris.size)
            } finally {
                loadingUris.removeAll(newUris.toSet())
                publishLoadingState()
            }
        }
    }

    // Actualiza la URI activa para que solo una foto reciba operaciones individuales.
    @MainThread
    fun selectPhoto(uri: Uri) {
        if (_uiState.value.photos.any { it.uri == uri }) {
            _uiState.value = _uiState.value.copy(selectedUri = uri)
        }
    }

    // Cambia el filtro de la foto activa y vuelve a procesarla desde el original.
    @MainThread
    fun setFilter(filter: PhotoFilter) {
        editSelectedPhoto { it.copy(filter = filter) }
    }

    // Ajusta el brillo de la foto activa dentro del intervalo permitido.
    @MainThread
    fun setBrightness(value: Int) {
        editSelectedPhoto { it.copy(brightness = value.coerceIn(-100, 100)) }
    }

    // Ajusta la saturación de la foto activa; 100 representa el color original.
    @MainThread
    fun setSaturation(value: Int) {
        editSelectedPhoto { it.copy(saturation = value.coerceIn(0, 200)) }
    }

    // Suma un giro horario de 90 grados a la foto activa.
    @MainThread
    fun rotateSelectedPhoto() {
        editSelectedPhoto { it.copy(rotationDegrees = (it.rotationDegrees + 90) % 360) }
    }

    // Restaura los parámetros y vuelve a mostrar el bitmap original de la foto activa.
    @MainThread
    fun resetSelectedPhoto() {
        val photo = selectedPhotoOrNotify() ?: return
        invalidateRender(photo.uri)
        replacePhoto(
            photo.copy(
                currentBitmap = photo.originalBitmap,
                filter = PhotoFilter.NONE,
                rotationDegrees = 0,
                brightness = 0,
                saturation = DEFAULT_SATURATION
            )
        )
    }

    // Elimina solo la foto activa y retira su selección.
    @MainThread
    fun deleteSelectedPhoto() {
        val photo = selectedPhotoOrNotify() ?: return
        invalidateRender(photo.uri)
        _uiState.value = _uiState.value.copy(
            photos = _uiState.value.photos.filterNot { it.uri == photo.uri },
            selectedUri = null
        )
        postMessage(R.string.photo_deleted)
    }

    // Vacía la lista y descarta los resultados de cargas iniciadas antes de esta acción.
    @MainThread
    fun clearPhotos() {
        loadRevision++
        loadingUris.clear()
        renderJobs.values.forEach(Job::cancel)
        renderJobs.clear()
        renderRevisions.clear()
        _uiState.value = _uiState.value.copy(
            photos = emptyList(),
            selectedUri = null,
            isLoading = false
        )
        postMessage(R.string.collage_cleared)
    }

    // Alterna la cuadrícula entre dos y tres columnas.
    @MainThread
    fun toggleColumns() {
        val columns = if (_uiState.value.columnCount == 2) 3 else 2
        _uiState.value = _uiState.value.copy(columnCount = columns)
    }

    // Aplica un cambio a la selección y programa una representación desde el original.
    private fun editSelectedPhoto(edit: (CollagePhoto) -> CollagePhoto) {
        val photo = selectedPhotoOrNotify() ?: return
        val editedPhoto = edit(photo)
        replacePhoto(editedPhoto)
        renderPhoto(editedPhoto)
    }

    // Procesa una instantánea de parámetros y descarta renders obsoletos.
    private fun renderPhoto(photo: CollagePhoto) {
        val revision = (renderRevisions[photo.uri] ?: 0) + 1
        renderRevisions[photo.uri] = revision
        renderJobs[photo.uri]?.cancel()
        val filter = photo.filter
        val rotation = photo.rotationDegrees
        val brightness = photo.brightness
        val saturation = photo.saturation

        renderJobs[photo.uri] = viewModelScope.launch {
            try {
                val rendered = withContext(Dispatchers.Default) {
                    BitmapProcessor.render(
                        photo.originalBitmap,
                        filter,
                        rotation,
                        brightness,
                        saturation
                    )
                }
                val latestPhoto = _uiState.value.photos.firstOrNull { it.uri == photo.uri }
                if (latestPhoto == null || renderRevisions[photo.uri] != revision) {
                    if (rendered !== photo.originalBitmap) rendered.recycle()
                    return@launch
                }
                replacePhoto(latestPhoto.copy(currentBitmap = rendered))
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                postMessage(R.string.edit_error)
            } catch (error: OutOfMemoryError) {
                postMessage(R.string.edit_error)
            } finally {
                if (renderRevisions[photo.uri] == revision) renderJobs.remove(photo.uri)
            }
        }
    }

    // Invalida y cancela cualquier procesamiento pendiente para una foto concreta.
    private fun invalidateRender(uri: Uri) {
        renderRevisions[uri] = (renderRevisions[uri] ?: 0) + 1
        renderJobs.remove(uri)?.cancel()
    }

    // Devuelve la foto seleccionada o informa por qué no se puede editar.
    private fun selectedPhotoOrNotify(): CollagePhoto? {
        return _uiState.value.photos.firstOrNull { it.uri == _uiState.value.selectedUri }
            ?: run {
                postMessage(R.string.no_selection)
                null
            }
    }

    // Reemplaza el modelo editado para que StateFlow detecte y publique el cambio.
    private fun replacePhoto(updatedPhoto: CollagePhoto) {
        _uiState.value = _uiState.value.copy(
            photos = _uiState.value.photos.map { photo ->
                if (photo.uri == updatedPhoto.uri) updatedPhoto else photo
            }
        )
    }

    // Refleja si queda alguna selección de imágenes pendiente de carga.
    private fun publishLoadingState() {
        _uiState.value = _uiState.value.copy(isLoading = loadingUris.isNotEmpty())
    }

    // Emite un aviso localizado que la Activity convierte en un mensaje visible.
    private fun postMessage(resourceId: Int, count: Int? = null) {
        _messages.tryEmit(UiMessage(resourceId, count))
    }

    private var loadRevision = 0

    private companion object {
        const val DEFAULT_SATURATION = 100
    }
}
