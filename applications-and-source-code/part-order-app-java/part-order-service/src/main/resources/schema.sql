CREATE TABLE part_orders
(
    id           VARCHAR(255) NOT NULL,
    order_number VARCHAR(255),
    part_sku     VARCHAR(255),
    price        DECIMAL,
    quantity     INT,
    status       VARCHAR(255),
    timestamp    TIMESTAMP,
    CONSTRAINT pk_part_orders PRIMARY KEY (id)
);