# Step 9 — Recap and Exercises

> [Back to index](README.md) · Previous: [Step 8](08-operating-compose.md)

## Quick reference

| Concept | Where it lives |
| --- | --- |
| The `dev` / H2 stack | [compose.yaml](compose.yaml) |
| The `prod` / MySQL stack as an override | [compose.mysql.yaml](compose.mysql.yaml), merged with `-f` or `COMPOSE_FILE` |
| Passwords (plaintext, local only) | [.env](.env) |
| One-time database setup | [mysql/init-users.sh](mysql/init-users.sh), runs on an empty volume only |
| Readiness and start order | `healthcheck` + `depends_on: condition: service_healthy` + `up --wait` |
| Service-to-service address | the service name on the project network, via `INVENTORY_SERVICE_URL` |
| Persistence | named volume `mysql-data`, removed only by `down -v` |
| Scaling | `deploy.replicas` or `--scale`, only for unpublished services |
| The same stacks on Kubernetes | [walkthrough 03](../03-k8s-h2-stack/README.md) and [04](../04-k8s-mysql-stack/README.md) |

## Compose versus Kubernetes, line by line

| You wrote in Compose | The Kubernetes equivalent you wrote earlier |
| --- | --- |
| `INVENTORY_SERVICE_URL: http://part-inventory-service:8080` | the same variable in `part-order-service-config` |
| `healthcheck` on `/actuator/health/readiness` | `readinessProbe` on the same path |
| `depends_on: ... service_healthy` | readiness probe + the `wait-for-mysql` initContainer |
| `.env` passwords | `mysql-secret`, with `secretKeyRef` per consumer |
| `mysql-data` named volume | PVC `data-mysql-0` from `volumeClaimTemplates` |
| `./mysql/init-users.sh` bind mount | `mysql-init-scripts` ConfigMap |
| `-f compose.yaml -f compose.mysql.yaml` | `values-dev.yaml` / `values-prod.yaml` overlays |
| `ports: "8080:8080"` | `nodePort: 30080` |
| inventory unpublished | `ClusterIP` Service |
| `deploy.replicas: 2` | `replicas: 2` |
| `down -v` | deleting the Namespace (PVC included) |

What Kubernetes adds that none of the left column provides: multiple machines, real load balancing, rolling updates and rollback, self-healing onto another node, autoscaling, and a declared place for secrets.

## Gotchas

| Gotcha | Where it comes from |
| --- | --- |
| Plain `depends_on` only waits for the container to start, not to be ready | Step 4 |
| A mistyped health check path makes a healthy app `unhealthy` and blocks dependents | Step 4 |
| DNS round-robin is not a load balancer; replicas still hold their own H2 state | Step 5 |
| A published fixed host port stops a service being scaled | Steps 5 and 7 |
| MySQL init scripts only run on an empty volume | Step 6; same rule as walkthrough 04 |
| Do not publish 3306 if you run MySQL locally | Step 6 |
| A single `$` in a Compose file is substituted by Compose, copying secrets into the config | Step 6 |
| `down` keeps volumes, `down -v` does not | Steps 6-8 |
| `COMPOSE_FILE` left set from an earlier step changes every later command | Step 8 |
| `prod` starts empty: no seed data | Step 7; same as Kubernetes |
| `.env` holds plaintext passwords and `docker compose config` prints them | Steps 6 and 7 |

## Discussion questions

1. Why does the order service use the *service* name in its URL rather than the container name?
2. `depends_on` with `service_healthy` solved a start-up problem. What problem does it **not** solve, if MySQL becomes unhealthy an hour later?
3. Why is the MySQL override a separate file instead of more lines in `compose.yaml`? What would you lose by merging them?
4. In Step 7 the order service stays at one replica. Which Kubernetes object removes that restriction, and why?
5. After `down -v` the apps recovered on their own, but in walkthrough 04 they returned HTTP 500. What was different?

## Exercises

1. Make `compose.yaml` pull the published images instead of building: replace `build:` with `image: ram1uj/part-inventory-service:latest` (and the order one) and use `docker compose pull`. Compare the first-start time.
2. Add `restart: unless-stopped` to the services. Kill a container with `docker kill` and watch it return. What does that give you compared with a Kubernetes Deployment?
3. Publish MySQL on host port 3307 (`"3307:3306"`) and connect with a local MySQL client as `inventory_app`. Try to read `part_order_db`; confirm it is refused.
4. Put a reverse proxy (an `nginx` service) in front of the order service, remove its host port, and scale the order service to 2. Show that both replicas answer, and explain why MySQL makes this safe.
5. Add `profiles: ["tools"]` to a small `adminer` or `mysql` client service so it only starts with `--profile tools`.
6. Replace the `.env` file with Compose `secrets:` (file-based) for the MySQL root password. What changes in how the container reads it?
7. Translate this Compose project to Kubernetes by hand using the table above. Which of your results already exist in `k8s-with-mysql/`?
8. Rebuild all four files from memory in an empty folder, then diff each against the reference:

   ```bash
   for f in compose.yaml compose.mysql.yaml .env mysql/init-users.sh; do diff ~/docker-lab/$f /path/to/02b-docker-compose/$f; done
   ```

## What's next

Compose runs the stack on one machine. Walkthroughs [03](../03-k8s-h2-stack/README.md) and [04](../04-k8s-mysql-stack/README.md) run exactly these stacks on Kubernetes, where the gaps listed in Step 1 (several machines, real load balancing, rollouts) are closed. The series index is at [../../README.md](../../README.md).
