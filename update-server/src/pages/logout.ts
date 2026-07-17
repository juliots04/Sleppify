import type { APIRoute } from 'astro';
import { sessionCookieName } from '../lib/auth';

export const prerender = false;

/** Cierra sesión: borra la cookie firmada y vuelve al login. GET para poder usar un enlace simple. */
export const GET: APIRoute = async ({ cookies, redirect }) => {
  cookies.delete(sessionCookieName, { path: '/' });
  return redirect('/');
};
