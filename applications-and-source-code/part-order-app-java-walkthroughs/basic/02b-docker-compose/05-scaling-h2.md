# Step 5 — Scaling, DNS Round-Robin and the H2 Problem Again

> [Back to index](README.md) · Previous: [Step 4](04-health-and-ordering.md) · Next: [Step 6 — The MySQL Service](06-mysql-service.md)

## Goal

Run two copies of the inventory service, see how Compose spreads traffic between them, and reproduce the shared-state problem from walkthrough 03 without Kubernetes.

## Why this matters

`docker compose up --scale part-inventory-service=2` is the quickest
experiment in this series, and it answers a question Kubernetes also asks: what
actually happens to requests when there are several copies behind one name?

In Compose the answer is plain and a little surprising. There is **no load
balancer**. Docker's embedded DNS simply returns the IP addresses of **all** the
containers for the service name, in rotating order, and the client connects to the
first one. So requests alternate between replicas, but only because each new lookup
gets a rotated list. A client that caches the DNS answer (most HTTP keep-alive clients
do for a while) will stick to one replica. Kubernetes Services do real
connection-level balancing in `kube-proxy`; this is a looser mechanism.

The experiment then repeats a lesson you have already paid for. The `dev` profile
keeps data in H2, **per container**. A second replica is a fresh container with a
fresh `data.sql` seed, so it knows nothing about an order the first replica handled.
Scaling the application is only safe once the state is somewhere shared, which is what Steps 6 and 7 build.

## 1. Make the two replicas differ

Place an order first, so the first container's stock is 98 while a new container will start at 100:

```bash
cd ~/docker-lab
docker compose up -d --wait
curl -s -X POST http://localhost:8080/api/part-orders/place-order \
  -H "Content-Type: application/json" -d '{"sku":"SEAT-SAFE45-001","quantity":2}'; echo
```

Expected: `Order placed successfully`.

## 2. Scale inventory to two

```bash
docker compose up -d --scale part-inventory-service=2 --wait
docker compose ps
```

Expected: two inventory containers, `part-order-app-part-inventory-service-1` (the original) and `-2` (new), both healthy, plus the order service. Compose did not rebuild anything or touch the order service.

The scale flag works because no `container_name` is set and inventory publishes no host port. A fixed host port could not be shared by two containers; this is why Step 3 left inventory unpublished.

## Try it

Look at what the name resolves to, from inside the order container:

```bash
docker compose exec part-order-service getent hosts part-inventory-service
```

Expected: **two** lines, two IP addresses, one name. Now ask for the stock twelve times:

```bash
docker compose exec part-order-service sh -c \
  'for i in 1 2 3 4 5 6 7 8 9 10 11 12; do curl -s http://part-inventory-service:8080/api/parts | grep -o "\"sku\":\"SEAT-SAFE45-001\",\"stock\":[0-9]*"; done' \
  | sort | uniq -c
```

Expected, in this test an exact alternation:

```text
   6 "sku":"SEAT-SAFE45-001","stock":100
   6 "sku":"SEAT-SAFE45-001","stock":98
```

The same URL returns two different answers. Place another order and check the order service's view too: it can only record the order in whichever order-service container handled it, and that container's H2 is private.

Put it back:

```bash
docker compose up -d --scale part-inventory-service=1 --wait
docker compose exec part-order-service getent hosts part-inventory-service
```

Expected: one address again. Compose stopped and removed the extra container.

## Checkpoint

This step creates no file; `compose.yaml` stays as at the end of Step 4. The state to confirm before moving on:

<details>
<summary>Expected <code>docker compose ps</code> after scaling back</summary>

```text
NAME                                      IMAGE                            SERVICE                  STATUS
part-order-app-part-inventory-service-1   part-inventory-service:compose   part-inventory-service   Up ... (healthy)
part-order-app-part-order-service-1       part-order-service:compose       part-order-service       Up ... (healthy)
```

</details>

## Common mistakes

| Symptom | Cause | Fix |
| --- | --- | --- |
| `port is already allocated` when scaling | the service publishes a fixed host port | scale only unpublished services, or put a reverse proxy in front (exercise 4 in the recap) |
| All requests hit one replica | the client caches the DNS answer | each fresh connection is a fresh lookup; a keep-alive client may stay on one |
| `--scale` seems ignored on the next `up` | `up` without `--scale` returns to the file's count | put the count in the file (`deploy.replicas`, Step 7) or repeat the flag |
| Stock identical on all replicas right after scaling | no order was placed before scaling | place an order first (section 1) |
| Orders appear and disappear | the order service is also per-container H2 | only inventory was scaled here, but the same rule applies to both |

Next: **[Step 6 — The MySQL Service](06-mysql-service.md)**.
