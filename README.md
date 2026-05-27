# 🎵 Sleppify

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white&style=for-the-badge" alt="Android Badge" />
  <img src="https://img.shields.io/badge/Kotlin-Kotlin%20%2F%20Java-7F52FF?logo=kotlin&logoColor=white&style=for-the-badge" alt="Kotlin Badge" />
  <img src="https://img.shields.io/badge/Gradle-9.3.1-02303A?logo=gradle&logoColor=white&style=for-the-badge" alt="Gradle Badge" />
  <img src="https://img.shields.io/badge/Firebase-Auth%20%7C%20Firestore-FFCA28?logo=firebase&logoColor=black&style=for-the-badge" alt="Firebase Badge" />
</p>

**Sleppify** es una moderna y completa aplicación nativa de Android diseñada para ofrecer una excelente experiencia de reproducción musical, descarga de videos y gestión de playlists. Permite disfrutar de contenido en streaming y de manera local sin conexión a internet, optimizando el consumo de batería del dispositivo y facilitando la sincronización de tu biblioteca en la nube.

---

## 📸 Capturas de Pantalla

<details>
<summary><b>👀 Ver Capturas de la Interfaz</b></summary>
<br/>

### Lista de Playlists
![Lista de Playlists](lista_playlists.png)

### Detalles de la Playlist
![Detalles de la Playlist](detalles_playlist.png)

### Buscador de Música
![Buscador de Música](buscador_musica.png)

### Ecualizador Avanzado
![Módulo Equalizer](modulo_equalizer.png)

</details>

---

## ✨ Funcionalidades Principales

### 📥 Descarga y Reproducción de Videos y Música Offline
* **Descarga Completa de Contenido:** Permite descargar tus canciones y videos favoritos directamente en formato **MP4** en tu dispositivo. Puedes ver y escuchar tu contenido multimedia en cualquier momento y lugar sin consumir tus datos móviles ni requerir conexión.
* **Descargas Inteligentes y Reanudables:** El sistema de descargas trabaja en segundo plano. Si tu conexión a internet se interrumpe, la descarga no se reinicia desde cero, sino que continúa exactamente donde se quedó cuando recuperas la señal.
* **Filtros de Red:** Puedes configurar la aplicación para que realice descargas únicamente cuando estés conectado a una red Wi-Fi, protegiendo tu plan de datos.

### 🌐 Conexión Web a YouTube Music (Sesión con Cookies)
* **Acceso a tu Cuenta:** Cuenta con una pantalla de inicio de sesión que te permite conectar tu cuenta de Google de forma segura.
* **Soporte de Cookies de Sesión:** Extrae y utiliza de manera segura tus cookies de autenticación para que puedas acceder a tus playlists privadas de YouTube Music, mixes personalizados, recomendaciones basadas en tus gustos y flujos de audio de máxima calidad.

### 🔋 Reproducción Multimedia Eficiente y Ahorro de Batería
* **Música en Segundo Plano:** Disfruta de la reproducción continua de tus canciones mientras usas otras aplicaciones o con la pantalla apagada.
* **Detección de Video Estático:** Si estás reproduciendo un video musical descargado que solo muestra una carátula o imagen estática en pantalla, la aplicación detecta esto automáticamente y detiene la decodificación visual para ahorrar batería y recursos de tu dispositivo.

### 🔄 Reemplazo de Canciones en Caliente (Hot-Swapping)
* **Alternativas al Instante:** Si algún enlace o video musical deja de estar disponible o no se puede reproducir, puedes presionar la canción para buscar versiones alternativas (covers, directos, videos de letras o remixes) y reemplazarla al vuelo.
* **Sincronización Permanente:** Tu elección de reemplazo se guarda de forma permanente para esa lista de reproducción, y puedes restaurar la canción original en cualquier momento.

### 🔍 Lector de Códigos QR Integrado
* **Escaneo Instantáneo:** Usa la cámara de tu dispositivo para leer códigos QR y códigos de barras de manera rápida.
* **Controles Gestuales:** Soporta enfocar con un toque y pellizcar la pantalla para hacer zoom dinámico en la cámara, mostrando una animación de encuadre en el objetivo detectado.

### ☁️ Sincronización en la Nube
* **Tus Datos Seguros:** Guarda tus playlists creadas, tus canciones favoritas, tu historial y preferencias en la nube mediante Firebase. Tu biblioteca se sincroniza automáticamente para que nunca la pierdas y puedas acceder a ella desde cualquier dispositivo en tiempo real.

### 🎛️ Ajustes de Audio y Ecualización (Extra)
* **Personalización del Sonido:** Ajusta frecuencias específicas usando un ecualizador interactivo, en el cual puedes dibujar las curvas de sonido a mano alzada.
* **Perfiles Automáticos:** Guarda ajustes automáticos que se activan según estés escuchando por el altavoz de tu móvil o por tus auriculares Bluetooth.

---

## 🛠️ Estructura del Código

El proyecto sigue una arquitectura organizada y separada para facilitar el desarrollo y mantenimiento del código fuente:

