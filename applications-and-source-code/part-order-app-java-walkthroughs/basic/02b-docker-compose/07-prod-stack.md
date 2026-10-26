# Step 7 — The Prod Stack: Applications on MySQL

> [Back to index](README.md) · Previous: [Step 6](06-mysql-service.md) · Next: [Step 8 — Operating Compose](08-operating-compose.md)

## Goal

Switch both services to the `prod` profile against MySQL, run two inventory replicas, and prove the data is consistent across replicas and survives restarts.

## Why this matters

The application containers need four changes to use a real database. All four
go in the override file, and none touches `compose.yaml`:

| Change | Why |
| --- | --- |
| `SPRING_PROFILES_ACTIVE: prod` | selects `application-prod.yml`: MySQL, Hibernate creates tables, no seed data |
| `MYSQL_HOST/PORT/DATABASE/USER/PASSWORD` | the connection settings that file reads; the host is the service name `mysql` |
| `depends_on: mysql: service_healthy` | the apps open a database connection on start-up; MySQL must be ready first |
| `deploy.replicas: 2` (inventory) | safe now, because the state lives in MySQL |

The merge rules do the rest. `environment` entries merge by key, so
`SPRING_PROFILES_ACTIVE` is **replaced** (`dev` to `prod`) while
`INVENTORY_SERVICE_URL` is kept. `depends_on` entries also merge, so the order
service ends up waiting for **both** inventory and MySQL. You will see the result with `docker compose config`.

Each service gets only its own password, the same least-privilege rule as the Kubernetes
`secretKeyRef` in walkthrough 04: the inventory container never holds `ORDER_DB_PASSWORD`.

The order service stays at one replica here, and that is a real Compose limit rather
than an oversight. It publishes host port 8080, and two containers cannot share one
host port. Kubernetes solves this with a Service in front; with Compose you would add a
reverse proxy (exercise 4).

## 1. The application overrides

Append to `compose.mysql.yaml`, between the `mysql` service and the `volumes:` block:

```yaml
  part-inventory-service:
    environment:
      SPRING_PROFILES_ACTIVE: prod
      MYSQL_HOST: mysql
      MYSQL_PORT: "3306"
      MYSQL_DATABASE: part_inventory_db
      MYSQL_USER: inventory_app
      MYSQL_PASSWORD: ${INVENTORY_DB_PASSWORD}
    depends_on:
      mysql:
        condition: service_healthy
    deploy:
      replicas: 2

  part-order-service:
    environment:
      SPRING_PROFILES_ACTIVE: prod
      MYSQL_HOST: mysql
      MYSQL_PORT: "3306"
      MYSQL_DATABASE: part_order_db
      MYSQL_USER: order_app
      MYSQL_PASSWORD: ${ORDER_DB_PASSWORD}
    depends_on:
      mysql:
        condition: service_healthy
```

The `MYSQL_DATABASE` values matter. The order service's own default in `application-prod.yml` is inventory's database, a copy-paste leftover that walkthrough 04 also had to override. Setting it explicitly is the fix.

## 2. See the merged result before running it

```bash
cd ~/docker-lab
docker compose -f compose.yaml -f compose.mysql.yaml config | sed -n '/part-order-service:/,/image:/p' | head -40
```

Expected: under the order service, `depends_on` lists **both** `mysql` and `part-inventory-service`, both `condition: service_healthy`, and `environment` contains the Spring profile `prod` together with `INVENTORY_SERVICE_URL` and the MySQL variables. The password appears as its real value because `config` expands `.env`; that is the output you should keep out of shared logs.

## 3. Start the stack

```bash
docker compose -f compose.yaml -f compose.mysql.yaml down -v
docker compose -f compose.yaml -f compose.mysql.yaml up -d --build --wait
docker compose -f compose.yaml -f compose.mysql.yaml ps
```

(`down -v` first so MySQL starts from an empty volume and runs the init script. If you exported `COMPOSE_FILE` in Step 6, you can drop the `-f` flags.)

## Try it

Expected from `ps`: `mysql` healthy, **two** inventory containers (`-1` and `-2`) healthy, one order service healthy. Startup order: MySQL first, then inventory, then the order service.

The `prod` database starts empty, so the parts list is empty:

```bash
docker compose exec part-order-service curl -s http://part-inventory-service:8080/api/parts; echo
```

Expected: `[]`. Add a part (the id is generated for you), then place an order:

```bash
docker compose exec part-order-service curl -s -X POST http://part-inventory-service:8080/api/parts \
  -H "Content-Type: application/json" \
  -d '{"sku":"SEAT-SAFE45-001","name":"Seat Safe 45 - Model A","price":29.99,"stock":100}'; echo
curl -s -X POST http://localhost:8080/api/part-orders/place-order \
  -H "Content-Type: application/json" -d '{"sku":"SEAT-SAFE45-001","quantity":2}'; echo
```

