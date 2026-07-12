<?php
require_once __DIR__ . '/auth.php';
require_login_json();

header('Content-Type: application/json');

function fail(string $msg, int $code = 400): void
{
    http_response_code($code);
    echo json_encode(['ok' => false, 'error' => $msg]);
    exit;
}

if ($_SERVER['REQUEST_METHOD'] !== 'POST') fail('Método inválido.', 405);

// Si el POST supera post_max_size, PHP vacía $_POST y $_FILES — sin esto el usuario vería
// un engañoso "Token CSRF inválido" en vez del verdadero problema (APK demasiado grande).
if (empty($_POST) && empty($_FILES) && (int) ($_SERVER['CONTENT_LENGTH'] ?? 0) > 0) {
    fail('El APK supera el límite de subida del servidor. Sube post_max_size/upload_max_filesize en .user.ini.');
}

if (!csrf_ok($_POST['csrf'] ?? null)) fail('Token CSRF inválido. Recarga la página.');

$versionName = trim($_POST['version_name'] ?? '');
$versionCode = (int) ($_POST['version_code'] ?? 0);
$notes = trim($_POST['notes'] ?? '');

if (!preg_match('/^[0-9]+(\.[0-9]+)*$/', $versionName)) fail('Versión inválida (usa el formato 1.0.1).');
if (strlen($versionName) > 32) fail('El nombre de versión es demasiado largo (máx. 32).');
if ($versionCode <= 0) fail('Version code inválido.');

// La base (persistente) manda; el manifiesto cubre el caso de base vacía/caída.
$currentCode = max((int) latest_release()['versionCode'], (int) current_manifest()['versionCode']);
if ($versionCode <= $currentCode) {
    fail('El version code debe ser mayor que el publicado (' . $currentCode . ').');
}

// --- APK subido ---
if (empty($_FILES['apk']) || $_FILES['apk']['error'] !== UPLOAD_ERR_OK) {
    $err = $_FILES['apk']['error'] ?? -1;
    if ($err === UPLOAD_ERR_INI_SIZE || $err === UPLOAD_ERR_FORM_SIZE) {
        fail('El APK supera el límite de subida de PHP. Revisa .user.ini (upload_max_filesize).');
    }
    fail('No llegó el archivo APK (error ' . $err . ').');
}

$tmp = $_FILES['apk']['tmp_name'];
$size = (int) $_FILES['apk']['size'];
if ($size < 1024) fail('El archivo APK parece vacío.');

// Un APK es un ZIP: valida los magic bytes "PK\x03\x04" para no publicar cualquier cosa.
$fh = fopen($tmp, 'rb');
$magic = $fh ? fread($fh, 4) : '';
if ($fh) fclose($fh);
if ($magic !== "PK\x03\x04") fail('Ese archivo no parece un APK válido.');

if (!is_dir(APK_DIR) && !mkdir(APK_DIR, 0755, true)) fail('No pude crear la carpeta apk/.', 500);

// El nombre incluye el version code (único y creciente) para NO sobrescribir APKs anteriores
// y que los enlaces de descarga del historial sigan válidos.
$apkName = 'sleppify-v' . $versionName . '-' . $versionCode . '.apk';
$apkRel = 'apk/' . $apkName;
$apkAbs = APK_DIR . '/' . $apkName;
if (!move_uploaded_file($tmp, $apkAbs)) fail('No pude guardar el APK en el servidor.', 500);

// Orden importante: primero la BASE (fuente persistente), y SOLO si graba escribimos
// version.json (lo que ve la app). Así el panel y la app nunca quedan desincronizados.
// ON DUPLICATE KEY hace el reintento idempotente si el mismo code se reintenta.
try {
    $stmt = db()->prepare(
        'INSERT INTO releases (version_name, version_code, notes, apk_path, apk_size)
         VALUES (?, ?, ?, ?, ?)
         ON DUPLICATE KEY UPDATE version_name = VALUES(version_name), notes = VALUES(notes),
                                 apk_path = VALUES(apk_path), apk_size = VALUES(apk_size)'
    );
    $stmt->execute([$versionName, $versionCode, $notes, $apkRel, $size]);
} catch (Throwable $e) {
    @unlink($apkAbs);
    fail('No se pudo registrar la versión en la base de datos.', 500);
}

// --- Manifiesto público: escribir a tmp + rename = la app nunca lee un JSON a medias ---
$newManifest = [
    'versionName' => $versionName,
    'versionCode' => $versionCode,
    'apk' => $apkRel,
    'size' => $size,
    'notes' => $notes,
    'publishedAt' => date('c'),
];
$tmpJson = VERSION_JSON . '.tmp';
if (file_put_contents($tmpJson, json_encode($newManifest, JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES)) === false
        || !rename($tmpJson, VERSION_JSON)) {
    fail('No pude escribir version.json (la versión quedó en la base; reintenta).', 500);
}

echo json_encode(['ok' => true, 'versionName' => $versionName, 'versionCode' => $versionCode]);
