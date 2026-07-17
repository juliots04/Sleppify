# Sleppify Updates — panel (Astro SSR + MySQL)

Panel autohospedado para publicar las actualizaciones de la app. La app consulta
`GET /version.json` y, si el `versionCode` remoto supera al instalado, descarga el APK y lanza el
instalador. Reescrito de PHP a **Astro (SSR con Node)**.

## Qué trae de nuevo

- **Login que no se cae**: sesión _stateless_ en una cookie firmada (HMAC), sin ficheros de sesión
  en disco — eso era la causa del “carga pero se refresca solo” en alwaysdata.
- **Usuario admin automático**: se crea solo en el primer login si la tabla de usuarios está vacía
  (`ADMIN_USER`/`ADMIN_PASS`, por defecto `admin` / `Supern0va123?`). No hay que precomputar hashes.
- **Panel mejorado**: diseño oscuro con animaciones, vista previa 1:1 del popup de la app en vivo,
  validaciones de versión/código/novedades, barra de subida real.
- **Título del popup editable** desde el panel (el “🔥 NUEVA VERSIÓN DISPONIBLE”).
- **Opcional vs obligatoria**: si la marcas opcional, la app muestra un botón **“Más tarde”**; si es
  obligatoria, la única salida es actualizar.

## Estructura

```
src/
  middleware.ts            guard de sesión (/panel exige login)
  lib/{config,db,auth}.ts  configuración, MySQL (mysql2), scrypt + cookie firmada
  pages/
    index.astro            login
    panel.astro            panel (publicar + preview + historial + ajustes)
    logout.ts
    version.json.ts        manifiesto público que lee la app
    api/login.ts           valida credenciales, emite cookie
    api/publish.ts         sube APK + registra versión (validaciones + magic bytes)
    apk/[...file].ts       sirve los APKs de apk/
apk/                       APKs subidos (persisten en el hosting)
database.sql               esquema
.env / .env.example        configuración (DB, SESSION_SECRET, admin)
```

## Puesta en marcha (local)

```bash
cp .env.example .env      # y edita SESSION_SECRET
npm install
npm run dev               # http://localhost:4321
```

## Despliegue en alwaysdata (sitio Node)

> ⚠️ **IMPORTANTE — no compiles en el servidor.** El SSH de alwaysdata tiene poca RAM y mata
> (`Killed`) el `npm install`/`npm run build` completo (vite + esbuild + rollup + sharp). La regla
> es: **compila en tu PC** y en el servidor instala SOLO lo de runtime (ligero, sin binarios
> nativos). Verificado: el servidor corre sin esbuild/rollup/vite/sharp.

1. **Base de datos**: en phpMyAdmin importa `database.sql` (crea las tablas). No crees el usuario a
   mano — se siembra solo en el primer login.

2. **En tu PC** (una vez):
   ```bash
   npm install
   npm run build          # genera dist/  (esto es lo pesado; hazlo aquí, no en el server)
   ```

3. **Sube a `~/www/`** por SFTP/rsync (FileZilla/WinSCP sirven): `dist/`, `package.json`,
   `package-lock.json`, `astro.config.mjs`, `database.sql`, `.env` y la carpeta `apk/`.
   (NO subas `node_modules/` en este paso — se instala en el siguiente, ligero.)

4. **En el servidor (SSH), instala SOLO runtime** (sin nativos → no hace OOM):
   ```bash
   cd ~/www
   npm install --omit=dev --no-optional --no-audit --no-fund --maxsockets 1
   ```
   Son ~270 paquetes / ~126 MB de JS, sin descargar esbuild/rollup/sharp. Tarda segundos.

5. **Configura el sitio** (Web > Sitios > Añadir un sitio):
   - Tipo: **Node.js**
   - Comando: `node ./dist/server/entry.mjs`
   - Directorio de trabajo: `~/www`
   - Variables de entorno: copia las de `.env` (sobre todo `SESSION_SECRET`, `DB_*`). alwaysdata
     inyecta `PORT`/`HOST`; el adaptador Node standalone los respeta.

6. Apunta el dominio `sleppifymanagerupdate.alwaysdata.net` a ese sitio. La app ya pide
   `https://sleppifymanagerupdate.alwaysdata.net/version.json`.

> **Plan B si el paso 4 aún hiciera OOM:** no instales nada en el servidor — sube también tu
> `node_modules/` local junto con `dist/`. Funciona en Linux porque el runtime nunca carga los
> binarios por-plataforma de Windows (esbuild/rollup/sharp son solo de compilación, verificado).
> Luego solo `node ./dist/server/entry.mjs`.

> Cada vez que publiques cambios del panel: repite pasos 2–3 (recompila en tu PC y sube el `dist/`
> nuevo). El `node_modules` del servidor no cambia salvo que toques las dependencias.

## Publicar una versión

1. Entra al panel, rellena versión visible, version code (mayor que el actual), novedades y el
   título del popup, marca si es obligatoria u opcional, elige el `.apk` y pulsa **Publicar**.
2. La fila se guarda en `releases` y `GET /version.json` la refleja al instante. Los teléfonos la
   ven al abrir la app.

## Contrato de `version.json`

```json
{
  "versionName": "1.2.0",
  "versionCode": 5,
  "apk": "apk/sleppify-v1.2.0-5.apk",
  "size": 31510488,
  "notes": "- Novedad 1\n- Novedad 2",
  "dialogTitle": "🔥 NUEVA VERSIÓN DISPONIBLE",
  "mandatory": false,
  "publishedAt": "2026-07-18T01:00:00.000Z"
}
```

La app (`AppUpdateManager.kt`) lee `dialogTitle` (header del popup) y `mandatory` (si es `false`
muestra “Más tarde”). Los manifiestos antiguos sin esos campos se tratan como obligatorios con el
título por defecto.

La versión PHP anterior quedó archivada en `_legacy_php/` por si hace falta consultarla.
