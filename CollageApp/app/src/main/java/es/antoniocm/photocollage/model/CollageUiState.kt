package es.antoniocm.photocollage.model

import android.net.Uri
import androidx.annotation.StringRes

// Clase para representar los datos que la pantalla necesita mostrar.
data class CollageUiState(
    val photos: List<CollagePhoto> = emptyList(),
    val selectedUri: Uri? = null,
    val columnCount: Int = 2,
    val isLoading: Boolean = false
)

// Clase para transportar un aviso localizado desde el ViewModel a la Activity.
data class UiMessage(
    @StringRes val resourceId: Int,
    val count: Int? = null
)
