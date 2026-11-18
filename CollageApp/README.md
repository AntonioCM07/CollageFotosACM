# PhotoCollage

Aplicación Android nativa escrita en Kotlin para crear collages a partir de fotos elegidas con el Photo Picker del sistema. La interfaz está implementada en XML con ViewBinding y componentes Material.

## Funcionalidades

- Selección múltiple de imágenes y detección de duplicados por `Uri`.
- Cuadrícula seleccionable con distribución de dos o tres columnas.
- Edición no destructiva de la foto activa: blanco y negro, sepia, brillo, saturación y rotación horaria.
- Restauración de los parámetros y la imagen original.
- Eliminación individual y vaciado del collage con confirmación.
- Composición del collage con recorte centrado y exportación PNG para compartir.
- Guardado del resultado final como JPEG en la galería con MediaStore.
- Muestreo de imágenes de entrada y límite de memoria para evitar decodificaciones excesivas.

## Organización del código

- `MainActivity`: enlaza controles, observa el estado y solicita compartir o guardar.
- `viewmodel/CollageViewModel`: gestiona selección, edición y estado durante cambios de configuración.
- `data/PhotoRepository`: carga imágenes con un límite de memoria.
- `utils/BitmapProcessor`: aplica los efectos y compone la cuadrícula.
- `export/CollageExporter`: guarda la composición o crea el archivo compartible.
- `adapter/PhotoGridAdapter` y `model/`: muestran las fotos y definen sus datos.

## Requisitos

- Android Studio actualizado con Android SDK 35.
- JDK 17 o superior (requisito de Android Gradle Plugin 8.7).
- Dispositivo o emulador Android 10 (API 29) o superior.

## Ejecución

1. Abre la carpeta `CollageApp` desde Android Studio.
2. Espera a que Gradle sincronice las dependencias.
3. Selecciona un dispositivo/emulador con API 29 o superior.
4. Ejecuta la configuración `app` con **Run**.

También puedes compilar desde Windows con `gradlew.bat :app:assembleDebug` (o desde macOS/Linux con `./gradlew :app:assembleDebug`).

