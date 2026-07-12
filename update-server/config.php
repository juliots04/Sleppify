<?php
// ================= CONFIGURACIÓN — edita esto con tus datos de alwaysdata =================
// Los datos de MySQL están en tu panel de alwaysdata > Bases de datos > MySQL.
define('DB_HOST', 'mysql-sleppifymanagerupdate.alwaysdata.net');
define('DB_NAME', 'sleppifymanagerupdate_user');
define('DB_USER', 'sleppifymanagerupdate');
define('DB_PASS', 'Supern0va123?');

// Sal para el hash de la contraseña del panel. DEBE ser idéntica a la que usa database.sql
// (viene igual por defecto — si cambias una, cambia la otra y re-importa el SQL).
define('AUTH_SALT', 'sleppify::panel::v1::');

// Rutas internas (no hace falta tocar).
define('APK_DIR', __DIR__ . '/apk');
define('VERSION_JSON', __DIR__ . '/version.json');

function db(): PDO
{
    static $pdo = null;
    if ($pdo === null) {
        $pdo = new PDO(
            'mysql:host=' . DB_HOST . ';dbname=' . DB_NAME . ';charset=utf8mb4',
            DB_USER,
            DB_PASS,
            [
                PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
                PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
            ]
        );
    }
    return $pdo;
}

function current_manifest(): array
{
    $defaults = ['versionName' => '1.0', 'versionCode' => 1, 'apk' => '', 'size' => 0, 'notes' => '', 'publishedAt' => ''];
    if (!is_file(VERSION_JSON)) return $defaults;
    $data = json_decode((string) file_get_contents(VERSION_JSON), true);
    return is_array($data) ? array_merge($defaults, $data) : $defaults;
}

/** Última versión publicada: la base manda (persistente); version.json es el espejo público. */
function latest_release(): array
{
    try {
        $row = db()->query('SELECT version_name, version_code, notes, apk_path, apk_size, published_at
                            FROM releases ORDER BY version_code DESC LIMIT 1')->fetch();
        if ($row) {
            return [
                'versionName' => $row['version_name'],
                'versionCode' => (int) $row['version_code'],
                'apk' => $row['apk_path'],
                'size' => (int) $row['apk_size'],
                'notes' => (string) $row['notes'],
                'publishedAt' => (string) $row['published_at'],
            ];
        }
    } catch (Throwable $e) {
        // sin base → cae al manifiesto
    }
    return current_manifest();
}
