<?php
require_once __DIR__ . '/auth.php';

if (is_logged_in()) {
    header('Location: panel.php');
    exit;
}

$error = '';

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $user = trim($_POST['username'] ?? '');
    $pass = $_POST['password'] ?? '';

    // Freno anti fuerza bruta. Delay FIJO en cada intento (aunque tiren la cookie) + progresivo
    // por sesión. Con contraseña fuerte alcanza para un panel personal.
    usleep(400000);
    $fails = (int) ($_SESSION['login_fails'] ?? 0);
    if ($fails > 0) {
        usleep(min($fails, 6) * 500000);
    }

    try {
        $row = null;
        if ($user !== '') {
            $stmt = db()->prepare('SELECT id, username, password_hash FROM panel_users WHERE username = ?');
            $stmt->execute([$user]);
            $row = $stmt->fetch();
        }

        if ($row && verify_panel_password($pass, $row['password_hash'])) {
            // Nuevo id de sesión al autenticar = evita fijación de sesión.
            session_regenerate_id(true);
            $_SESSION['user_id'] = (int) $row['id'];
            $_SESSION['username'] = $row['username'];
            $_SESSION['login_fails'] = 0;
            session_write_close();
            header('Location: panel.php');
            exit;
        }

        $_SESSION['login_fails'] = $fails + 1;
        $error = 'Usuario o contraseña incorrectos.';
    } catch (Throwable $e) {
        $error = 'No se pudo conectar con la base de datos. Revisa config.php.';
    }
}
?>
<!doctype html>
<html lang="es">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Login — Sleppify Updates</title>
<link rel="stylesheet" href="style.css">
</head>
<body>
<div class="auth-wrap">
    <div class="card auth-card">
        <div class="brand">Sleppify <span>Updates</span></div>
        <h2>Entrar</h2>
        <p class="muted small">Panel de actualizaciones · acceso con tu usuario de la base</p>
        <?php if ($error): ?><div class="error"><?= htmlspecialchars($error) ?></div><?php endif; ?>
        <form method="post" autocomplete="off">
            <label>Usuario
                <input type="text" name="username" required autofocus>
            </label>
            <label>Contraseña
                <input type="password" name="password" required>
            </label>
            <button class="btn btn-primary" type="submit">Entrar</button>
        </form>
    </div>
</div>
</body>
</html>
