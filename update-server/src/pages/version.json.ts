import type { APIRoute } from 'astro';
import { latestRelease } from '../lib/db';

export const prerender = false;

/**
 * Manifiesto público que consulta la app (AppUpdateManager pide /version.json). Reemplaza el
 * fichero estático de la versión PHP: ahora se genera en vivo desde la base (fuente de verdad),
 * así panel y app NUNCA se desincronizan. Incluye los campos nuevos: `dialogTitle` (header
 * editable del popup) y `mandatory` (opcional → la app muestra "Más tarde").
 */
export const GET: APIRoute = async () => {
  const defaults = {
    versionName: '1.0',
    versionCode: 1,
    apk: '',
    size: 0,
    notes: '',
    dialogTitle: '🔥 NUEVA VERSIÓN DISPONIBLE',
    mandatory: true,
    publishedAt: '',
  };

  let body = defaults;
  try {
    const r = await latestRelease();
    if (r) {
      body = {
        versionName: r.version_name,
        versionCode: r.version_code,
        apk: r.apk_path,
        size: r.apk_size,
        notes: r.notes ?? '',
        dialogTitle: r.dialog_title || defaults.dialogTitle,
        mandatory: r.mandatory === 1,
        publishedAt: r.published_at ? new Date(r.published_at).toISOString() : '',
      };
    }
  } catch {
    // base caída → manifiesto por defecto (la app se queda al día, nunca crashea)
  }

  return new Response(JSON.stringify(body, null, 2), {
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      'Cache-Control': 'no-store',
    },
  });
};
