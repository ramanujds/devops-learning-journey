# Step 7 — The Order Service on MySQL

> [Back to index](README.md) · Previous: [Step 6](06-inventory-service.md) · Next: [Step 8 — State and Failure](08-state-and-failure.md)

## Goal

Run `part-order-service` on its own database with two replicas, expose it on NodePort 30081, and place an order that both services and both replicas agree on.

## Why this matters

This is the inventory pattern again with a different user, a different database
and one extra setting. The extra setting hides a trap. `application-prod.yml` in
the order service defaults `MYSQL_DATABASE` to `part_inventory_db`, a copy-paste
leftover from the inventory service. If the ConfigMap left it unset, the order service would silently connect to **inventory's database**.
It would even succeed, if the user had rights there; here the scoped user `order_app`
has no rights on `part_inventory_db`, so it would fail with `Access denied`. Either way
the lesson is the same: do not rely on a default that was written for the other service. The ConfigMap sets it explicitly, and says why in a comment.

The NodePort is `30081`, not `30080`. NodePorts are cluster-wide, so two Services
cannot both claim the same one, even in different Namespaces. Using a different port
is what lets this stack run next to the one from walkthrough 03.

| | inventory | order |
| --- | --- | --- |
| Database / user | `part_inventory_db` / `inventory_app` | `part_order_db` / `order_app` |
| Secret key | `INVENTORY_DB_PASSWORD` | `ORDER_DB_PASSWORD` |
| Also needs | nothing | `INVENTORY_SERVICE_URL` |
| Service | ClusterIP | NodePort 30081 |

## 1. The ConfigMap

Create `part-order-service/configmap.yaml`:

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: part-order-service-config
  namespace: part-order-app-mysql
data:
  # prod profile -> MySQL via application-prod.yml (ddl-auto: update, so
  # Hibernate creates the schema on first boot; sql.init.mode is "never" so
  # schema.sql/data.sql do NOT run - the DB starts empty, unlike the dev/H2 stack)
  SPRING_PROFILES_ACTIVE: "prod"
  # shared MySQL instance (see ../mysql/) - part_order_db is a database on
  # it, not a separate server
  MYSQL_HOST: "mysql"
  MYSQL_PORT: "3306"
  # application-prod.yml's own default here is wrong (part_inventory_db,
  # copy-paste leftover) - set explicitly so this DB doesn't collide with
  # the inventory service's database
  MYSQL_DATABASE: "part_order_db"
  MYSQL_USER: "order_app"
  # same-namespace short DNS name for the inventory Service; used directly by
  # the @FeignClient url attribute in InventoryServiceClient
  INVENTORY_SERVICE_URL: "http://part-inventory-service:8080"
```

## 2. The Deployment: copy the inventory one

```bash
cp part-inventory-service/deployment.yaml part-order-service/deployment.yaml
sed -i.bak 's/part-inventory-service/part-order-service/g; s/INVENTORY_DB_PASSWORD/ORDER_DB_PASSWORD/' part-order-service/deployment.yaml
rm part-order-service/deployment.yaml.bak
```

The `sed` renames the seven name and label occurrences and switches the Secret key. One thing remains by hand: the comment above the `env` block names the *other* service's password. Change

```yaml
            # pod never sees MYSQL_ROOT_PASSWORD or the order service's password
```

to

```yaml
            # pod never sees MYSQL_ROOT_PASSWORD or the inventory service's password
```

Everything else is identical: `replicas: 2`, the `wait-for-mysql` initContainer, probes and resources.

## 3. The Service

Create `part-order-service/service.yaml`:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: part-order-service
  namespace: part-order-app-mysql
  labels:
    app: part-order-service
spec:
  # NodePort so the web UI (home/place-order/orders) is reachable from
  # outside the cluster in a local dev cluster (minikube/kind/Docker Desktop).
  # NodePort is 30081, not 30080 - NodePorts are cluster-wide, so this stays
  # distinct from the ../k8s (H2) stack's part-order-service if both are
  # applied at once.
  type: NodePort
  selector:
    app: part-order-service
  ports:
    - name: http
      port: 8080
      targetPort: http
      nodePort: 30081
```

## 4. Apply

```bash
kubectl apply -f part-order-service/
kubectl rollout status deploy/part-order-service -n part-order-app-mysql
kubectl get pods,svc -n part-order-app-mysql
```

## Try it

Expected: two order Pods `1/1`, two inventory Pods, `mysql-0`, and the order Service as `8080:30081/TCP`. Open <http://localhost:30081>, then place an order from the command line (the part you created in Step 6 must exist):

```bash
curl -X POST http://localhost:30081/api/part-orders/place-order \
  -H "Content-Type: application/json" \
  -d '{"sku":"SEAT-SAFE45-001","quantity":2}'
```

Expected: `"status":"Order placed successfully","quantity":2,"totalPrice":59.98`.

Verify the data landed in the right database, written by the right service:

```bash
kubectl exec -n part-order-app-mysql mysql-0 -- sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "SELECT sku,stock FROM part_inventory_db.parts; SELECT order_number,part_sku,quantity FROM part_order_db.part_orders" ' 2>&1 | grep -v Warning
```

