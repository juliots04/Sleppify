import crypto from 'node:crypto';
import { SESSION_SECRET, SESSION_COOKIE, SESSION_TTL_MS } from './config';

/**
 * Hash de contraseña con scrypt (nativo de Node, sin dependencias nativas como bcrypt). Formato:
 *   scrypt$<saltHex>$<hashHex>
 * verifyPassword acepta ese formato. Reemplaza el password_hash/bcrypt de PHP; el bootstrap del
 * admin usa esto, así que login y creación NUNCA se desincronizan.
 */
export function hashPassword(password: string): string {
  const salt = crypto.randomBytes(16);
  const hash = crypto.scryptSync(password, salt, 32);
  return `scrypt$${salt.toString('hex')}$${hash.toString('hex')}`;
}

export function verifyPassword(password: string, stored: string): boolean {
  if (!stored) return false;
  const parts = stored.split('$');
  if (parts.length !== 3 || parts[0] !== 'scrypt') return false;
  const salt = Buffer.from(parts[1], 'hex');
  const expected = Buffer.from(parts[2], 'hex');
  const actual = crypto.scryptSync(password, salt, expected.length);
  return expected.length === actual.length && crypto.timingSafeEqual(expected, actual);
}

// ───────────────────────── Sesión STATELESS por cookie firmada ─────────────────────────
// Sin ficheros de sesión en disco (la causa del "carga pero se refresca solo" del PHP: el
// session_save_path a ./sessions no persistía en alwaysdata). El estado va firmado en la cookie:
//   <payloadBase64url>.<hmacBase64url>   donde payload = {uid, name, exp}
// Cualquier manipulación rompe el HMAC y la sesión se rechaza.

interface SessionPayload {
  uid: number;
  name: string;
  exp: number;
}

function b64url(buf: Buffer | string): string {
  return Buffer.from(buf).toString('base64url');
}

function sign(data: string): string {
  return crypto.createHmac('sha256', SESSION_SECRET).update(data).digest('base64url');
}

export function createSessionCookie(uid: number, name: string): string {
  const payload: SessionPayload = { uid, name, exp: nowMs() + SESSION_TTL_MS };
  const body = b64url(JSON.stringify(payload));
  return `${body}.${sign(body)}`;
}

export function readSession(cookieValue: string | undefined): SessionPayload | null {
  if (!cookieValue) return null;
  const dot = cookieValue.lastIndexOf('.');
  if (dot <= 0) return null;
  const body = cookieValue.slice(0, dot);
  const sig = cookieValue.slice(dot + 1);
  const expected = sign(body);
  // Comparación en tiempo constante; longitudes distintas => inválida.
  if (sig.length !== expected.length) return null;
  if (!crypto.timingSafeEqual(Buffer.from(sig), Buffer.from(expected))) return null;
  try {
    const payload = JSON.parse(Buffer.from(body, 'base64url').toString('utf8')) as SessionPayload;
    if (typeof payload.uid !== 'number' || typeof payload.exp !== 'number') return null;
    if (payload.exp < nowMs()) return null;
    return payload;
  } catch {
    return null;
  }
}

export const sessionCookieName = SESSION_COOKIE;

/** Opciones de la cookie de sesión. secure=true detrás del proxy TLS de alwaysdata. */
export function sessionCookieOptions(secure: boolean) {
  return {
    httpOnly: true,
    sameSite: 'lax' as const,
    secure,
    path: '/',
    maxAge: Math.floor(SESSION_TTL_MS / 1000),
  };
}

function nowMs(): number {
  return Date.now();
}
