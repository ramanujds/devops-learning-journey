# Step 4 — Health Checks and Start-Up Ordering

> [Back to index](README.md) · Previous: [Step 3](03-two-services.md) · Next: [Step 5 — Scaling and the H2 Problem](05-scaling-h2.md)

## Goal

Give each service a health check, make the order service wait until inventory is healthy, and use `--wait` so `docker compose up` only returns when the stack is genuinely ready.

## Why this matters

"Container started" and "application ready" are different moments. A Spring Boot
service takes ten seconds or more to boot, and for that time the container is
`Up` while every request fails. Without a health check, Compose can only wait for
the **process** to exist, so `depends_on` on its own (plain, no condition) gives a false
sense of order: the dependent service starts while the dependency is still booting.

A `healthcheck` fixes that by running a command inside the container on a schedule.
Compose reports the container as `starting`, `healthy` or `unhealthy`, and two things
then use that state:

- `depends_on: ... condition: service_healthy` holds the dependent service back until the dependency is healthy. This is the Compose equivalent of the `wait-for-mysql` initContainer plus readiness probe from walkthrough 04, in one declaration.
- `docker compose up --wait` returns only when every service is running and healthy, and exits non-zero if one becomes unhealthy. That makes it usable in scripts and CI, where "started" is not good enough.

The check uses the app's own readiness endpoint, `/actuator/health/readiness`, the same path the Kubernetes probes call. It runs `curl` **inside** the container (the image contains it), so it tests the app on its own port and needs nothing published to the host.

## 1. The inventory health check

Add under `part-inventory-service`, after `environment`:

```yaml
    healthcheck:
      test: ["CMD", "curl", "-fs", "http://localhost:8080/actuator/health/readiness"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 20s
```

- `test` is the command; exit code 0 means healthy. `-f` makes `curl` fail on an HTTP error status, and `-s` keeps it quiet. Without `-f`, a 503 would count as success.
- `interval` is how often it runs; `timeout` is how long one run may take; `retries` is how many consecutive failures mark it `unhealthy`.
- `start_period` is a grace period: failures in the first 20 seconds do not count, so a slow boot is not mistaken for a broken app.

## 2. The same for the order service, plus the dependency

Add under `part-order-service`, after `ports`:

```yaml
    depends_on:
      part-inventory-service:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "curl", "-fs", "http://localhost:8080/actuator/health/readiness"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 20s
```

The order service now has to wait for `part-inventory-service` to be `healthy` before Compose even creates its container.

## 3. Start with `--wait`

```bash
cd ~/docker-lab
docker compose down
docker compose up -d --build --wait
```

## Try it

Expected output ends with the inventory service `Healthy`, then the order service `Started` and `Healthy`, and the command returns only after both. Then:

```bash
docker compose ps
```

Expected: both `Up ... (healthy)`. The order service started about ten seconds after inventory, the time inventory needed to become healthy.

Now break a health check on purpose. Make a copy with a path that does not exist, and run that instead:

```bash
sed 's#actuator/health/readiness#actuator/health/nope#' compose.yaml > compose.broken.yaml
docker compose down
docker compose -f compose.broken.yaml up -d --wait
```

After about a minute it ends with:

```text
dependency failed to start: container part-order-app-part-inventory-service-1 is unhealthy
```

and a non-zero exit code. Look at the state and why:

```bash
docker compose -f compose.broken.yaml ps -a
docker inspect part-order-app-part-inventory-service-1 --format '{{json .State.Health.Log}}'
```

Expected: inventory `Up ... (unhealthy)` and the order service in `Created`: its container was made but never started, because its dependency never became healthy. The health log shows the failing command with exit code `22`, which is `curl -f` reporting an HTTP 404. The app is running perfectly; the check is wrong, which is a good thing to learn to distinguish.

Clean up and return to the real file:

```bash
docker compose -f compose.broken.yaml down
rm compose.broken.yaml
docker compose up -d --wait
```

## Checkpoint

<details>
<summary>Full <code>compose.yaml</code></summary>

```yaml
name: part-order-app

services:
  part-inventory-service:
    build: ./part-inventory-service
    image: part-inventory-service:compose
    environment:
      SPRING_PROFILES_ACTIVE: dev
    healthcheck:
      test: ["CMD", "curl", "-fs", "http://localhost:8080/actuator/health/readiness"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 20s

  part-order-service:
    build: ./part-order-service
    image: part-order-service:compose
    environment:
      SPRING_PROFILES_ACTIVE: dev
      INVENTORY_SERVICE_URL: http://part-inventory-service:8080
    ports:
      - "8080:8080"
    depends_on:
      part-inventory-service:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "curl", "-fs", "http://localhost:8080/actuator/health/readiness"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 20s
```

</details>


This matches [compose.yaml](compose.yaml) exactly.

## Common mistakes

| Symptom | Cause | Fix |
| --- | --- | --- |
| Container stays `starting`, then `unhealthy` | wrong path or port in the health check | run the command by hand: `docker compose exec part-inventory-service curl -fs http://localhost:8080/actuator/health/readiness` |
| Health check passes while the app is broken | `curl` without `-f` treats any response as success | keep `-fs` |
| Dependent service still starts too early | `depends_on` listed without `condition: service_healthy` | use the mapping form with the condition |
| `unhealthy` right after start on a slow machine | `start_period` shorter than the boot time | raise `start_period` |
| `$VAR` in a health check expands to nothing | Compose substitutes `$VAR` itself before the container sees it | write `$$VAR` for a variable inside the container (used for MySQL in Step 6) |
| `up --wait` times out | a service never becomes healthy | `docker compose ps -a` and `docker compose logs <service>` |

Next: **[Step 5 — Scaling and the H2 Problem](05-scaling-h2.md)**.