```
app/src/main/java/com/example/sleppify/
├── 📱 Pantallas e Interfaz de Usuario (Fragments & Activities)
│   ├── MainActivity.kt                   # Actividad principal que organiza la navegación
│   ├── YouTubeMusicWebSessionActivity.kt # Pantalla de inicio de sesión para YouTube Music
│   ├── PrincipalFragment.java             # Pantalla de inicio con atajos, mixes y covers recomendados
│   ├── SearchFragment.kt                 # Interfaz de búsqueda de música y videos
│   ├── PlaylistDetailFragment.java       # Detalles de listas de reproducción y gestor de tracks
│   ├── SongPlayerFragment.java           # Pantalla del reproductor multimedia con controles de gestos
│   ├── ScannerFragment.kt                # Escáner de códigos QR y de barras
│   └── SettingsFragment.kt               # Ajustes generales (calidad, red y descargas)
│
├── 🧠 Motor de Reproducción y Servicios
│   ├── PlaybackKeepAliveService.kt       # Servicio que mantiene la reproducción activa en segundo plano
│   ├── AudioEffectsService.kt            # Control del ecualizador y efectos de sonido
│   ├── VideoSurfaceRouter.java           # Administrador visual de las pantallas de video
│   ├── ExoMediaPlayer.kt                 # Controlador del reproductor de media
│   └── ExoPlayerManager.kt               # Ciclo de vida y estados del reproductor
│
├── ☁️ Almacenamiento y Sincronización
│   ├── CloudSyncManager.kt               # Sincronización de datos con Firebase Firestore
│   ├── OfflineAudioStore.kt              # Gestor y validador de los archivos descargados
│   ├── CustomPlaylistsStore.kt           # Almacenamiento local de listas de reproducción personalizadas
│   ├── FavoritesPlaylistStore.kt         # Almacenamiento de canciones marcadas como favoritas
│   ├── PlaylistOverrideStore.kt          # Almacenamiento de canciones reemplazadas
│   └── PlaybackHistoryStore.kt           # Historial de canciones reproducidas
│
└── 🧩 Extractores de Datos y Componentes
    ├── InnertubeResolver.kt              # Extractor de enlaces de streaming de YouTube
    ├── SleppifyDownloaderResolver.kt     # Resolutor de descargas con soporte para pausar y reanudar
    ├── TrackReplacementSheet.kt          # Ventana para buscar y reemplazar canciones caídas
    └── EqCurveEditorView.kt              # Vista para dibujar curvas de ecualización en pantalla
```

---

## 🚀 Requisitos e Instalación

### Prerrequisitos
* **Java Development Kit (JDK):** Versión 11 para compatibilidad, soportando JDK Toolchain 17.
* **Android SDK:** Compile SDK **35**, Target SDK **35**, Min SDK **24**.
* **Android Studio:** Ladybug o superior.
* **Firebase:** Configurar un proyecto con Firestore y Auth habilitado en Google.

### Configuración Local

1. **Clonación del Repositorio:**
   ```bash
   git clone https://github.com/juliots21/Sleppify.git
   cd Sleppify
   ```

2. **Añadir Firebase:**
   * Genera el archivo `google-services.json` desde tu consola de Firebase.
   * Cópialo en la raíz del módulo de la aplicación:
     ```bash
     Sleppify/app/google-services.json
     ```

3. **Variables en `gradle.properties`:**
   * Abre o crea el archivo `gradle.properties` en la raíz del proyecto y define las siguientes claves con tus valores reales:
     ```properties
     # Clave de API de YouTube Data v3
     YOUTUBE_DATA_API_KEY=tu_api_key_real_aqui
     
     # URL del servicio de descarga de la aplicación (ej. en Render)
     SLEPPIFY_DOWNLOAD_SERVICE_URL=https://tu-servicio-de-descargas.com
     ```

### Construcción y Despliegue en Dispositivo (Windows)

Ejecuta los siguientes comandos desde tu terminal para compilar e instalar la aplicación en modo debug en un dispositivo o emulador activo:

```powershell
# Compilar la aplicación en modo Debug
.\gradlew.bat assembleDebug

# Compilar e instalar directamente en el dispositivo conectado
.\gradlew.bat installDebug
```

---

## 🔒 Seguridad y Pautas de Desarrollo

* ⚠️ **Gestión de Secretos:** Nunca subas el archivo `google-services.json` ni expongas tu clave de API `YOUTUBE_DATA_API_KEY` en commits públicos. Ambos están incluidos en el archivo `.gitignore` para evitar filtraciones.
* 🌿 **Políticas de Ramas:** La rama de desarrollo principal es `sleppy`. Dirige tus pull requests directamente a ella.
* 🎛️ **Consistencia de Módulos:** Todas las actividades e interfaces de usuario comparten el mismo módulo. Al modificar contratos, Intents o bases de datos locales, asegúrate de actualizar las referencias cruzadas para evitar fallos de ejecución.

---

Creado con ❤️ por [juliots04](https://github.com/juliots04)