import { defineMiddleware } from 'astro:middleware';
import { readSession, sessionCookieName } from './lib/auth';

/**
 * Middleware de sesión + guard de rutas.
 *  - Lee la cookie firmada y expone `locals.session` (o null).
 *  - /panel exige sesión → si no, redirige a /.
 *  - / (login) con sesión activa → redirige a /panel.
 * Los endpoints /api/* hacen su propia comprobación (devuelven 401 JSON), así que aquí se dejan pasar.
 */
export const onRequest = defineMiddleware(async (context, next) => {
  const raw = context.cookies.get(sessionCookieName)?.value;
  context.locals.session = readSession(raw);

  const path = context.url.pathname;
  const loggedIn = context.locals.session !== null;

  if (path === '/panel' && !loggedIn) {
    return context.redirect('/');
  }
  if (path === '/' && loggedIn) {
    return context.redirect('/panel');
  }

  return next();
});
