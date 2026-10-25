SET NAMES utf8mb4;
-- Deterministic synthetic data for demo_shop.
--
-- Randomness comes from CRC32 of (row id, tag) so every run produces the same data, which keeps
-- demos and evaluation numbers reproducible.
--
-- One business problem is planted for the agent to find:
--   In 2026 Q3, the 母婴 category's repurchase rate drops. The drop is concentrated in 华东 users
--   who registered through the App, and is strongest among users who registered in 2026.
--   At the same time, 华东 母婴 refunds spike, mostly with reason 质量问题.
USE demo_shop;

INSERT INTO regions VALUES (1, '华东'), (2, '华南'), (3, '华北'), (4, '西南'), (5, '华中');
INSERT INTO channels VALUES (1, 'App'), (2, '小程序'), (3, 'PC官网');
INSERT INTO categories VALUES (1, '母婴'), (2, '数码'), (3, '美妆'), (4, '食品'), (5, '服饰'), (6, '家居');

INSERT INTO products VALUES
    (1, '婴儿纸尿裤 L码 52片', 1, 129.00), (2, '婴儿配方奶粉 3段 800g', 1, 268.00),
    (3, '婴儿湿巾 80抽x6包', 1, 49.90), (4, '宝宝辅食米粉', 1, 59.00),
    (5, '婴儿推车 轻便折叠', 1, 899.00), (6, '儿童安全座椅', 1, 1299.00),
    (7, '婴儿洗护套装', 1, 158.00), (8, '宝宝连体衣 纯棉', 1, 89.00),
    (9, '奶瓶 宽口径 240ml', 1, 79.00), (10, '儿童益智积木', 1, 139.00),
    (11, '无线蓝牙耳机', 2, 399.00), (12, '智能手表', 2, 1299.00),
    (13, '移动电源 20000mAh', 2, 149.00), (14, '机械键盘', 2, 459.00),
    (15, '无线鼠标', 2, 99.00), (16, '平板电脑 11英寸', 2, 2799.00),
    (17, 'USB-C 快充充电器', 2, 129.00), (18, '便携蓝牙音箱', 2, 299.00),
    (19, '4K 显示器 27英寸', 2, 1999.00), (20, '降噪头戴耳机', 2, 1599.00),
    (21, '保湿面霜 50ml', 3, 299.00), (22, '防晒霜 SPF50', 3, 159.00),
    (23, '口红 经典色', 3, 249.00), (24, '卸妆油 200ml', 3, 139.00),
    (25, '精华液 30ml', 3, 599.00), (26, '面膜 10片装', 3, 99.00),
    (27, '洗面奶 120g', 3, 79.00), (28, '粉底液', 3, 359.00),
    (29, '香水 50ml', 3, 699.00), (30, '眼霜 15ml', 3, 429.00),
    (31, '每日坚果 30包', 4, 129.00), (32, '挂耳咖啡 50包', 4, 89.00),
    (33, '有机大米 5kg', 4, 69.00), (34, '牛排套餐 10片', 4, 299.00),
    (35, '纯牛奶 24盒', 4, 65.00), (36, '进口橄榄油 1L', 4, 119.00),
    (37, '零食大礼包', 4, 99.00), (38, '茶叶礼盒', 4, 399.00),
    (39, '速食拌面 10袋', 4, 49.90), (40, '蜂蜜 500g', 4, 79.00),
    (41, '纯棉T恤', 5, 79.00), (42, '牛仔裤 直筒', 5, 199.00),
    (43, '羽绒服 轻薄款', 5, 699.00), (44, '运动鞋 跑步款', 5, 499.00),
    (45, '针织开衫', 5, 259.00), (46, '连衣裙', 5, 329.00),
    (47, '休闲衬衫', 5, 169.00), (48, '运动套装', 5, 299.00),
    (49, '帆布鞋', 5, 149.00), (50, '羊毛围巾', 5, 189.00),
    (51, '四件套 纯棉', 6, 399.00), (52, '记忆棉枕头', 6, 159.00),
    (53, '收纳箱 3个装', 6, 89.00), (54, '香薰蜡烛', 6, 69.00),
    (55, '不粘锅炒锅', 6, 239.00), (56, '保温杯 500ml', 6, 129.00),
    (57, '空气加湿器', 6, 199.00), (58, '落地灯', 6, 349.00),
    (59, '浴巾 2条装', 6, 99.00), (60, '餐具套装', 6, 189.00);

SET SESSION cte_max_recursion_depth = 200000;

CREATE TABLE seq (n INT PRIMARY KEY);
INSERT INTO seq (n)
WITH RECURSIVE s (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM s WHERE n < 180000)
SELECT n FROM s;

-- 20,000 users registered evenly from 2024-06-01 to 2026-09-30 (id order = registration order)
INSERT INTO users (id, register_date, region_id, channel_id, gender, age_group)
SELECT n,
       DATE_ADD('2024-06-01', INTERVAL FLOOR((n - 1) * 851 / 20000) DAY),
       CASE
           WHEN CRC32(CONCAT(n, ':region')) % 100 < 35 THEN 1
           WHEN CRC32(CONCAT(n, ':region')) % 100 < 55 THEN 2
           WHEN CRC32(CONCAT(n, ':region')) % 100 < 72 THEN 3
           WHEN CRC32(CONCAT(n, ':region')) % 100 < 86 THEN 4
           ELSE 5 END,
       CASE
           WHEN CRC32(CONCAT(n, ':channel')) % 100 < 55 THEN 1
           WHEN CRC32(CONCAT(n, ':channel')) % 100 < 85 THEN 2
           ELSE 3 END,
       IF(CRC32(CONCAT(n, ':gender')) % 100 < 52, 'F', 'M'),
       ELT(1 + CRC32(CONCAT(n, ':age')) % 4, '18-24', '25-34', '35-44', '45+')
