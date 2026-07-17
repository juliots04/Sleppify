/**
 * Configuración central leída de variables de entorno (con valores por defecto que replican los
 * de la versión PHP, para que funcione recién importada la base sin tocar nada). Cámbialos en
 * `.env` en producción — sobre todo SESSION_SECRET.
 */
const env = (globalThis as any).process?.env ?? {};

export const DB = {
  host: env.DB_HOST || 'mysql-sleppifymanagerupdate.alwaysdata.net',
  name: env.DB_NAME || 'sleppifymanagerupdate_user',
  user: env.DB_USER || 'sleppifymanagerupdate',
  pass: env.DB_PASS ?? 'Supern0va123?',
};

export const SESSION_SECRET: string =
  env.SESSION_SECRET || 'sleppify-updates-astro-default-secret-change-me';

export const ADMIN = {
  user: env.ADMIN_USER || 'admin',
  pass: env.ADMIN_PASS || 'Supern0va123?',
};

/** Carpeta donde se guardan y sirven los APKs (se conserva `apk/` de la versión anterior). */
export const APK_DIR = new URL('../../apk/', import.meta.url);

/** Cookie de sesión y su vida (12 h). */
export const SESSION_COOKIE = 'sleppify_admin';
export const SESSION_TTL_MS = 12 * 60 * 60 * 1000;

/** Límite de subida del APK (MB). alwaysdata Node no tiene el post_max_size de PHP. */
export const MAX_APK_MB = 300;
