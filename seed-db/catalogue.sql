-- =====================================================================
-- TechZone — demo catalogue seed
--
-- Applied by run-seed.sh AFTER product-service has booted and Hibernate has
-- created the tables. It cannot live in /docker-entrypoint-initdb.d, because
-- those scripts run before any table exists.
--
-- Expects two session variables, set by run-seed.sh via --init-command:
--   @seller_id     user_id of `seller1` in the ecommerce schema (may be NULL)
--   @seller_email  matching email
--
-- Prices are USD — the SPA formats with Intl.NumberFormat("en-US", "USD")
-- and Stripe charges in `usd`.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Categories
--
-- category_id is a real AUTO_INCREMENT (Category uses GenerationType.IDENTITY),
-- so ids are left to MySQL and products look the category up by name below.
-- Each insert is guarded so an existing category is never duplicated.
-- ---------------------------------------------------------------------
INSERT INTO `category` (`category_name`)
SELECT 'Gaming Laptops' FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `category` WHERE `category_name` = 'Gaming Laptops');

INSERT INTO `category` (`category_name`)
SELECT 'Ultrabooks' FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `category` WHERE `category_name` = 'Ultrabooks');

INSERT INTO `category` (`category_name`)
SELECT 'Business Laptops' FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `category` WHERE `category_name` = 'Business Laptops');

INSERT INTO `category` (`category_name`)
SELECT 'Creator Laptops' FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `category` WHERE `category_name` = 'Creator Laptops');

SET @cat_gaming   = (SELECT `category_id` FROM `category` WHERE `category_name` = 'Gaming Laptops'   LIMIT 1);
SET @cat_ultra    = (SELECT `category_id` FROM `category` WHERE `category_name` = 'Ultrabooks'       LIMIT 1);
SET @cat_business = (SELECT `category_id` FROM `category` WHERE `category_name` = 'Business Laptops' LIMIT 1);
SET @cat_creator  = (SELECT `category_id` FROM `category` WHERE `category_name` = 'Creator Laptops'  LIMIT 1);

