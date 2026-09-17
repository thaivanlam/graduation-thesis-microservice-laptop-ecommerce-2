-- =====================================================================
-- TechZone — database-level initialisation
--
-- Mounted into the MySQL container at /docker-entrypoint-initdb.d, so it runs
-- ONCE, when the `mysql_data` volume is first created, BEFORE any Spring
-- service starts.
--
-- That timing is why this file only declares databases and never inserts rows:
-- the schema is owned by Hibernate (`ddl-auto: update`), so at this point no
-- table exists yet. Row seeding happens afterwards, from ../seed-db.
--
-- Each service uses its own logical database inside this one instance:
--   ecommerce          user-service     (users, roles, addresses)
--   ecommerce_product  product-service  (catalogue, specifications)
--   ecommerce_order    order-service    (carts, orders, payments)
--   keycloak           Keycloak         (realm, accounts, credentials, sessions)
--
-- The JDBC URLs still carry `createDatabaseIfNotExist=true`, which is what
-- keeps the non-Docker `dev` profile working. Declaring the databases here
-- makes the charset explicit rather than inheriting the server default.
-- =====================================================================

CREATE DATABASE IF NOT EXISTS `ecommerce`
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE DATABASE IF NOT EXISTS `ecommerce_product`
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE DATABASE IF NOT EXISTS `ecommerce_order`
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- Keycloak's own store (ADR-0012). It owns this schema outright and creates its
-- tables on first start; nothing in this project reads or writes it directly.
-- KC_DB_URL also carries createDatabaseIfNotExist=true, so a stack whose
-- mysql_data volume predates ADR-0012 — where this script has already run and
-- will not run again — still comes up.
CREATE DATABASE IF NOT EXISTS `keycloak`
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
