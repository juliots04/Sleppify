<?php
require_once __DIR__ . '/config.php';

$sessionPath = __DIR__ . '/sessions';
if (!is_dir($sessionPath)) {
    @mkdir($sessionPath, 0755, true);
}
if (is_dir($sessionPath) && is_writable($sessionPath)) {
    session_save_path($sessionPath);
}

session_name('SLEPPIFYSESSID');
// Detección de HTTPS considerando el proxy de alwaysdata (termina el TLS y reenvía por http,
// así que $_SERVER['HTTPS'] puede venir vacío aunque el usuario esté en https).
$isHttps = (!empty($_SERVER['HTTPS']) && strtolower($_SERVER['HTTPS']) !== 'off')
    || (($_SERVER['HTTP_X_FORWARDED_PROTO'] ?? '') === 'https')
    || ((int) ($_SERVER['SERVER_PORT'] ?? 0) === 443);

session_set_cookie_params([
    'httponly' => true,
    'samesite' => 'Lax',
    'secure' => $isHttps,
]);
session_start();

function is_logged_in(): bool
{
    return !empty($_SESSION['user_id']);
}

function require_login(): void
{
    if (!is_logged_in()) {
        header('Location: index.php');
        exit;
    }
}

function require_login_json(): void
{
    if (!is_logged_in()) {
        http_response_code(401);
        header('Content-Type: application/json');
        echo json_encode(['ok' => false, 'error' => 'Sesión expirada. Vuelve a entrar.']);
        exit;
    }
}

/**
 * El usuario se crea/actualiza con set_password.php, que usa password_hash() de PHP
 * (bcrypt) — la MISMA función que password_verify() aquí, así que login y creación nunca
 * pueden desincronizarse. El branch SHA-256+sal es compatibilidad con hashes viejos, por
 * si alguna vez se insertó uno a mano directo en la base.
 */
function verify_panel_password(string $password, string $storedHash): bool
{
    if ($storedHash === '') return false;
    if ($storedHash[0] === '$') {
        return password_verify($password, $storedHash);
    }
    return hash_equals($storedHash, hash('sha256', AUTH_SALT . $password));
}

function csrf_token(): string
{
    if (empty($_SESSION['csrf'])) {
        $_SESSION['csrf'] = bin2hex(random_bytes(32));
    }
    return $_SESSION['csrf'];
}

function csrf_ok(?string $token): bool
{
    return !empty($_SESSION['csrf']) && is_string($token) && hash_equals($_SESSION['csrf'], $token);
}
