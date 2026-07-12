<?php
require_once __DIR__ . '/auth.php';

$_SESSION = [];

// Borrar también la cookie de sesión del navegador (session_destroy no la limpia sola).
if (ini_get('session.use_cookies')) {
    $p = session_get_cookie_params();
    setcookie(session_name(), '', time() - 42000, $p['path'], $p['domain'], $p['secure'], $p['httponly']);
}

session_destroy();
header('Location: index.php');
