import mysql from 'mysql2/promise';
import { DB, ADMIN } from './config';
import { hashPassword } from './auth';

/**
 * Pool MySQL único (lazy). Reemplaza la conexión PDO de config.php. mysql2/promise da un pool con
 * reconexión, así que el panel no se cae si la base cierra una conexión ociosa.
 */
let pool: mysql.Pool | null = null;

export function db(): mysql.Pool {
  if (!pool) {
    pool = mysql.createPool({
      host: DB.host,
      user: DB.user,
      password: DB.pass,
      database: DB.name,
      charset: 'utf8mb4',
      waitForConnections: true,
      connectionLimit: 5,
      enableKeepAlive: true,
    });
  }
  return pool;
}

export interface Release {
  id: number;
  version_name: string;
  version_code: number;
  notes: string;
  apk_path: string;
  apk_size: number;
  mandatory: number;
  dialog_title: string;
  published_at: string;
}

/** Última versión publicada (la base manda; ordena por version_code desc). null si no hay ninguna. */
export async function latestRelease(): Promise<Release | null> {
  const [rows] = await db().query<any[]>(
    `SELECT id, version_name, version_code, notes, apk_path, apk_size, mandatory, dialog_title, published_at
       FROM releases ORDER BY version_code DESC LIMIT 1`
  );
  return rows.length ? (rows[0] as Release) : null;
}

/** Historial de versiones (más recientes primero). */
export async function listReleases(limit = 30): Promise<Release[]> {
  const [rows] = await db().query<any[]>(
    `SELECT id, version_name, version_code, notes, apk_path, apk_size, mandatory, dialog_title, published_at
       FROM releases ORDER BY version_code DESC LIMIT ?`,
    [limit]
  );
  return rows as Release[];
}

/**
 * Bootstrap idempotente del usuario admin, por NOMBRE (no por "tabla vacía"): así "funciona desde
 * 0" aunque la base traiga datos viejos. Casos:
 *  - No existe ADMIN_USER  → lo inserta con hash scrypt de ADMIN_PASS.
 *  - Existe con hash LEGACY (bcrypt `$...` del PHP, que scrypt no verifica) → migra su hash a
 *    scrypt(ADMIN_PASS), para que el login del sistema nuevo funcione sin precomputar nada.
 *  - Existe ya con hash scrypt → no se toca (respeta una posible clave cambiada).
 * Se corre una vez por proceso (salvo fallo, que reintenta en el próximo login).
 */
let adminEnsured = false;
export async function ensureAdminSeed(): Promise<void> {
  if (adminEnsured) return;
  try {
    const [rows] = await db().query<any[]>(
      'SELECT id, password_hash FROM panel_users WHERE username = ? LIMIT 1',
      [ADMIN.user]
    );
    const row = rows[0];
    if (!row) {
      await db().query('INSERT INTO panel_users (username, password_hash) VALUES (?, ?)', [
        ADMIN.user,
        hashPassword(ADMIN.pass),
      ]);
    } else if (typeof row.password_hash !== 'string' || !row.password_hash.startsWith('scrypt$')) {
      // Hash legacy (bcrypt del PHP u otro): migrar al formato scrypt del sistema nuevo.
      await db().query('UPDATE panel_users SET password_hash = ? WHERE id = ?', [
        hashPassword(ADMIN.pass),
        row.id,
      ]);
    }
    adminEnsured = true;
  } catch {
    // La tabla puede no existir todavía (esquema no importado). Reintento en el próximo login.
    adminEnsured = false;
  }
}
