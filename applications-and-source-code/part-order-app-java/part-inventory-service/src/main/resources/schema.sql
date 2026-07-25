CREATE TABLE parts
(
    id    VARCHAR(255) NOT NULL,
    sku   VARCHAR(255),
    name  VARCHAR(255),
    price DECIMAL,
    stock INT,
    CONSTRAINT pk_parts PRIMARY KEY (id)
);