-- ---------------------------------------------------------------------
-- Products
--
-- product_id is written explicitly. Product uses GenerationType.AUTO, which on
-- MySQL makes Hibernate 6 hand out ids from the `product_seq` TABLE generator
-- rather than AUTO_INCREMENT — so the column has no auto value of its own.
-- run-seed.sh raises product_seq.next_val afterwards; without that, the first
-- product created through the UI would collide with a seeded id.
--
-- `image` is a path relative to the directory product-service serves at
-- /images/**, so the API hands the SPA an absolute URL of the form
--   <IMAGE_BASE_URL>/seed/<file>.jpg
-- The files live in product-service/images/seed/ and are copied into that
-- service's container image by its Dockerfile. Their sources and licences are
-- recorded in product-service/images/seed/CREDITS.md.
--
-- special_price is a stored column, not a derived one, so it is computed here
-- the same way the service computes it: price * (1 - discount/100).
-- ---------------------------------------------------------------------
INSERT INTO `product`
    (`product_id`, `product_name`, `image`, `description`, `quantity`,
     `price`, `discount`, `special_price`,
     `seller_id`, `seller_email`, `category_id`, `sku`, `brand`)
VALUES
    (1, 'ASUS ROG Strix G16', 'seed/asus-rog-strix-g16.jpg',
     'A 16-inch gaming machine built around a 165Hz panel and RTX 4060 graphics, with the ROG Intelligent Cooling system for sustained load.',
     25, 1499.00, 10, ROUND(1499.00 * 0.90, 2),
     @seller_id, @seller_email, @cat_gaming, 'ASUS-ROGG16-001', 'ASUS'),

    (2, 'MSI Katana 15', 'seed/msi-katana-15.jpg',
     'An entry-level gaming laptop pairing a 144Hz FHD display with RTX 4050 graphics — the cheapest way into current-generation ray tracing.',
     40, 1099.00, 5, ROUND(1099.00 * 0.95, 2),
     @seller_id, @seller_email, @cat_gaming, 'MSI-KAT15-002', 'MSI'),

    (3, 'Acer Predator Helios Neo 16', 'seed/acer-predator-helios-neo-16.jpg',
     'A desktop-replacement build: 24-core i9, RTX 4070 graphics and a 165Hz WQXGA screen, cooled by dual fans and a vapour chamber.',
     15, 1799.00, 12, ROUND(1799.00 * 0.88, 2),
     @seller_id, @seller_email, @cat_gaming, 'ACER-PHN16-003', 'Acer'),

    (4, 'Lenovo Legion Pro 5', 'seed/lenovo-legion-pro-5.jpg',
     'A 240Hz WQXGA gaming laptop on Ryzen 7 with RTX 4060 graphics, tuned by Lenovo AI Engine+ to shift power between CPU and GPU.',
     22, 1599.00, 8, ROUND(1599.00 * 0.92, 2),
     @seller_id, @seller_email, @cat_gaming, 'LEN-LEGP5-004', 'Lenovo'),

    (5, 'Apple MacBook Air 13 M3', 'seed/apple-macbook-air-13-m3.jpg',
     'A fanless 13.6-inch ultraportable on the M3 chip, with an 18-hour battery and a Liquid Retina display in a 1.24kg body.',
     35, 1299.00, 5, ROUND(1299.00 * 0.95, 2),
     @seller_id, @seller_email, @cat_ultra, 'APPL-MBA13-005', 'Apple'),

    (6, 'ASUS Zenbook 14 OLED', 'seed/asus-zenbook-14-oled.jpg',
     'A 1.2kg ultrabook with a 120Hz 3K OLED panel and a Core Ultra processor whose NPU handles on-device AI workloads.',
     30, 1199.00, 10, ROUND(1199.00 * 0.90, 2),
     @seller_id, @seller_email, @cat_ultra, 'ASUS-ZEN14-006', 'ASUS'),

    (7, 'Dell XPS 13 Plus', 'seed/dell-xps-13-plus.jpg',
     'A minimalist 13.4-inch ultrabook with an edge-to-edge keyboard, invisible haptic trackpad and InfinityEdge display.',
     18, 1349.00, 7, ROUND(1349.00 * 0.93, 2),
     @seller_id, @seller_email, @cat_ultra, 'DELL-XPS13P-007', 'Dell'),

    (8, 'HP Pavilion Aero 13', 'seed/hp-pavilion-aero-13.jpg',
     'Under a kilogram in a magnesium chassis, with a Ryzen 7 processor and a WUXGA IPS display — the lightest option in the range.',
     45, 899.00, 0, 899.00,
     @seller_id, @seller_email, @cat_ultra, 'HP-AERO13-008', 'HP'),

    (9, 'Lenovo ThinkPad X1 Carbon Gen 12', 'seed/lenovo-thinkpad-x1-carbon.jpg',
     'The business flagship: carbon-fibre chassis, MIL-STD-810H durability, 32GB of memory and full vPro manageability.',
     12, 1899.00, 15, ROUND(1899.00 * 0.85, 2),
     @seller_id, @seller_email, @cat_business, 'LEN-X1C12-009', 'Lenovo'),

    (10, 'Dell Latitude 7440', 'seed/dell-latitude-7440.jpg',
     'A 14-inch corporate workhorse with an FHD+ IPS panel, ExpressCharge and the security stack Dell ships for managed fleets.',
     28, 1249.00, 10, ROUND(1249.00 * 0.90, 2),
     @seller_id, @seller_email, @cat_business, 'DELL-LAT7440-010', 'Dell'),

    (11, 'HP EliteBook 840 G11', 'seed/hp-elitebook-840-g11.jpg',
     'A managed-fleet 14-inch notebook with Wolf Security firmware protection, a privacy camera and a WUXGA IPS display.',
     20, 1399.00, 8, ROUND(1399.00 * 0.92, 2),
     @seller_id, @seller_email, @cat_business, 'HP-EB840G11-011', 'HP'),

    (12, 'Apple MacBook Pro 14 M3 Pro', 'seed/apple-macbook-pro-14-m3-pro.jpg',
     'A 14.2-inch Liquid Retina XDR display at 120Hz on the M3 Pro chip — built for colour-accurate video and photo work.',
     14, 1999.00, 5, ROUND(1999.00 * 0.95, 2),
     @seller_id, @seller_email, @cat_creator, 'APPL-MBP14-012', 'Apple'),

    (13, 'ASUS ProArt Studiobook 16 OLED', 'seed/asus-proart-studiobook-16.jpg',
     'A Pantone-validated 3.2K OLED workstation with RTX 4070 graphics, 2TB of storage and the ASUS Dial for creative applications.',
     8, 2499.00, 10, ROUND(2499.00 * 0.90, 2),
     @seller_id, @seller_email, @cat_creator, 'ASUS-PA16-013', 'ASUS'),

    (14, 'MSI Creator M16', 'seed/msi-creator-m16.jpg',
     'A 16-inch QHD+ 165Hz creator laptop with 32GB of memory and RTX 4060 graphics for rendering and timeline scrubbing.',
     16, 1699.00, 8, ROUND(1699.00 * 0.92, 2),
     @seller_id, @seller_email, @cat_creator, 'MSI-CRM16-014', 'MSI');

-- ---------------------------------------------------------------------
-- Specifications
--
-- These are the fields the faceted search filters on, so every seeded product
-- gets one. id is AUTO_INCREMENT (IDENTITY), product_id is the unique FK.
-- ---------------------------------------------------------------------
INSERT INTO `product_specifications`
    (`product_id`, `processor`, `ram`, `storage`, `display`, `graphics`)
VALUES
    (1,  'Intel Core i7-13650HX',  '16GB DDR5',     '1TB SSD NVMe',        '16" FHD+ IPS 165Hz',        'NVIDIA RTX 4060 8GB'),
    (2,  'Intel Core i7-13620H',   '16GB DDR5',     '512GB SSD NVMe',      '15.6" FHD IPS 144Hz',       'NVIDIA RTX 4050 6GB'),
    (3,  'Intel Core i9-14900HX',  '32GB DDR5',     '1TB SSD PCIe Gen 4',  '16" WQXGA IPS 165Hz',       'NVIDIA RTX 4070 8GB'),
    (4,  'AMD Ryzen 7 7745HX',     '16GB DDR5',     '1TB SSD PCIe Gen 4',  '16" WQXGA IPS 240Hz',       'NVIDIA RTX 4060 8GB'),
    (5,  'Apple M3 8-core',        '16GB Unified',  '512GB SSD',           '13.6" Liquid Retina 60Hz',  'Apple 10-core GPU'),
    (6,  'Intel Core Ultra 7 155H','16GB LPDDR5X',  '1TB SSD NVMe',        '14" 3K OLED 120Hz',         'Intel Arc Graphics'),
    (7,  'Intel Core i7-1360P',    '16GB LPDDR5',   '512GB SSD NVMe',      '13.4" FHD+ InfinityEdge',   'Intel Iris Xe Graphics'),
    (8,  'AMD Ryzen 7 7735U',      '16GB LPDDR5',   '512GB SSD NVMe',      '13.3" WUXGA IPS 60Hz',      'AMD Radeon 680M'),
    (9,  'Intel Core Ultra 7 155U','32GB LPDDR5X',  '1TB SSD PCIe Gen 4',  '14" WUXGA IPS 60Hz',        'Intel Graphics'),
    (10, 'Intel Core i5-1345U',    '16GB DDR5',     '512GB SSD NVMe',      '14" FHD+ IPS 60Hz',         'Intel Iris Xe Graphics'),
    (11, 'Intel Core Ultra 5 125U','16GB DDR5',     '512GB SSD NVMe',      '14" WUXGA IPS 60Hz',        'Intel Graphics'),
    (12, 'Apple M3 Pro 11-core',   '18GB Unified',  '512GB SSD',           '14.2" Liquid Retina XDR 120Hz', 'Apple 14-core GPU'),
    (13, 'Intel Core i9-13980HX',  '32GB DDR5',     '2TB SSD PCIe Gen 4',  '16" 3.2K OLED 120Hz',       'NVIDIA RTX 4070 8GB'),
    (14, 'Intel Core i7-13700H',   '32GB DDR5',     '1TB SSD PCIe Gen 4',  '16" QHD+ IPS 165Hz',        'NVIDIA RTX 4060 8GB');
