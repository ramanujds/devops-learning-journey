# Step 3 — Two Services and Service-Name DNS

> [Back to index](README.md) · Previous: [Step 2](02-first-service.md) · Next: [Step 4 — Health and Ordering](04-health-and-ordering.md)

## Goal

Add the order service, point it at inventory by service name, publish its port, and place an order that crosses both containers.

## Why this matters

The order service finds inventory through one environment variable,
`INVENTORY_SERVICE_URL`. Its default (`http://localhost:8080`) is the container
itself, which is the bug you triggered on purpose in walkthrough 02. In a Compose
project the right value is simply the other service's name: `http://part-inventory-service:8080`. Docker's DNS resolves that name to the
container's IP on the project network, and it keeps working when the container is
recreated and gets a new IP. You no longer create the network or name the containers yourself.

Notice what is **not** in the file: `container_name`. Compose names containers
`<project>-<service>-<n>` so that several copies of a service can exist
(Step 5), which a fixed `container_name` would prevent. DNS uses the *service* name, not the container name.

Publishing is asymmetric on purpose. `ports: "8080:8080"` means *host port 8080 to container port 8080*, on the order service only. The two sides need not match
(`"9090:8080"` would make the UI appear on 9090).

## 1. Add the order service

Append to `compose.yaml`, under `services:`:

```yaml
  part-order-service:
    build: ./part-order-service
    image: part-order-service:compose
    environment:
      SPRING_PROFILES_ACTIVE: dev
      INVENTORY_SERVICE_URL: http://part-inventory-service:8080
    ports:
      - "8080:8080"
```

The service key (`part-order-service`) is both the name you use in commands and its DNS name. The full file now reads:

```yaml
name: part-order-app

services:
  part-inventory-service:
    build: ./part-inventory-service
    image: part-inventory-service:compose
    environment:
      SPRING_PROFILES_ACTIVE: dev

  part-order-service:
    build: ./part-order-service
    image: part-order-service:compose
    environment:
      SPRING_PROFILES_ACTIVE: dev
      INVENTORY_SERVICE_URL: http://part-inventory-service:8080
    ports:
      - "8080:8080"
```

## 2. Start both

```bash
cd ~/docker-lab
docker compose up -d
docker compose ps
```

Compose sees that inventory is unchanged and leaves it alone, and builds and starts the order service. If the images from Step 2 were removed, it builds them again.

## Try it

Wait about 15 seconds for Spring Boot, then:

```bash
curl -s -o /dev/null -w "UI: HTTP %{http_code}\n" http://localhost:8080/
curl -X POST http://localhost:8080/api/part-orders/place-order \
  -H "Content-Type: application/json" \
  -d '{"sku":"SEAT-SAFE45-001","quantity":2}'
```

Expected: `UI: HTTP 200` and `"status":"Order placed successfully","quantity":2`. Now see the effect on the other service:

```bash
docker compose exec part-order-service curl -s http://part-inventory-service:8080/api/parts \
  | grep -o '"sku":"SEAT-SAFE45-001","stock":[0-9]*'
```

Expected: `"stock":98`. That command also proves the DNS name: it ran inside the order container and reached inventory by `part-inventory-service`.

Open <http://localhost:8080> in a browser to see the UI. Then look at the ports column of `docker compose ps`: the order service shows `0.0.0.0:8080->8080/tcp`; inventory still shows only `8080/tcp`.

One thing to observe, not fix yet: there is no ordering between the services. If you ran `docker compose down` and `up -d` and requested the UI in the first seconds, the order service could answer before inventory is ready. Step 4 closes that gap.

## Checkpoint

<details>
<summary>Full <code>compose.yaml (after Step 3)</code></summary>

```yaml
name: part-order-app

services:
  part-inventory-service:
    build: ./part-inventory-service
    image: part-inventory-service:compose
    environment:
      SPRING_PROFILES_ACTIVE: dev

  part-order-service:
    build: ./part-order-service
    image: part-order-service:compose
    environment:
      SPRING_PROFILES_ACTIVE: dev
      INVENTORY_SERVICE_URL: http://part-inventory-service:8080
    ports:
      - "8080:8080"
```

</details>


This is an intermediate version; Step 4 completes it and it then matches [compose.yaml](compose.yaml).

## Common mistakes

| Symptom | Cause | Fix |
| --- | --- | --- |
| `place-order` returns HTTP 500, both containers Up | `INVENTORY_SERVICE_URL` missing or misspelled, so it defaults to `localhost:8080` | check the variable name and the service name in the URL |
| `bind: address already in use` for 8080 | something else publishes 8080 | stop it, or change the left side: `"9090:8080"` |
| `could not translate host name` / `Unknown host` | typed the container name instead of the service name | use `part-inventory-service`, not `part-order-app-part-inventory-service-1` |
| Change to `environment` has no effect | container not recreated | `docker compose up -d` recreates a service whose config changed; check with `docker compose ps` |
| Browser shows the page, orders list is empty | H2 resets whenever the container is recreated | expected for the `dev` profile |

Next: **[Step 4 — Health and Ordering](04-health-and-ordering.md)**.