Expected: stock 98 in inventory's database, and one order row in the order database. If the column names differ in your version, run `SHOW TABLES FROM part_order_db` and `DESCRIBE` the table first.

Now the payoff. Check that the two **order** replicas agree, and the two **inventory** replicas agree:

```bash
kubectl run probe -n part-order-app-mysql --rm -i --restart=Never --image=curlimages/curl --command -- sh -c \
  'sleep 3; for i in 1 2 3 4 5 6 7 8; do curl -s http://part-order-service:8080/api/part-orders | grep -o "ORD-[A-Z0-9]*" | wc -l; done' 2>/dev/null | grep -v "^pod" | sort | uniq -c
```

Expected: `8 1`. Every request, whichever Pod answers, reports the same single order. Run the stock sampling loop from Step 6 again too: still `98` every time. Compare with walkthrough 03, where the identical test returned `100` and `98`.

## Checkpoint

<details>
<summary>Full <code>part-order-service/configmap.yaml</code></summary>

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: part-order-service-config
  namespace: part-order-app-mysql
data:
  # prod profile -> MySQL via application-prod.yml (ddl-auto: update, so
  # Hibernate creates the schema on first boot; sql.init.mode is "never" so
  # schema.sql/data.sql do NOT run - the DB starts empty, unlike the dev/H2 stack)
  SPRING_PROFILES_ACTIVE: "prod"
  # shared MySQL instance (see ../mysql/) - part_order_db is a database on
  # it, not a separate server
  MYSQL_HOST: "mysql"
  MYSQL_PORT: "3306"
  # application-prod.yml's own default here is wrong (part_inventory_db,
  # copy-paste leftover) - set explicitly so this DB doesn't collide with
  # the inventory service's database
  MYSQL_DATABASE: "part_order_db"
  MYSQL_USER: "order_app"
  # same-namespace short DNS name for the inventory Service; used directly by
  # the @FeignClient url attribute in InventoryServiceClient
  INVENTORY_SERVICE_URL: "http://part-inventory-service:8080"
```

</details>

This matches [part-order-service/configmap.yaml](../../../part-order-app-java/k8s-with-mysql/part-order-service/configmap.yaml) exactly.

<details>
<summary>Full <code>part-order-service/deployment.yaml</code></summary>

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: part-order-service
  namespace: part-order-app-mysql
  labels:
    app: part-order-service
spec:
  # state now lives in MySQL, not per-pod, so this can safely run >1 replica
  # (unlike the dev/H2 stack in ../k8s)
  replicas: 2
  selector:
    matchLabels:
      app: part-order-service
  template:
    metadata:
      labels:
        app: part-order-service
    spec:
      initContainers:
        - name: wait-for-mysql
          image: busybox:1.36
          command:
            - sh
            - -c
            - until nc -z mysql 3306; do echo waiting for mysql; sleep 2; done
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
          env:
            # pulled individually (not envFrom the whole secret) so the app
            # pod never sees MYSQL_ROOT_PASSWORD or the inventory service's password
            - name: MYSQL_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: mysql-secret
                  key: ORDER_DB_PASSWORD
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

This matches [part-order-service/deployment.yaml](../../../part-order-app-java/k8s-with-mysql/part-order-service/deployment.yaml) exactly.

<details>
<summary>Full <code>part-order-service/service.yaml</code></summary>

```yaml
apiVersion: v1
kind: Service
metadata:
  name: part-order-service
  namespace: part-order-app-mysql
  labels:
    app: part-order-service
spec:
  # NodePort so the web UI (home/place-order/orders) is reachable from
  # outside the cluster in a local dev cluster (minikube/kind/Docker Desktop).
  # NodePort is 30081, not 30080 - NodePorts are cluster-wide, so this stays
  # distinct from the ../k8s (H2) stack's part-order-service if both are
  # applied at once.
  type: NodePort
  selector:
    app: part-order-service
  ports:
    - name: http
      port: 8080
      targetPort: http
      nodePort: 30081
```

</details>

This matches [part-order-service/service.yaml](../../../part-order-app-java/k8s-with-mysql/part-order-service/service.yaml) exactly.

## Common mistakes

| Symptom | Cause | Fix |
| --- | --- | --- |
| Order app `CrashLoopBackOff`, `Access denied ... part_inventory_db` | `MYSQL_DATABASE` unset, so the wrong default is used | keep the explicit `part_order_db` in the ConfigMap |
| `place-order` returns 500, pods Ready | the part does not exist in inventory (empty `prod` database) or `INVENTORY_SERVICE_URL` wrong | seed a part (Step 6); check the URL |
| `provided port (30081) is already allocated` | another Service holds it | delete the other stack or pick a free port |
| Comment still names the wrong service | `sed` does not touch the comment | edit it by hand; it must match the reference to pass the diff |
| `SELECT` on the orders table fails with unknown column | column names differ from the example | `DESCRIBE part_order_db.part_orders` and adjust |

Next: **[Step 8 — State and Failure](08-state-and-failure.md)**.
