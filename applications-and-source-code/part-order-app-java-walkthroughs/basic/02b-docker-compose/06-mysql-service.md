# Step 6 — The MySQL Service, a Volume and the Init Script

> [Back to index](README.md) · Previous: [Step 5](05-scaling-h2.md) · Next: [Step 7 — The Prod Stack](07-prod-stack.md)

## Goal

Add MySQL in a second Compose file that is merged over the first, with a named volume, a script that creates two databases and two scoped users, and passwords kept in `.env`.

## Why this matters

State needs three things Compose handles with different features:

- **Storage that outlives the container.** A named volume (`mysql-data`) is managed by Docker and survives `docker compose down`. It is removed only by `down -v`. This is the Compose version of the PersistentVolumeClaim in walkthrough 04.
- **A one-time initialisation.** The official `mysql` image runs every script in `/docker-entrypoint-initdb.d/` the first time it starts on an **empty** data directory, and never again. A bind-mounted file does the job here, instead of a ConfigMap.
- **Configuration that is not hard-coded.** Compose reads a file called `.env` from the project folder and substitutes `${NAME}` in the Compose file. Passwords live there once, and both MySQL and the applications read them. As with the Kubernetes Secret, this is plaintext: fine for a laptop, never to be committed with real credentials.

Why a **second file** rather than editing `compose.yaml`? Because the H2 stack should keep working untouched.
`docker compose -f compose.yaml -f compose.mysql.yaml ...` merges the files in order: maps are
merged key by key, later values win, and the second file may add whole services. The base file stays the
dev stack, and the override turns it into the MySQL stack. Helm's overlay values files (walkthrough 05) work the same way.

One more deliberate omission: **MySQL has no `ports:` entry.** The apps reach it by name on the project network, and nothing on the host needs to. This also avoids a real conflict. If something on your machine already listens on port 3306 (a locally installed MySQL does), publishing `"3306:3306"` fails to start.

## 1. The passwords

Create `.env` in `~/docker-lab/`:

```text
# Plaintext for local use only - same caveat as k8s-with-mysql/mysql/secret.yaml.
MYSQL_ROOT_PASSWORD=dev-root-pw
INVENTORY_DB_PASSWORD=dev-inventory-pw
ORDER_DB_PASSWORD=dev-order-pw
```

The names are the same keys as the Kubernetes Secret in walkthrough 04. Compose loads `.env` automatically from the folder you run it in.

## 2. The init script

```bash
mkdir -p ~/docker-lab/mysql
```

Create `mysql/init-users.sh`:

```bash
#!/bin/bash
set -e

mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" <<-EOSQL
    CREATE DATABASE IF NOT EXISTS part_inventory_db;
    CREATE DATABASE IF NOT EXISTS part_order_db;

    CREATE USER IF NOT EXISTS 'inventory_app'@'%' IDENTIFIED BY '${INVENTORY_DB_PASSWORD}';
    GRANT ALL PRIVILEGES ON part_inventory_db.* TO 'inventory_app'@'%';

    CREATE USER IF NOT EXISTS 'order_app'@'%' IDENTIFIED BY '${ORDER_DB_PASSWORD}';
    GRANT ALL PRIVILEGES ON part_order_db.* TO 'order_app'@'%';

    FLUSH PRIVILEGES;
EOSQL
```

```bash
chmod +x ~/docker-lab/mysql/init-users.sh
```

It creates `part_inventory_db` and `part_order_db`, then one user for each with rights on **only its own database**. `${INVENTORY_DB_PASSWORD}` and the others are read from the container's environment, which is why the next file passes them in. The here-document is unquoted, so the shell fills them in before MySQL sees the SQL.

## 3. The override file with only MySQL

Create `compose.mysql.yaml`:

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

volumes:
  mysql-data:
```

- `$$MYSQL_ROOT_PASSWORD` in the health check has two dollar signs because Compose treats a single `$` as its own substitution. `$$` passes a literal `$` through, so the shell **inside the container** expands the variable. With a single `$`, Compose substitutes the value from `.env` while parsing, and the real password ends up written into the health check command, where `docker inspect` can show it. Same result when it runs, but the secret is now stored in the container's configuration.
- `CMD-SHELL` runs the check through a shell, needed for the quoted variable.
- The `volumes:` list has two kinds of mount: `mysql-data:/var/lib/mysql` is the **named volume** (declared under the top-level `volumes:`), and `./mysql/init-users.sh:...:ro` is a **bind mount** of one file, read-only.
- All three passwords go into the MySQL container's environment: the image reads `MYSQL_ROOT_PASSWORD` itself, and the script reads the other two.

## 4. Start MySQL alone

The override is not a complete stack on its own, so pass both files, and name the one service you want:

```bash
cd ~/docker-lab
docker compose -f compose.yaml -f compose.mysql.yaml up -d --wait mysql
```

## Try it

```bash
docker compose -f compose.yaml -f compose.mysql.yaml ps
docker compose -f compose.yaml -f compose.mysql.yaml exec mysql sh -c \
  'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "SHOW DATABASES; SELECT user,host FROM mysql.user WHERE user LIKE \"%_app\"; CREATE DATABASE scratch"' 2>&1 | grep -v Warning
