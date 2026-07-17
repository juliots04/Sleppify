-- =====================================================================
-- Sleppify Updates — esquema de la base (versión Astro)
--
-- CÓMO USARLO:
--   1) En alwaysdata abre phpMyAdmin, selecciona tu base e Importa este
--      archivo. Crea las tablas.
--   2) NO necesitas crear el usuario a mano: la app crea el admin
--      automáticamente en el primer arranque/login si la tabla de
--      usuarios está vacía (ADMIN_USER / ADMIN_PASS del .env; por
--      defecto admin / Supern0va123?). Se hashea con scrypt en runtime,
--      así login y creación nunca se desincronizan.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Usuarios del panel (el admin lo siembra la app; aquí solo la tabla)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS panel_users (
    id INT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------
-- Registro persistente de versiones (historial + detalles)
--   mandatory     1 = obligatoria (sin "Más tarde") · 0 = opcional
--   dialog_title  header editable del popup en la app
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS releases (
    id INT AUTO_INCREMENT PRIMARY KEY,
    version_name VARCHAR(32) NOT NULL,
    version_code INT NOT NULL UNIQUE,
    notes TEXT,
    apk_path VARCHAR(255) NOT NULL DEFAULT '',
    apk_size BIGINT NOT NULL DEFAULT 0,
    mandatory TINYINT(1) NOT NULL DEFAULT 1,
    dialog_title VARCHAR(120) NOT NULL DEFAULT '🔥 NUEVA VERSIÓN DISPONIBLE',
    published_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------
-- Si vienes de la versión PHP anterior (tabla releases ya existente sin
-- las columnas nuevas), ejecuta estas dos líneas UNA vez en su lugar:
-- ---------------------------------------------------------------------
-- ALTER TABLE releases ADD COLUMN mandatory TINYINT(1) NOT NULL DEFAULT 1;
-- ALTER TABLE releases ADD COLUMN dialog_title VARCHAR(120) NOT NULL DEFAULT '🔥 NUEVA VERSIÓN DISPONIBLE';

-- ---------------------------------------------------------------------
-- Versión base: la 1.0 (code 1) ya instalada en el teléfono. Piso para
-- que el panel exija siempre un version code mayor.
-- ---------------------------------------------------------------------
INSERT IGNORE INTO releases (version_name, version_code, notes, apk_path, apk_size, mandatory)
VALUES ('1.0', 1, 'Versión inicial', '', 0, 1);
