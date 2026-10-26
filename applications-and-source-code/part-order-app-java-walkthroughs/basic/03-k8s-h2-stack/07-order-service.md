# Step 7 — The Order Service: Deployment, NodePort Service, First Order

> [Back to index](README.md) · Previous: [Step 6](06-inventory-service.md) · Next: [Step 8 — HPA and Shared State](08-hpa-and-shared-state.md)

## Goal

Deploy `part-order-service`, expose its web UI at `http://localhost:30080`, and place an order that travels from the order service to the inventory service.

## Why this matters

This is the same pattern as inventory, so most of it is copy and adjust. The
value of the step is the **two differences** and the end-to-end proof:

| | inventory | order |
| --- | --- | --- |
| Names, labels, ConfigMap reference | `part-inventory-service` | `part-order-service` |
| Service `type` | internal (see Step 6) | `NodePort` with a **fixed** `nodePort: 30080` |
| ConfigMap keys | profile | profile **and** `INVENTORY_SERVICE_URL` |

The order service is the only human-facing entry point (it serves the Thymeleaf
pages `home`, `place-order` and `orders`), so it is the one that must be
reachable from outside. A NodePort opens the same port on every node of the
cluster; on Docker Desktop that appears on `localhost`. The port is pinned so
the URL is predictable, and the repo avoids clashes between its four variants by giving each a different one (30080 here, 30081 for MySQL).

A cloud cluster would use a LoadBalancer or an Ingress instead; `k8s-with-ingress/` covers that later.

## 1. Deployment: copy, then change the names

```bash
cp part-inventory-service/deployment.yaml part-order-service/deployment.yaml
```

In `part-order-service/deployment.yaml`, replace `part-inventory-service` with `part-order-service` **everywhere** (there are seven occurrences: the Deployment name, three label values, the container name, the image repository, and the ConfigMap reference). With sed:

```bash
sed -i.bak 's/part-inventory-service/part-order-service/g' part-order-service/deployment.yaml && rm part-order-service/deployment.yaml.bak
```

Everything else stays: same probes, same resources, same single replica and the same reasoning. Order history also lives in per-Pod H2.

## 2. The Service

Create `part-order-service/service.yaml`:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: part-order-service
  namespace: part-order-app
  labels:
    app: part-order-service
spec:
  # NodePort so the web UI (home/place-order/orders) is reachable from
  # outside the cluster in a local dev cluster (minikube/kind/Docker Desktop)
  type: NodePort
  selector:
    app: part-order-service
  ports:
    - name: http
      port: 8080
      targetPort: http
      nodePort: 30080
```

## 3. Apply the whole folder

Both ConfigMaps already exist from Step 3. Applying a folder applies every file in it:

```bash
kubectl apply -f part-order-service/
kubectl rollout status deploy/part-order-service -n part-order-app
kubectl get pods,svc -n part-order-app
```

The `configmap.yaml` line will say `unchanged`, which is the point of declarative apply.

## Try it

Open <http://localhost:30080> in a browser: the order service's home page.

Place an order from the command line and watch the inventory change:

```bash
curl -X POST http://localhost:30080/api/part-orders/place-order \
  -H "Content-Type: application/json" \
  -d '{"sku":"SEAT-SAFE45-001","quantity":2}'
```

Expected: `"status":"Order placed successfully","quantity":2,"totalPrice":60.0`.

Check the other side, through a port-forward so it works whichever Service type you chose:

```bash
kubectl port-forward -n part-order-app svc/part-inventory-service 8081:8080 &
sleep 2
curl -s http://localhost:8081/api/parts | grep -o '"sku":"SEAT-SAFE45-001","stock":[0-9]*'
kill %1
```

Expected: `"stock":98`. The request went browser, NodePort, order Pod, Feign call by Service name, inventory Pod. If this works, Steps 3, 4, 6 and 7 are all correct.

Look at the order service's own data too:

```bash
curl -s http://localhost:30080/api/part-orders | head -c 300
```

It returns the seeded orders (`ORD-001`, ...) from the order service's own H2.

## Checkpoint

<details>
<summary>Full <code>part-order-service/deployment.yaml</code></summary>

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: part-order-service
  namespace: part-order-app
  labels:
    app: part-order-service
spec:
  # single replica: dev profile uses per-pod H2 in-memory DB, so state isn't
  # shared across replicas. Bump this once a real shared DB is wired up.
  replicas: 1
  selector:
    matchLabels:
      app: part-order-service
  template:
    metadata:
      labels:
        app: part-order-service
    spec:
      containers:
        - name: part-order-service
          image: ram1uj/part-order-service:latest
          imagePullPolicy: Always
          ports:
            - name: http
              containerPort: 8080
          envFrom:
            - configMapRef:
                name: part-order-service-config
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: http
            initialDelaySeconds: 20
            periodSeconds: 10
            failureThreshold: 3
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: http
            initialDelaySeconds: 30
            periodSeconds: 15
            failureThreshold: 3
          resources:
            requests:
              cpu: 100m
              memory: 256Mi
            limits:
              cpu: 500m
              memory: 512Mi
```

</details>

This matches [part-order-service/deployment.yaml](../../../part-order-app-java/k8s/part-order-service/deployment.yaml) exactly.

<details>
<summary>Full <code>part-order-service/service.yaml</code></summary>

```yaml
apiVersion: v1
kind: Service
metadata:
  name: part-order-service
  namespace: part-order-app
  labels:
    app: part-order-service
spec:
  # NodePort so the web UI (home/place-order/orders) is reachable from
  # outside the cluster in a local dev cluster (minikube/kind/Docker Desktop)
  type: NodePort
  selector:
    app: part-order-service
  ports:
    - name: http
      port: 8080
      targetPort: http
      nodePort: 30080
```

</details>

This matches [part-order-service/service.yaml](../../../part-order-app-java/k8s/part-order-service/service.yaml) exactly.

## Common mistakes

| Symptom | Cause | Fix |
| --- | --- | --- |
| `place-order` returns HTTP 500, pods are Ready | `INVENTORY_SERVICE_URL` does not match the inventory Service name or port | `kubectl get cm part-order-service-config -n part-order-app -o yaml` and `kubectl get svc` |
| `localhost:30080` refuses the connection | Pod not Ready yet, or the Service type is not NodePort | wait for `1/1`; `kubectl get svc -n part-order-app`; fall back to `kubectl port-forward svc/part-order-service 8080:8080` |
| `provided port is already allocated` | another Service already holds 30080 (for example the repo's `k8s/` stack) | delete the other stack, or choose another `nodePort` in 30000-32767 |
| Order page loads but shows no parts | order Pod cannot reach inventory | run the Step 6 DNS test from a Pod in this Namespace; check `kubectl logs deploy/part-order-service` |
| Only some `sed` occurrences changed | edited by hand and missed one | `grep -n inventory part-order-service/deployment.yaml` should print nothing |

Next: **[Step 8 — HPA and Shared State](08-hpa-and-shared-state.md)**.