```

Expected: `mysql` shows `Up ... (healthy)` with ports `3306/tcp, 33060/tcp` (container-internal, not host), `part_inventory_db` and `part_order_db` in the database list, and the users `inventory_app` and `order_app` with host `%`.

Compose commands get long with two `-f` flags. For the rest of the session you can set the file list once:

```bash
export COMPOSE_FILE=compose.yaml:compose.mysql.yaml
docker compose ps
```

(`COMPOSE_FILE` takes a colon-separated list. Unset it to go back to the dev stack: `unset COMPOSE_FILE`.)

Prove the volume survives, and learn the init-once rule:

```bash
docker compose down
docker volume ls | grep part-order-app
docker compose up -d --wait mysql
docker compose exec mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "SHOW DATABASES"' 2>&1 | grep -v Warning | tr '\n' ' '; echo
docker compose logs mysql | grep -c init-users
```

Expected: the volume `part-order-app_mysql-data` exists after `down`, the `scratch` database you created is still listed, and the log count is `0`: the init script ran during the very first start only, not this time. Editing `init-users.sh` now would change nothing, because the data directory is no longer empty. To make a script change take effect, wipe the volume with `docker compose down -v` (this destroys the data).

## Checkpoint

<details>
<summary>Full <code>.env</code></summary>

```text
# Plaintext for local use only - same caveat as k8s-with-mysql/mysql/secret.yaml.
MYSQL_ROOT_PASSWORD=dev-root-pw
INVENTORY_DB_PASSWORD=dev-inventory-pw
ORDER_DB_PASSWORD=dev-order-pw
```

</details>


This matches [.env](.env) exactly.

<details>
<summary>Full <code>mysql/init-users.sh</code></summary>

```bash
#!/bin/bash
set -e

mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" <<-EOSQL
    CREATE DATABASE IF NOT EXISTS part_inventory_db;
    CREATE DATABASE IF NOT EXISTS part_order_db;

    CREATE USER IF NOT EXISTS 'inventory_app'@'%' IDENTIFIED BY '${INVENTORY_DB_PASSWORD}';
    GRANT ALL PRIVILEGES ON part_inventory_db.* TO 'inventory_app'@'%';

    CREATE USER IF NOT EXISTS 'order_app'@'%' IDENTIFIED BY '${ORDER_DB_PASSWORD}';
    GRANT ALL PRIVILEGES ON part_order_db.* TO 'order_app'@'%';

    FLUSH PRIVILEGES;
EOSQL
```

</details>


This matches [mysql/init-users.sh](mysql/init-users.sh) exactly.

<details>
<summary>Full <code>compose.mysql.yaml (after Step 6)</code></summary>

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

volumes:
  mysql-data:
```

</details>


This is an intermediate version; Step 7 adds the application overrides and it then matches [compose.mysql.yaml](compose.mysql.yaml).

## Common mistakes

| Symptom | Cause | Fix |
| --- | --- | --- |
| `Bind for 0.0.0.0:3306 failed: port is already allocated` | `ports: "3306:3306"` added while a local MySQL holds 3306 | remove `ports`, or publish another host port such as `"3307:3306"` |
| `service "part-inventory-service" has neither an image nor a build context specified` | `compose.mysql.yaml` run on its own | pass both files, or set `COMPOSE_FILE` |
| Databases `part_inventory_db` / `part_order_db` missing | the volume was initialised before the script existed | `docker compose down -v`, then `up` |
| Variable warning `The "MYSQL_ROOT_PASSWORD" variable is not set` | `.env` missing or in the wrong folder | `.env` must sit next to the Compose file you run |
| Health check never passes | single `$` expanded by Compose, or `CMD` instead of `CMD-SHELL` | use `CMD-SHELL` and `$$` |
| Script runs but fails with `Access denied` | `MYSQL_ROOT_PASSWORD` not passed to the container | keep the three passwords under `environment` |
| `exec` into the container fails | service name typo | `docker compose ps` lists names |

Next: **[Step 7 — The Prod Stack](07-prod-stack.md)**.
