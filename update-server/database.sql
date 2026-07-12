-- =====================================================================
-- Sleppify Updates — esquema de la base
--
-- CÓMO USARLO:
--   1) En alwaysdata abre phpMyAdmin, selecciona tu base y usa
--      Importar → este archivo. Crea las tablas (sin usuario todavía).
--   2) Abre set_password.php en el navegador UNA VEZ para crear tu
--      usuario y contraseña del panel (usa el hash bcrypt nativo de
--      PHP — password_hash/password_verify — así que login y creación
--      SIEMPRE están de acuerdo; nada que pueda desincronizarse entre
--      MySQL y PHP). Después borra set_password.php del hosting.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Usuarios del panel (los crea/actualiza set_password.php, no este SQL)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS panel_users (
    id INT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------
-- Registro persistente de versiones publicadas (historial + detalles)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS releases (
    id INT AUTO_INCREMENT PRIMARY KEY,
    version_name VARCHAR(32) NOT NULL,
    version_code INT NOT NULL UNIQUE,
    notes TEXT,
    apk_path VARCHAR(255) NOT NULL DEFAULT '',
    apk_size BIGINT NOT NULL DEFAULT 0,
    published_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------
-- Versión base: la 1.0 (code 1) que ya está instalada en el teléfono.
-- Sirve de piso para que el panel exija siempre un version code mayor.
-- ---------------------------------------------------------------------
INSERT IGNORE INTO releases (version_name, version_code, notes, apk_path, apk_size)
VALUES ('1.0', 1, 'Versión inicial', '', 0);
