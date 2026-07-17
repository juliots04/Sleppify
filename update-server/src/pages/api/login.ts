import type { APIRoute } from 'astro';
import { db, ensureAdminSeed } from '../../lib/db';
import {
  verifyPassword,
  createSessionCookie,
  sessionCookieName,
  sessionCookieOptions,
} from '../../lib/auth';

export const prerender = false;

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

function isHttps(request: Request): boolean {
  const proto = request.headers.get('x-forwarded-proto');
  if (proto) return proto.split(',')[0].trim() === 'https';
  return new URL(request.url).protocol === 'https:';
}

/** CSRF ligero: si viene Origin/Referer, debe coincidir con el Host. */
function sameOrigin(request: Request): boolean {
  const host = request.headers.get('host');
  const origin = request.headers.get('origin') || request.headers.get('referer');
  if (!origin) return true;
  try {
    return new URL(origin).host === host;
  } catch {
    return false;
  }
}

/**
 * Login: valida credenciales contra panel_users y emite la cookie de sesión FIRMADA (stateless).
 * Antes de comprobar, asegura el usuario admin (bootstrap) — así funciona recién importada la base.
 * Delay fijo anti fuerza bruta (como el usleep del PHP).
 */
export const POST: APIRoute = async ({ request, cookies }) => {
  if (!sameOrigin(request)) return json({ ok: false, error: 'Origen no válido.' }, 403);
  await ensureAdminSeed();
  await sleep(350);

  let username = '';
  let password = '';
  try {
    const form = await request.formData();
    username = String(form.get('username') ?? '').trim();
    password = String(form.get('password') ?? '');
  } catch {
    return json({ ok: false, error: 'Solicitud inválida.' }, 400);
  }

  if (!username || !password) {
    return json({ ok: false, error: 'Escribe usuario y contraseña.' }, 400);
  }

  try {
    const [rows] = await db().query<any[]>(
      'SELECT id, username, password_hash FROM panel_users WHERE username = ? LIMIT 1',
      [username]
    );
    const row = rows[0];
    if (row && verifyPassword(password, row.password_hash)) {
      const cookie = createSessionCookie(row.id, row.username);
      cookies.set(sessionCookieName, cookie, sessionCookieOptions(isHttps(request)));
      return json({ ok: true });
    }
    return json({ ok: false, error: 'Usuario o contraseña incorrectos.' }, 401);
  } catch (e) {
    return json(
      { ok: false, error: 'No se pudo conectar con la base de datos. Revisa el .env.' },
      500
    );
  }
};

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json; charset=utf-8' },
  });
}
