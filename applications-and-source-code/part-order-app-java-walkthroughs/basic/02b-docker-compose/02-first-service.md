# Step 2 — The First Service

> [Back to index](README.md) · Previous: [Step 1](01-concepts.md) · Next: [Step 3 — Two Services](03-two-services.md)

## Goal

Write a `compose.yaml` with the inventory service only, start it, read its logs, run a command inside it, and tear it down.

## Why this matters

A Compose file is a description, and the first thing to learn is the loop it
enables: edit the file, run `docker compose up -d`, and Compose works out the
difference. It builds missing images, creates the network, and starts only what
needs starting. Run it again with no changes and nothing happens. That
repeatability is the difference from a shell script of `docker run` commands,
which would try to create the same container twice and fail.

Two choices in this file are deliberate:

- **No published port for inventory.** Only the order service has a human-facing UI. Inventory has no `ports:` entry, so it is reachable from other containers in the project but not from your browser, exactly like a `ClusterIP` Service. You will reach it through `docker compose exec`, which runs a command inside the container.
- **`build:` plus `image:`.** `build:` says how to make the image from your source folder. `image:` names the result (`part-inventory-service:compose`). Together, `up` builds the image if it does not exist and tags it with that name, so it never overwrites the `:local` image from walkthrough 02 or the published `ram1uj/...` ones.

## 1. The project name and the first service

In `~/docker-lab/`, create `compose.yaml`:

```yaml
name: part-order-app

services:
  part-inventory-service:
    build: ./part-inventory-service
    image: part-inventory-service:compose
    environment:
      SPRING_PROFILES_ACTIVE: dev
```

- `name: part-order-app` sets the project name. Without it, Compose uses the folder name (`docker-lab`). The name prefixes the network, volume and container names, so setting it makes them predictable.
- `build: ./part-inventory-service` is the build context, the same folder you passed to `docker build` before. Compose finds the `Dockerfile` in it.
- `environment` sets `SPRING_PROFILES_ACTIVE=dev` explicitly, as the ConfigMap did in walkthrough 03: H2 in memory, seed data on every boot.

There is no `version:` line. Modern Compose ignores it, and recent versions warn that it is obsolete.

## 2. Start it

```bash
cd ~/docker-lab
docker compose up -d --build
```

`-d` runs it in the background; `--build` forces a rebuild even if the image exists. The first build takes a minute or two; later ones reuse the layers.

## Try it

```bash
docker compose ps
docker compose logs --tail 5 part-inventory-service
```

Expected: one container `part-order-app-part-inventory-service-1`, status `Up`, and port column showing only `8080/tcp` (exposed inside the network, **not** published to the host).

Spring Boot needs about ten seconds. Then reach the service from inside:

```bash
docker compose exec part-inventory-service curl -s http://localhost:8080/actuator/health
docker compose exec part-inventory-service curl -s http://localhost:8080/api/parts | head -c 150
```

Expected: `{"groups":["liveness","readiness"],"status":"UP"}` and the start of the seeded parts list.

Prove it is not reachable from the host:

```bash
curl -s -m 3 http://localhost:8080/api/parts || echo "not reachable from the host"
```

Look at what Compose actually understood, after expanding paths and defaults:

```bash
docker compose config
```

Expected: your file with the build context turned into an absolute path, plus a `networks: default` entry you never wrote. Compose created a default network for the project.

```bash
docker network ls | grep part-order-app
docker compose down
docker compose ps -a
```

Expected: a network `part-order-app_default`; after `down`, no containers and the network gone. `down` removes containers and networks (not images, and not named volumes unless you add `-v`).

## Checkpoint

<details>
<summary>Full <code>compose.yaml (after Step 2)</code></summary>

```yaml
name: part-order-app

services:
  part-inventory-service:
    build: ./part-inventory-service
    image: part-inventory-service:compose
    environment:
      SPRING_PROFILES_ACTIVE: dev
```

</details>


This is an intermediate version; Steps 3 and 4 extend it until it matches [compose.yaml](compose.yaml).

## Common mistakes

| Symptom | Cause | Fix |
| --- | --- | --- |
| `unable to prepare context: path ".../part-inventory-service" not found` | running `docker compose` outside `~/docker-lab`, or the folder is missing | `cd ~/docker-lab`; check `ls` |
| `no configuration file provided: not found` | no `compose.yaml` in the current folder | Compose looks for `compose.yaml` (or `docker-compose.yml`) in the current directory |
| Container exits immediately | the app failed to start | `docker compose logs part-inventory-service` |
| `curl` inside the container says connection refused | the app is still starting | wait 10 s and retry; Step 4 automates this |
| YAML error `mapping values are not allowed here` | indentation mixed tabs and spaces | use two spaces, no tabs |

Next: **[Step 3 — Two Services](03-two-services.md)**.
