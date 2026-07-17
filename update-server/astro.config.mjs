// @ts-check
import { defineConfig } from 'astro/config';
import node from '@astrojs/node';

// SSR en modo standalone: el sitio corre como un servidor Node (`npm run build` + `npm start`).
// Pensado para un sitio Node en alwaysdata escuchando en HOST/PORT (ver README).
export default defineConfig({
  output: 'server',
  adapter: node({ mode: 'standalone' }),
  // El panel es privado; no hace falta prefetch ni telemetría.
  devToolbar: { enabled: false },
  // El checkOrigin de Astro compara Origin contra el Host que ve Node, que TRAS EL PROXY de
  // alwaysdata puede no coincidir (falso positivo que bloquearía el login). Se desactiva y la
  // verificación de origen se hace a mano en los endpoints (sameOrigin en login/publish).
  security: { checkOrigin: false },
  server: {
    host: true,
    port: Number(process.env.PORT) || 4321,
  },
});
