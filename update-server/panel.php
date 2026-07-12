<?php
require_once __DIR__ . '/auth.php';
require_login();

// La base es la fuente persistente; version.json es el espejo público que lee la app.
$current = latest_release();
$releases = [];
try {
    $releases = db()->query('SELECT version_name, version_code, notes, apk_path, apk_size, published_at
                             FROM releases ORDER BY version_code DESC LIMIT 25')->fetchAll();
} catch (Throwable $e) {
}

// Sugerencias para el formulario: siguiente versión de parche y siguiente code.
$suggestName = $current['versionName'];
$parts = explode('.', $suggestName);
$last = (int) end($parts);
$parts[count($parts) - 1] = (string) ($last + 1);
$suggestName = implode('.', $parts);
$suggestCode = (int) $current['versionCode'] + 1;

function fmt_size(int $bytes): string
{
    if ($bytes >= 1048576) return number_format($bytes / 1048576, 1) . ' MB';
    if ($bytes >= 1024) return number_format($bytes / 1024, 0) . ' KB';
    return $bytes . ' B';
}
?>
<!doctype html>
<html lang="es">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Panel — Sleppify Updates</title>
<link rel="stylesheet" href="style.css">
</head>
<body>
<header class="topbar">
    <div class="brand">Sleppify <span>Updates</span></div>
    <div class="topbar-right">
        <span class="muted small"><?= htmlspecialchars($_SESSION['username'] ?? '') ?></span>
        <a class="btn btn-ghost" href="logout.php">Salir</a>
    </div>
</header>

<main class="panel-grid">
    <!-- Columna izquierda: publicar -->
    <section class="card">
        <div class="micro-label">Publicar</div>
        <h2>Nueva versión</h2>
        <p class="muted small">Publicada actualmente: <strong><?= htmlspecialchars($current['versionName']) ?></strong> (code <?= (int) $current['versionCode'] ?>)</p>

        <form id="publishForm">
            <input type="hidden" name="csrf" value="<?= htmlspecialchars(csrf_token()) ?>">
            <div class="row2">
                <label>Versión (visible)
                    <input type="text" name="version_name" id="inVersion" placeholder="<?= htmlspecialchars($suggestName) ?>" value="<?= htmlspecialchars($suggestName) ?>" pattern="[0-9]+(\.[0-9]+)*" required>
                </label>
                <label>Version code (entero, debe subir)
                    <input type="number" name="version_code" id="inCode" min="<?= (int) $current['versionCode'] + 1 ?>" value="<?= $suggestCode ?>" required>
                </label>
            </div>
            <label>Detalles de la versión (una línea por punto; empieza con "- " para viñeta)
                <textarea name="notes" id="inNotes" rows="6" placeholder="- Nuevo ecualizador solo-app&#10;- Corrección de errores"></textarea>
            </label>
            <label class="file-label">APK
                <input type="file" name="apk" id="inApk" accept=".apk" required>
                <span class="file-face" id="fileFace">Elegir archivo .apk…</span>
            </label>

            <div class="upload-zone" id="uploadZone" hidden>
                <div class="progress-track"><div class="progress-fill" id="upFill"></div></div>
                <div class="progress-label" id="upLabel">0%</div>
            </div>
            <div class="error" id="pubError" hidden></div>
            <div class="success" id="pubOk" hidden></div>

            <button class="btn btn-primary btn-big" type="submit" id="btnPublish">Publicar versión</button>
        </form>
    </section>

    <!-- Columna derecha: vista previa 1:1 de la pantalla "Actualizar" de la app -->
    <section class="card preview-col">
        <div class="micro-label">Vista previa</div>
        <h2>Así se verá en la app</h2>
        <div class="phone">
            <div class="phone-screen">
                <div class="app-hero">
                    <div class="app-logo">S</div>
                    <div class="app-name">Sleppify</div>
                    <div class="app-installed">Versión instalada <?= htmlspecialchars($current['versionName']) ?></div>
                </div>
                <div class="update-card">
                    <div class="update-head">
                        <div class="update-title">🔥 Nueva versión</div>
                        <div class="version-pill" id="pvVersion"><?= htmlspecialchars($suggestName) ?></div>
                    </div>
                    <div class="update-divider"></div>
                    <div class="update-notes-head">NOVEDADES</div>
                    <ul class="update-notes" id="pvNotes"><li class="muted">Escribe los detalles…</li></ul>
                    <div class="update-progress" id="pvProgressWrap" hidden>
                        <div class="progress-track"><div class="progress-fill" id="pvFill"></div></div>
                        <div class="progress-label" id="pvLabel">0%</div>
                    </div>
                </div>
                <button class="btn-accent" id="pvBtn" type="button">Actualizar</button>
            </div>
        </div>
        <p class="muted small center">Toca “Actualizar” en la vista previa para simular la descarga con %.</p>
    </section>

    <!-- Historial persistente (MySQL) -->
    <section class="card wide">
        <div class="micro-label">Registro</div>
        <h2>Historial de versiones</h2>
        <?php if (!$releases): ?>
            <p class="muted">Todavía no hay versiones registradas en la base.</p>
        <?php else: ?>
            <table class="history">
                <tr><th>Versión</th><th>Code</th><th>Detalles</th><th>APK</th><th>Tamaño</th><th>Fecha</th></tr>
                <?php foreach ($releases as $i => $r): ?>
                <tr>
                    <td><strong><?= htmlspecialchars($r['version_name']) ?></strong><?php if ($i === 0): ?><span class="badge-current">actual</span><?php endif; ?></td>
                    <td><?= (int) $r['version_code'] ?></td>
                    <td class="muted"><?= nl2br(htmlspecialchars(mb_strimwidth((string) $r['notes'], 0, 120, '…'))) ?></td>
                    <td><?php if ($r['apk_path']): ?><a href="<?= htmlspecialchars($r['apk_path']) ?>"><?= htmlspecialchars(basename($r['apk_path'])) ?></a><?php else: ?><span class="muted">—</span><?php endif; ?></td>
                    <td><?= fmt_size((int) $r['apk_size']) ?></td>
                    <td class="muted"><?= htmlspecialchars($r['published_at']) ?></td>
                </tr>
                <?php endforeach; ?>
            </table>
        <?php endif; ?>
    </section>
</main>

<script>
const $ = id => document.getElementById(id);

// ---------- Vista previa en vivo (idéntica al render de la app: "• " por línea) ----------
function renderNotes() {
    const raw = $('inNotes').value.trim();
    const ul = $('pvNotes');
    ul.innerHTML = '';
    if (!raw) {
        ul.innerHTML = '<li class="muted">Escribe los detalles…</li>';
        return;
    }
    raw.split('\n').map(l => l.trim()).filter(Boolean).forEach(line => {
        const li = document.createElement('li');
        li.textContent = line.replace(/^-\s*/, '');
        ul.appendChild(li);
    });
}
$('inNotes').addEventListener('input', renderNotes);
$('inVersion').addEventListener('input', () => {
    $('pvVersion').textContent = $('inVersion').value || '—';
});
renderNotes();

// Simulación del flujo real: Actualizar → barra con % → "Instalando…"
let pvTimer = null;
$('pvBtn').addEventListener('click', () => {
    clearInterval(pvTimer);
    const wrap = $('pvProgressWrap');
    wrap.hidden = false;
    $('pvBtn').disabled = true;
    $('pvBtn').textContent = 'Descargando…';
    let p = 0;
    pvTimer = setInterval(() => {
        p += 2 + Math.random() * 5;
        if (p >= 100) {
            p = 100;
            clearInterval(pvTimer);
            $('pvBtn').textContent = 'Instalando…';
            setTimeout(() => {
                $('pvBtn').textContent = 'Actualizar';
                $('pvBtn').disabled = false;
                wrap.hidden = true;
                $('pvFill').style.width = '0%';
                $('pvLabel').textContent = '0%';
            }, 1800);
        }
        $('pvFill').style.width = p + '%';
        $('pvLabel').textContent = Math.round(p) + '%';
    }, 90);
});

// ---------- Subida real con progreso ----------
$('inApk').addEventListener('change', () => {
    const f = $('inApk').files[0];
    $('fileFace').textContent = f ? (f.name + ' (' + (f.size / 1048576).toFixed(1) + ' MB)') : 'Elegir archivo .apk…';
});

$('publishForm').addEventListener('submit', e => {
    e.preventDefault();
    const errBox = $('pubError'), okBox = $('pubOk');
    errBox.hidden = okBox.hidden = true;

    const f = $('inApk').files[0];
    if (!f || !f.name.toLowerCase().endsWith('.apk')) {
        errBox.textContent = 'Elige un archivo .apk';
        errBox.hidden = false;
        return;
    }

    const fd = new FormData($('publishForm'));
    const xhr = new XMLHttpRequest();
    xhr.open('POST', 'publish.php');
    $('uploadZone').hidden = false;
    $('btnPublish').disabled = true;

    xhr.upload.onprogress = ev => {
        if (!ev.lengthComputable) return;
        const p = Math.round(ev.loaded / ev.total * 100);
        $('upFill').style.width = p + '%';
        $('upLabel').textContent = p + '%' + (p === 100 ? ' — procesando…' : '');
    };
    xhr.onload = () => {
        $('btnPublish').disabled = false;
        let res = null;
        try { res = JSON.parse(xhr.responseText); } catch (_) {}
        if (res && res.ok) {
            okBox.textContent = '✔ Versión ' + res.versionName + ' publicada. Los teléfonos la verán al abrir la app.';
            okBox.hidden = false;
            setTimeout(() => location.reload(), 1600);
        } else {
            errBox.textContent = (res && res.error) ? res.error : ('Error del servidor (HTTP ' + xhr.status + ')');
            errBox.hidden = false;
            $('uploadZone').hidden = true;
        }
    };
    xhr.onerror = () => {
        $('btnPublish').disabled = false;
        errBox.textContent = 'Fallo de red subiendo el APK.';
        errBox.hidden = false;
    };
    xhr.send(fd);
});
</script>
</body>
</html>
