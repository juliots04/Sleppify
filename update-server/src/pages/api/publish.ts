import type { APIRoute } from 'astro';
import { fileURLToPath } from 'node:url';
import { mkdir, writeFile, unlink } from 'node:fs/promises';
import path from 'node:path';
import { db, latestRelease } from '../../lib/db';
import { APK_DIR, MAX_APK_MB } from '../../lib/config';

export const prerender = false;

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json; charset=utf-8' },
  });
}

/** Comprobación CSRF ligera: el POST debe venir del mismo host (Origin/Referer). */
function sameOrigin(request: Request): boolean {
  const host = request.headers.get('host');
  const origin = request.headers.get('origin') || request.headers.get('referer');
  if (!origin) return true; // algunos clientes no mandan Origin en same-origin
  try {
    return new URL(origin).host === host;
  } catch {
    return false;
  }
}

/**
 * Publica una versión nueva: valida todo, guarda el APK en apk/ y registra la fila en `releases`
 * (la base es la fuente de verdad; version.json se genera en vivo desde ella). Los campos nuevos
 * `mandatory` y `dialog_title` viajan aquí desde el panel.
 */
export const POST: APIRoute = async ({ request, locals }) => {
  if (!locals.session) return json({ ok: false, error: 'Sesión expirada. Vuelve a entrar.' }, 401);
  if (!sameOrigin(request)) return json({ ok: false, error: 'Origen no válido.' }, 403);

  let form: FormData;
  try {
    form = await request.formData();
  } catch {
    return json(
      { ok: false, error: `El APK supera el límite de subida (${MAX_APK_MB} MB) o la petición es inválida.` },
      413
    );
  }

  const versionName = String(form.get('version_name') ?? '').trim();
  const versionCode = parseInt(String(form.get('version_code') ?? '0'), 10) || 0;
  const notes = String(form.get('notes') ?? '').trim();
  const mandatory = String(form.get('mandatory') ?? '') === '1' || form.get('mandatory') === 'on';
  let dialogTitle = String(form.get('dialog_title') ?? '').trim();
  if (!dialogTitle) dialogTitle = '🔥 NUEVA VERSIÓN DISPONIBLE';

  // --- Validaciones de campos ---
  if (!/^[0-9]+(\.[0-9]+)*$/.test(versionName)) {
    return json({ ok: false, error: 'Versión inválida (usa el formato 1.0.1).' }, 400);
  }
  if (versionName.length > 32) return json({ ok: false, error: 'El nombre de versión es demasiado largo.' }, 400);
  if (versionCode <= 0) return json({ ok: false, error: 'Version code inválido.' }, 400);
  if (notes.length > 4000) return json({ ok: false, error: 'Las novedades son demasiado largas (máx. 4000).' }, 400);
  if (dialogTitle.length > 120) return json({ ok: false, error: 'El título del popup es muy largo (máx. 120).' }, 400);

  // El version code debe superar al publicado (la base manda).
  let currentCode = 0;
  try {
    currentCode = (await latestRelease())?.version_code ?? 0;
  } catch {
    return json({ ok: false, error: 'No se pudo leer la base de datos.' }, 500);
  }
  if (versionCode <= currentCode) {
    return json({ ok: false, error: `El version code debe ser mayor que el publicado (${currentCode}).` }, 400);
  }

  // --- APK ---
  const file = form.get('apk');
  if (!(file instanceof File) || file.size === 0) {
    return json({ ok: false, error: 'No llegó el archivo APK.' }, 400);
  }
  if (file.size < 1024) return json({ ok: false, error: 'El archivo APK parece vacío.' }, 400);
  if (file.size > MAX_APK_MB * 1024 * 1024) {
    return json({ ok: false, error: `El APK supera ${MAX_APK_MB} MB.` }, 413);
  }

  const bytes = Buffer.from(await file.arrayBuffer());
  // Un APK es un ZIP: los magic bytes son "PK\x03\x04".
  if (!(bytes[0] === 0x50 && bytes[1] === 0x4b && bytes[2] === 0x03 && bytes[3] === 0x04)) {
    return json({ ok: false, error: 'Ese archivo no parece un APK válido.' }, 400);
  }

  const apkDir = fileURLToPath(APK_DIR);
  const apkName = `sleppify-v${versionName}-${versionCode}.apk`;
  const apkRel = `apk/${apkName}`;
  const apkAbs = path.join(apkDir, apkName);

  try {
    await mkdir(apkDir, { recursive: true });
    await writeFile(apkAbs, bytes);
  } catch {
    return json({ ok: false, error: 'No pude guardar el APK en el servidor.' }, 500);
  }

  // Orden: primero la BASE (persistente). ON DUPLICATE KEY hace idempotente el reintento del code.
  try {
    await db().query(
      `INSERT INTO releases (version_name, version_code, notes, apk_path, apk_size, mandatory, dialog_title)
       VALUES (?, ?, ?, ?, ?, ?, ?)
       ON DUPLICATE KEY UPDATE version_name = VALUES(version_name), notes = VALUES(notes),
         apk_path = VALUES(apk_path), apk_size = VALUES(apk_size),
         mandatory = VALUES(mandatory), dialog_title = VALUES(dialog_title)`,
      [versionName, versionCode, notes, apkRel, bytes.length, mandatory ? 1 : 0, dialogTitle]
    );
  } catch {
    await unlink(apkAbs).catch(() => {});
    return json({ ok: false, error: 'No se pudo registrar la versión en la base de datos.' }, 500);
  }

  return json({ ok: true, versionName, versionCode, mandatory });
};
