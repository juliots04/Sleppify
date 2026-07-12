# Sleppify Updates — panel de actualizaciones (alwaysdata)

Carpeta lista para subir al hosting. La app consulta `version.json` al abrir; el panel es
donde publicas versiones nuevas. Sin Firebase, sin notificaciones: todo va por el hosting.

## Instalación (una sola vez)

1. **Base MySQL**: en alwaysdata → *Bases de datos* → *MySQL*, crea una base
   (ej. `TUCUENTA_sleppify`).
2. **`config.php`**: pon host/base/usuario/contraseña de MySQL (los de alwaysdata).
3. **`database.sql`**: ábrelo en phpMyAdmin (pestaña **Importar**) sobre esa base — crea
   las tablas `panel_users` y `releases`. Todavía no crea tu usuario del panel.
4. **Sube esta carpeta** por FTP/WebDAV, por ejemplo a `www/updates/`.
5. **`set_password.php`**: edita la constante `RESET_KEY` dentro del archivo por
   cualquier texto largo tuyo, sube el archivo, y ábrelo en el navegador
   (`https://tu-dominio/updates/set_password.php`). Pon esa clave + tu usuario +
   contraseña → crea tu login con `password_hash()` nativo de PHP (bcrypt), la misma
   función que usa `index.php` para verificar — así el hash **nunca puede desajustarse**
   entre MySQL y PHP.
6. **Borra `set_password.php` del hosting** (puedes volver a subirlo más tarde si
   necesitas resetear la contraseña).
7. Entra en `https://tu-dominio/updates/` con tu usuario. Listo.

> Si ya habías importado una versión anterior de `database.sql` que insertaba un hash
> SHA-256 calculado en MySQL y el login no aceptaba tus credenciales, ese era el problema:
> algunos hostings compilan MySQL sin soporte para `SHA2()`, que entonces devuelve `NULL`
> en silencio. `set_password.php` resuelve eso de raíz.

> Cuando tengas el link definitivo, hay que ponerlo en la app:
> `AppUpdateManager.kt` → `UPDATE_BASE_URL` (debe terminar en `/`).

## Publicar una versión

En el panel pones la **versión** (ej. `1.0.1`), el **version code** (entero que siempre
sube), escribes los **detalles** — la vista previa de la derecha es una réplica exacta de
la pantalla *Actualizar* de la app (mismos colores, tamaños y viñetas) y hasta simula la
descarga con % si tocas su botón — eliges el APK y presionas **Publicar versión**.

Qué hace el hosting al recibirlo (`publish.php`):
1. Valida sesión + token CSRF.
2. Valida el formato de versión y que el code sea mayor que el publicado (mira la base primero).
3. Verifica que el archivo sea un APK real (magic bytes de ZIP) y lo guarda en `apk/`.
4. Escribe `version.json` de forma **atómica** (tmp + rename): la app nunca lee un JSON a medias.
5. Registra la versión en la tabla `releases` (historial persistente).

`.user.ini` sube el límite de subida de PHP a 300 MB para que el APK entre sin problemas.

## Cómo se entera el teléfono (flujo completo)

1. Al **abrir la app**, MainActivity consulta `version.json` en silencio (1.4 s después de
   arrancar, una vez por sesión).
2. Si `versionCode` remoto > instalado → aparece la **ventana emergente** con la versión y
   los detalles (mismo diseño AMOLED de la app).
3. **"Actualizar"** en la ventana lleva directo a *Configuración → Actualizar* **con la
   descarga ya corriendo** (barra + % en número). **"Más tarde"** la cierra y no vuelve a
   molestar en esa sesión.
4. Al llegar a 100% se lanza solo el instalador del sistema (Android siempre pide el toque
   final de "Instalar" — eso no se puede automatizar por seguridad del sistema).
5. También se puede buscar manualmente desde *Configuración → Actualizar*.

> Importante: el `versionCode`/`versionName` del APK que compilas
> (`app/build.gradle.kts`) debe coincidir con lo que publicas en el panel.

## Archivos

| Archivo            | Qué hace |
|--------------------|----------|
| `database.sql`     | Esquema (tablas), importar una vez en phpMyAdmin |
| `set_password.php` | Crea/resetea tu login (bcrypt nativo de PHP) — úsalo y bórralo |
| `config.php`       | Credenciales MySQL + sal de compatibilidad |
| `auth.php`     | Sesiones, verificación de contraseña, CSRF |
| `index.php`    | Login |
| `panel.php`    | Panel: publicar + vista previa 1:1 + historial (MySQL) |
| `publish.php`  | Recibe el APK, actualiza `version.json`, registra en la base |
| `version.json` | Manifiesto público que lee la app |
| `apk/`         | APKs publicados |
| `.user.ini`    | Límite de subida de PHP (300 MB) |

## Seguridad

- Login contra MySQL con hash + sal (`hash_equals`, sin timing leaks), sesiones HttpOnly,
  token CSRF y freno progresivo contra fuerza bruta.
- `publish.php` exige sesión + CSRF, valida versión/code/magic-bytes.
- Sin `install.php` ni endpoints abiertos: lo único público es `version.json` y los APK.