Now the check that failed in Step 5. Two replicas, one name, twelve questions:

```bash
docker compose exec part-order-service getent hosts part-inventory-service
docker compose exec part-order-service sh -c \
  'for i in 1 2 3 4 5 6 7 8 9 10 11 12; do curl -s http://part-inventory-service:8080/api/parts | grep -o "\"sku\":\"SEAT-SAFE45-001\",\"stock\":[0-9]*"; done' \
  | sort | uniq -c
```

Expected: two IP addresses, and `12 "sku":"SEAT-SAFE45-001","stock":98`. Every replica gives the same answer because both read the same database.

Look at where the data lives and which user wrote it:

```bash
docker compose exec mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "SELECT sku,stock FROM part_inventory_db.parts; SELECT order_number,part_sku,quantity FROM part_order_db.part_orders"' 2>&1 | grep -v Warning
```

Expected: stock `98` in inventory's database and one order row in the order database.

### Persistence

```bash
docker compose down
docker compose up -d --wait
docker compose exec part-order-service sh -c \
  'curl -s http://part-inventory-service:8080/api/parts | grep -o "\"sku\":\"SEAT-SAFE45-001\",\"stock\":[0-9]*"; curl -s http://part-order-service:8080/api/part-orders | grep -o "ORD-[A-Z0-9]*"'
```

Expected: stock still `98` and the order number still listed. Every container was destroyed and recreated; only the `mysql-data` volume survived, and the apps started again from it (two replicas again, because `deploy.replicas` is in the file).

### Wiping everything

```bash
docker compose down -v
docker volume ls | grep -c part-order-app
docker compose up -d --wait
docker compose exec part-order-service curl -s http://part-inventory-service:8080/api/parts; echo
```

Expected: `0` volumes, then `[]`: the data is gone, the init script ran again, and the applications created fresh tables on start-up with no further action. That differs from walkthrough 04, Step 8, where wiping the volume *under running* applications left them returning HTTP 500 until restarted. Here `down -v` removed the application containers together with the volume, so there was nothing stale to notice.

## Checkpoint

<details>
<summary>Full <code>compose.mysql.yaml</code></summary>

```yaml
services:
  mysql:
    image: mysql:8.0
    environment:
      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD}
      INVENTORY_DB_PASSWORD: ${INVENTORY_DB_PASSWORD}
      ORDER_DB_PASSWORD: ${ORDER_DB_PASSWORD}
    volumes:
      - mysql-data:/var/lib/mysql
      - ./mysql/init-users.sh:/docker-entrypoint-initdb.d/init-users.sh:ro
    healthcheck:
      test: ["CMD-SHELL", "mysqladmin ping -h127.0.0.1 -uroot -p\"$$MYSQL_ROOT_PASSWORD\""]
      interval: 10s
      timeout: 5s
      retries: 10
      start_period: 20s

  part-inventory-service:
    environment:
      SPRING_PROFILES_ACTIVE: prod
      MYSQL_HOST: mysql
      MYSQL_PORT: "3306"
      MYSQL_DATABASE: part_inventory_db
      MYSQL_USER: inventory_app
      MYSQL_PASSWORD: ${INVENTORY_DB_PASSWORD}
    depends_on:
      mysql:
        condition: service_healthy
    deploy:
      replicas: 2

  part-order-service:
    environment:
      SPRING_PROFILES_ACTIVE: prod
      MYSQL_HOST: mysql
      MYSQL_PORT: "3306"
      MYSQL_DATABASE: part_order_db
      MYSQL_USER: order_app
      MYSQL_PASSWORD: ${ORDER_DB_PASSWORD}
    depends_on:
      mysql:
        condition: service_healthy

volumes:
  mysql-data:
```

</details>


This matches [compose.mysql.yaml](compose.mysql.yaml) exactly.

## Common mistakes

| Symptom | Cause | Fix |
| --- | --- | --- |
| App `unhealthy`, log says `Access denied for user` | volume initialised before the script or passwords existed, or the wrong password variable | `down -v` and start again; check `docker compose config` shows the right `MYSQL_PASSWORD` |
| App log: `Unknown database` | init script did not run (non-empty volume) | `down -v` |
| Order service starts before MySQL is ready | the `depends_on` on `mysql` is missing for that service | add it; check with `config` |
| Parts list is empty after the switch | expected: `prod` has no seed data | add parts with `POST /api/parts` |
| `port is already allocated` on 8080 | order service scaled, or another process holds 8080 | keep one order replica; stop the other process |
| Data survives when you expected it gone | used `down` without `-v` | `down -v` removes named volumes |

Next: **[Step 8 — Operating Compose](08-operating-compose.md)**.