FROM seq
WHERE n <= 20000;

-- 180,000 candidate orders. Taking the smaller of two random user ids weights users by tenure, so
-- each user orders at a roughly constant rate after registering. Each user has a preferred
-- category that 60% of their orders come from, which is what makes repurchase meaningful.
CREATE TABLE tmp_orders AS
SELECT o.n                                                                          AS id,
       o.user_id,
       DATE_ADD(u.register_date, INTERVAL CRC32(CONCAT(o.n, ':day'))
           % (DATEDIFF('2026-09-30', u.register_date) + 1) DAY)                     AS order_date,
       IF(CRC32(CONCAT(o.n, ':pref')) % 100 < 60,
          1 + o.user_id % 6,
          1 + CRC32(CONCAT(o.n, ':cat')) % 6)                                       AS category_id,
       1 + (CRC32(CONCAT(o.n, ':qty')) % 100 >= 80) + (CRC32(CONCAT(o.n, ':qty')) % 100 >= 95) AS quantity
FROM (SELECT n,
             LEAST(1 + CRC32(CONCAT(n, ':u1')) % 20000, 1 + CRC32(CONCAT(n, ':u2')) % 20000) AS user_id
      FROM seq) o
         JOIN users u ON u.id = o.user_id;

ALTER TABLE tmp_orders
    ADD PRIMARY KEY (id),
    ADD product_id INT,
    ADD refunded   TINYINT NOT NULL DEFAULT 0,
    ADD reason     VARCHAR(32);

UPDATE tmp_orders
SET product_id = (category_id - 1) * 10 + 1 + CRC32(CONCAT(id, ':product')) % 10;

-- Planted problem, part 1: remove repeat 母婴 purchases in 2026 Q3 from 华东 users, most heavily
-- from App users.
CREATE TABLE tmp_drop AS
SELECT r.id
FROM (SELECT t.id,
             t.user_id,
             ROW_NUMBER() OVER (PARTITION BY t.user_id ORDER BY t.order_date, t.id) AS rn
      FROM tmp_orders t
      WHERE t.category_id = 1
        AND t.order_date BETWEEN '2026-07-01' AND '2026-09-30') r
         JOIN users u ON u.id = r.user_id
WHERE r.rn > 1
  AND u.region_id = 1
  AND CRC32(CONCAT(r.id, ':drop')) % 100 < CASE
                                               WHEN u.channel_id <> 1 THEN 20
                                               WHEN u.register_date >= '2026-01-01' THEN 95
                                               ELSE 70 END;

DELETE FROM tmp_orders WHERE id IN (SELECT id FROM tmp_drop);

-- Refunds: 4% baseline. Planted problem, part 2: 华东 母婴 orders in 2026 Q3 are refunded far more
-- often, mostly for quality issues.
UPDATE tmp_orders t JOIN users u ON u.id = t.user_id
SET t.refunded = CRC32(CONCAT(t.id, ':refund')) % 100
    < IF(t.category_id = 1 AND u.region_id = 1 AND t.order_date >= '2026-07-01', 18, 4);

UPDATE tmp_orders t JOIN users u ON u.id = t.user_id
SET t.reason = IF(t.category_id = 1 AND u.region_id = 1 AND t.order_date >= '2026-07-01'
                      AND CRC32(CONCAT(t.id, ':reason')) % 100 < 80,
                  '质量问题',
                  ELT(1 + CRC32(CONCAT(t.id, ':reason2')) % 5, '质量问题', '不想要了', '规格不符', '物流太慢', '其他'))
WHERE t.refunded = 1;

INSERT INTO orders (id, user_id, channel_id, order_date, status, total_amount)
SELECT t.id,
       t.user_id,
       IF(CRC32(CONCAT(t.id, ':ochannel')) % 100 < 85, u.channel_id, 1 + CRC32(CONCAT(t.id, ':ochannel2')) % 3),
       t.order_date,
       IF(t.refunded, 'refunded', 'paid'),
       p.price * t.quantity
FROM tmp_orders t
         JOIN users u ON u.id = t.user_id
         JOIN products p ON p.id = t.product_id;

INSERT INTO order_items (order_id, product_id, quantity, unit_price)
SELECT t.id, t.product_id, t.quantity, p.price
FROM tmp_orders t
         JOIN products p ON p.id = t.product_id;

INSERT INTO refunds (order_id, refund_date, reason, amount)
SELECT t.id,
       DATE_ADD(t.order_date, INTERVAL 1 + CRC32(CONCAT(t.id, ':rday')) % 14 DAY),
       t.reason,
       p.price * t.quantity
FROM tmp_orders t
         JOIN products p ON p.id = t.product_id
WHERE t.refunded = 1
ORDER BY t.id;

DROP TABLE tmp_drop, tmp_orders, seq;
