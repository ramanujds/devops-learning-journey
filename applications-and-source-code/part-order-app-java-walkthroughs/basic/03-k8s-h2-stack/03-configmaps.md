# Step 3 — ConfigMaps: Configuration Outside the Image

> [Back to index](README.md) · Previous: [Step 2](02-namespace.md) · Next: [Step 4 — Inventory Deployment](04-inventory-deployment.md)

## Goal

Create one ConfigMap per service: the inventory service gets its Spring profile, and the order service gets its profile plus the address of the inventory service.

## Why this matters

An image should be identical in every environment; only configuration changes.
Spring Boot reads environment variables, so a ConfigMap becomes a set of
environment variables and the same `ram1uj/part-order-service` image can run
against H2 here and MySQL later with no rebuild.

The line that matters most is `INVENTORY_SERVICE_URL`. The Feign client reads it
(`${INVENTORY_SERVICE_URL:http://localhost:8080}`), and inside a Pod
`localhost:8080` is the order service itself. Get this wrong and the app starts,
passes health checks, and fails only when an order is placed (the `HTTP 500` you
saw in [walkthrough 02, Step 5](../02-build-docker-images/05-run-together.md)).
The value works because Kubernetes DNS resolves a bare Service name to its
Service **within the same Namespace**. From another Namespace you would need the
long form `part-inventory-service.part-order-app.svc.cluster.local`.

Also worth knowing: environment variables are read **when the container starts**. Editing a ConfigMap does not change running Pods; you must restart them (Step 9).

## 1. The inventory ConfigMap

Create `part-inventory-service/configmap.yaml`:

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: part-inventory-service-config
  namespace: part-order-app
data:
  # dev profile -> H2 in-memory DB (schema.sql/data.sql), no MySQL involved
  SPRING_PROFILES_ACTIVE: "dev"
```

`SPRING_PROFILES_ACTIVE` maps to `spring.profiles.active`. The app already defaults to `dev`, but setting it explicitly means the behaviour does not depend on what was baked into the image. Values must be strings, hence the quotes.

## 2. The order ConfigMap

Create `part-order-service/configmap.yaml`:

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: part-order-service-config
  namespace: part-order-app
data:
  # dev profile -> H2 in-memory DB, no MySQL involved
  SPRING_PROFILES_ACTIVE: "dev"
  # same-namespace short DNS name for the inventory Service; used directly by
  # the @FeignClient url attribute in InventoryServiceClient
  INVENTORY_SERVICE_URL: "http://part-inventory-service:8080"
```

`part-inventory-service` is not a made-up label: it must equal the **name of the Service** you create in Step 6, and `8080` its `port`.

## 3. Apply both

```bash
kubectl apply -f part-inventory-service/configmap.yaml -f part-order-service/configmap.yaml
```

## Try it

```bash
kubectl get configmap -n part-order-app
kubectl describe configmap part-order-service-config -n part-order-app
```

Expected: two ConfigMaps listed (plus the automatic `kube-root-ca.crt`), with `DATA` of 1 and 2. `describe` prints both keys and values.

Nothing uses them yet, so nothing has changed in the running system. The next step consumes them.

## Checkpoint

<details>
<summary>Full <code>part-inventory-service/configmap.yaml</code></summary>

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: part-inventory-service-config
  namespace: part-order-app
data:
  # dev profile -> H2 in-memory DB (schema.sql/data.sql), no MySQL involved
  SPRING_PROFILES_ACTIVE: "dev"
```

</details>

This matches [part-inventory-service/configmap.yaml](../../../part-order-app-java/k8s/part-inventory-service/configmap.yaml) exactly.

<details>
<summary>Full <code>part-order-service/configmap.yaml</code></summary>

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: part-order-service-config
  namespace: part-order-app
data:
  # dev profile -> H2 in-memory DB, no MySQL involved
  SPRING_PROFILES_ACTIVE: "dev"
  # same-namespace short DNS name for the inventory Service; used directly by
  # the @FeignClient url attribute in InventoryServiceClient
  INVENTORY_SERVICE_URL: "http://part-inventory-service:8080"
```

</details>

This matches [part-order-service/configmap.yaml](../../../part-order-app-java/k8s/part-order-service/configmap.yaml) exactly.

## Common mistakes

| Symptom | Cause | Fix |
| --- | --- | --- |
| `namespaces "part-order-app" not found` | Step 2 skipped | `kubectl apply -f namespace.yaml` first |
| `cannot unmarshal number into Go struct field ... of type string` | an unquoted number or boolean as a value | quote every ConfigMap value |
| Later, order returns 500 though health is `UP` | `INVENTORY_SERVICE_URL` does not match the Service name or port | make it exactly `http://<service-name>:<port>` |
| Edited the ConfigMap, app behaviour unchanged | env vars are read at container start | `kubectl rollout restart deploy/<name> -n part-order-app` |

Next: **[Step 4 — Inventory Deployment](04-inventory-deployment.md)**.
