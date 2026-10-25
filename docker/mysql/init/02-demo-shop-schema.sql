SET NAMES utf8mb4;
USE demo_shop;

CREATE TABLE regions
(
    id   INT PRIMARY KEY,
    name VARCHAR(32) NOT NULL COMMENT '大区名称，如 华东、华南'
) COMMENT '销售大区';

CREATE TABLE channels
(
    id   INT PRIMARY KEY,
    name VARCHAR(32) NOT NULL COMMENT '渠道名称：App、小程序、PC官网'
) COMMENT '获客与下单渠道';

CREATE TABLE categories
(
    id   INT PRIMARY KEY,
    name VARCHAR(32) NOT NULL COMMENT '品类名称'
) COMMENT '商品品类';

CREATE TABLE products
(
    id          INT PRIMARY KEY,
    name        VARCHAR(64)    NOT NULL COMMENT '商品名称',
    category_id INT            NOT NULL COMMENT '所属品类，关联 categories.id',
    price       DECIMAL(10, 2) NOT NULL COMMENT '当前售价（元）',
    INDEX idx_category (category_id)
) COMMENT '商品';

CREATE TABLE users
(
    id            INT PRIMARY KEY,
    register_date DATE        NOT NULL COMMENT '注册日期',
    region_id     INT         NOT NULL COMMENT '所在大区，关联 regions.id',
    channel_id    INT         NOT NULL COMMENT '注册渠道，关联 channels.id',
    gender        CHAR(1)     NOT NULL COMMENT 'M 男 / F 女',
    age_group     VARCHAR(16) NOT NULL COMMENT '年龄段：18-24、25-34、35-44、45+',
    INDEX idx_register_date (register_date)
) COMMENT '注册用户';

CREATE TABLE orders
(
    id           INT PRIMARY KEY,
    user_id      INT            NOT NULL COMMENT '下单用户，关联 users.id',
    channel_id   INT            NOT NULL COMMENT '下单渠道，关联 channels.id',
    order_date   DATE           NOT NULL COMMENT '下单日期',
    status       VARCHAR(16)    NOT NULL COMMENT 'paid 已支付 / refunded 已退款',
    total_amount DECIMAL(12, 2) NOT NULL COMMENT '订单实付金额（元）',
    INDEX idx_user_date (user_id, order_date),
    INDEX idx_order_date (order_date)
) COMMENT '订单。每个订单只包含一个商品，明细见 order_items';

CREATE TABLE order_items
(
    order_id   INT            NOT NULL COMMENT '关联 orders.id',
    product_id INT            NOT NULL COMMENT '关联 products.id',
    quantity   INT            NOT NULL COMMENT '购买数量',
    unit_price DECIMAL(10, 2) NOT NULL COMMENT '成交单价（元）',
    PRIMARY KEY (order_id, product_id),
    INDEX idx_product (product_id)
) COMMENT '订单明细';

CREATE TABLE refunds
(
    id          INT PRIMARY KEY AUTO_INCREMENT,
    order_id    INT            NOT NULL COMMENT '关联 orders.id',
    refund_date DATE           NOT NULL COMMENT '退款日期',
    reason      VARCHAR(32)    NOT NULL COMMENT '退款原因：质量问题、不想要了、规格不符、物流太慢、其他',
    amount      DECIMAL(12, 2) NOT NULL COMMENT '退款金额（元）',
    INDEX idx_order (order_id)
) COMMENT '退款记录';
