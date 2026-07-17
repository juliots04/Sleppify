import type { APIRoute } from 'astro';
import { fileURLToPath } from 'node:url';
import { stat } from 'node:fs/promises';
import { createReadStream } from 'node:fs';
import { Readable } from 'node:stream';
import path from 'node:path';
import { APK_DIR } from '../../lib/config';

export const prerender = false;

/**
 * Sirve los APKs de apk/ (los descarga la app desde `/apk/<archivo>.apk`), validando el nombre
 * para bloquear path traversal (un único segmento, sin barras ni "..").
 *
 * STREAMING (no bufferizado): se envía el fichero a chunks con createReadStream en vez de leerlo
 * entero en memoria (readFile). Antes, con un APK de ~30 MB, el servidor leía TODO a RAM antes de
 * mandar el primer byte → la app se quedaba en 0% varios segundos, y en el proceso Node de RAM
 * limitada de alwaysdata arriesgaba OOM. Con el stream, los bytes salen desde el primer momento
 * (la app avanza el % de inmediato) y la memoria del servidor se mantiene plana.
 */
export const GET: APIRoute = async ({ params, request }) => {
  const rel = params.file ?? '';
  if (!/^[A-Za-z0-9._-]+\.apk$/.test(rel) || rel.includes('..')) {
    return new Response('Not found', { status: 404 });
  }

  const dir = fileURLToPath(APK_DIR);
  const abs = path.join(dir, rel);
  if (!abs.startsWith(dir)) return new Response('Not found', { status: 404 });

  let size = 0;
  try {
    const info = await stat(abs);
    if (!info.isFile()) return new Response('Not found', { status: 404 });
    size = info.size;
  } catch {
    return new Response('Not found', { status: 404 });
  }

  // Soporte de Range (reanudar descargas / que el cliente pida un tramo). Sin Range → fichero
  // completo, pero SIEMPRE en streaming.
  const range = request.headers.get('range');
  const baseHeaders: Record<string, string> = {
    'Content-Type': 'application/vnd.android.package-archive',
    'Content-Disposition': `attachment; filename="${rel}"`,
    'Accept-Ranges': 'bytes',
    'Cache-Control': 'public, max-age=31536000, immutable',
  };

  if (range) {
    const m = /^bytes=(\d*)-(\d*)$/.exec(range.trim());
    if (m) {
      const start = m[1] ? parseInt(m[1], 10) : 0;
      const end = m[2] ? parseInt(m[2], 10) : size - 1;
      if (start >= size || end >= size || start > end) {
        return new Response('Range Not Satisfiable', {
          status: 416,
          headers: { 'Content-Range': `bytes */${size}` },
        });
      }
      const stream = Readable.toWeb(createReadStream(abs, { start, end })) as ReadableStream;
      return new Response(stream, {
        status: 206,
        headers: {
          ...baseHeaders,
          'Content-Range': `bytes ${start}-${end}/${size}`,
          'Content-Length': String(end - start + 1),
        },
      });
    }
  }

  const stream = Readable.toWeb(createReadStream(abs)) as ReadableStream;
  return new Response(stream, {
    headers: { ...baseHeaders, 'Content-Length': String(size) },
  });
};
