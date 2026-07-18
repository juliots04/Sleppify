# Sleppify

Aplicación nativa de Android para reproducir y descargar música, con sesión web a YouTube Music, sincronización en la nube y reproducción offline.

## Características

- Descarga de canciones
- Sesión web a YouTube Music (playlists privadas, mixes, streams de alta calidad)
- Reproducción en segundo plano con ahorro de batería
- Reemplazo de canciones, con sincronización permanente por playlist
- Sincronización de playlists, favoritos e historial vía Firebase
- Ecualizador sin distorsion y limitador

## Stack técnico

- Kotlin / Java, arquitectura basada en Fragments
- ExoPlayer para reproducción de audio y video
- Firebase (Auth + Firestore)
- CameraX para el escáner QR

## Requisitos

- JDK 11 (Toolchain 17)
- Android SDK: compileSdk 35, targetSdk 35, minSdk 24
- Android Studio Ladybug o superior
- Proyecto de Firebase con Auth y Firestore habilitados

## Instalación

1. Clonar el repositorio:
   ```bash
   git clone https://github.com/juliots21/Sleppify.git
   cd Sleppify
   ```

2. Colocar `google-services.json` en `app/`.

3. Compilar e instalar:
   ```bash
   ./gradlew.bat installDebug
   ```

## Licencia

Proyecto de uso Libre [juliots04](https://github.com/juliots04).
