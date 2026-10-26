# Step 6 — The Inventory Service on MySQL

> [Back to index](README.md) · Previous: [Step 5](05-mysql-probes-and-resources.md) · Next: [Step 7 — The Order Service](07-order-service.md)

## Goal

Run `part-inventory-service` with the `prod` profile against MySQL, two replicas, waiting for the database before it starts, and using only its own password.

## Why this matters

This reuses your walkthrough 03 Deployment, with four deliberate changes, each answering a question the H2 version never had to ask.

| Change | Question it answers |
| --- | --- |
| ConfigMap sets `prod` and the MySQL connection | Which database, which user, which profile? |
| `secretKeyRef` for one key into `MYSQL_PASSWORD` | How does the app get a password without seeing the others? |
| `initContainers: wait-for-mysql` | What if the app starts before MySQL accepts connections? |
| `replicas: 2` | Is it now safe to run more than one? Yes: state lives in MySQL |

**The initContainer.** Spring Boot opens its database connection at startup. If
MySQL is not reachable yet, the app fails and Kubernetes restarts it, producing a
noisy `CrashLoopBackOff` that looks like a bug. An initContainer runs to
completion first, so the app container does not start until `nc -z mysql 3306`
succeeds. While it waits, the Pod shows `Init:0/1`, which is normal. It is a
cheap ordering mechanism, and weaker than a readiness probe because it only
checks that the port is open, not that the database users exist (Step 4's script
makes them, and the first connection would fail without them).

**The Secret key.** `envFrom` would hand the container every key, root password
included. Using `env` with `secretKeyRef` copies exactly one key into one variable
name. The app reads `MYSQL_PASSWORD` (see `application-prod.yml`), which is why the variable is renamed here from `INVENTORY_DB_PASSWORD`.

**No seed data.** The `prod` profile sets `spring.sql.init.mode: never` and
`ddl-auto: update`. Hibernate creates the empty `parts` table on first start, and
nothing inserts the sample parts. The inventory will be empty until you add a part yourself.

## 1. The ConfigMap

Create `part-inventory-service/configmap.yaml`:

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: part-inventory-service-config
  namespace: part-order-app-mysql
data:
  # prod profile -> MySQL via application-prod.yml (ddl-auto: update, so
  # Hibernate creates the schema on first boot; sql.init.mode is "never" so
  # schema.sql/data.sql do NOT run - the DB starts empty, unlike the dev/H2 stack)
  SPRING_PROFILES_ACTIVE: "prod"
  # shared MySQL instance (see ../mysql/) - part_inventory_db is a database
  # on it, not a separate server
  MYSQL_HOST: "mysql"
  MYSQL_PORT: "3306"
  MYSQL_DATABASE: "part_inventory_db"
  MYSQL_USER: "inventory_app"
```

`MYSQL_HOST: "mysql"` is the headless Service from Step 3. The names map onto `${MYSQL_HOST}`, `${MYSQL_PORT}`, `${MYSQL_DATABASE}`, `${MYSQL_USER}` in `application-prod.yml`. The password is deliberately not here: it comes from the Secret.

## 2. The Deployment: start from the H2 one

```bash
cp ~/k8s-lab/part-order-app/part-inventory-service/deployment.yaml part-inventory-service/deployment.yaml
```

(If you skipped walkthrough 03, copy `k8s/part-inventory-service/deployment.yaml` from the repo instead.)

Make these edits in `part-inventory-service/deployment.yaml`.

Namespace:

```yaml
  namespace: part-order-app-mysql
```

Replicas and the comment that now explains the opposite decision:

```yaml
  # state now lives in MySQL, not per-pod, so this can safely run >1 replica
  # (unlike the dev/H2 stack in ../k8s)
  replicas: 2
```

An initContainer, under the Pod `spec:` just above `containers:`:

```yaml
      initContainers:
        - name: wait-for-mysql
          image: busybox:1.36
          command:
            - sh
            - -c
            - until nc -z mysql 3306; do echo waiting for mysql; sleep 2; done
```

And an `env` block in the container, after `envFrom`:

```yaml
          env:
            # pulled individually (not envFrom the whole secret) so the app
            # pod never sees MYSQL_ROOT_PASSWORD or the order service's password
            - name: MYSQL_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: mysql-secret
                  key: INVENTORY_DB_PASSWORD
```

`envFrom` (the ConfigMap) and `env` (the Secret key) coexist. Probes and resources stay exactly as in walkthrough 03.

## 3. The Service

Create `part-inventory-service/service.yaml`:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: part-inventory-service
  namespace: part-order-app-mysql
  labels:
    app: part-inventory-service
spec:
  # internal only - part-order-service is the only consumer
  type: ClusterIP
  selector:
    app: part-inventory-service
  ports:
    - name: http
      port: 8080
      targetPort: http
```

This is the `ClusterIP` form from the committed 03 design, and it is what this stack uses: nothing outside the cluster should call inventory. Reach it for testing with a port-forward or a throwaway Pod.

## 4. Apply and watch

In one terminal:

```bash
kubectl get pods -n part-order-app-mysql -w
```

In another:

```bash
kubectl apply -f part-inventory-service/
```

## Try it

Expected sequence for each new Pod: `Init:0/1`, then `PodInitializing`, then `Running 0/1`, then `1/1` after the readiness delay. MySQL from Step 5 is already up, so the init wait is short here; on a fresh install it can be long.

```bash
kubectl rollout status deploy/part-inventory-service -n part-order-app-mysql
kubectl get pods -n part-order-app-mysql
```

Expected: two inventory Pods `1/1`. Now look at what the app did to the database:

```bash
kubectl exec -n part-order-app-mysql mysql-0 -- sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "SHOW TABLES FROM part_inventory_db"' 2>&1 | grep -v Warning
```

Expected: a `parts` table. Hibernate created it, with no `schema.sql`. Ask the API what is in it:

```bash
kubectl run curl-test -n part-order-app-mysql --rm -i --restart=Never \
  --image=curlimages/curl --command -- sh -c 'sleep 3; curl -s http://part-inventory-service:8080/api/parts' 2>/dev/null
```

Expected: `[]`. Add a part (the `id` is generated for you):

```bash
kubectl run seed -n part-order-app-mysql --rm -i --restart=Never --image=curlimages/curl --command -- sh -c \
  'sleep 3; curl -s -X POST http://part-inventory-service:8080/api/parts -H "Content-Type: application/json" -d "{\"sku\":\"SEAT-SAFE45-001\",\"name\":\"Seat Safe 45 - Model A\",\"price\":29.99,\"stock\":100}"' 2>/dev/null
```

Expected: JSON echoing the part with a UUID `id` and `"stock":100`.

Check that the **second replica** sees it too:

```bash
kubectl run probe -n part-order-app-mysql --rm -i --restart=Never --image=curlimages/curl --command -- sh -c \
  'sleep 3; for i in 1 2 3 4 5 6 7 8 9 10 11 12; do curl -s http://part-inventory-service:8080/api/parts | grep -o "\"sku\":\"SEAT-SAFE45-001\",\"stock\":[0-9]*"; done' 2>/dev/null | grep stock | sort | uniq -c
```

Expected: `12 "sku":"SEAT-SAFE45-001","stock":100`. Twelve requests spread over two Pods, one answer.

## Checkpoint

<details>
<summary>Full <code>part-inventory-service/configmap.yaml</code></summary>

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: part-inventory-service-config
  namespace: part-order-app-mysql
data:
  # prod profile -> MySQL via application-prod.yml (ddl-auto: update, so
  # Hibernate creates the schema on first boot; sql.init.mode is "never" so
  # schema.sql/data.sql do NOT run - the DB starts empty, unlike the dev/H2 stack)
  SPRING_PROFILES_ACTIVE: "prod"
  # shared MySQL instance (see ../mysql/) - part_inventory_db is a database
  # on it, not a separate server
  MYSQL_HOST: "mysql"
  MYSQL_PORT: "3306"
  MYSQL_DATABASE: "part_inventory_db"
  MYSQL_USER: "inventory_app"
```

</details>

This matches [part-inventory-service/configmap.yaml](../../../part-order-app-java/k8s-with-mysql/part-inventory-service/configmap.yaml) exactly.

<details>
<summary>Full <code>part-inventory-service/deployment.yaml</code></summary>

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: part-inventory-service
  namespace: part-order-app-mysql
  labels:
    app: part-inventory-service
spec:
  # state now lives in MySQL, not per-pod, so this can safely run >1 replica
  # (unlike the dev/H2 stack in ../k8s)
  replicas: 2
  selector:
    matchLabels:
      app: part-inventory-service
  template:
    metadata:
      labels:
        app: part-inventory-service
    spec:
      initContainers:
        - name: wait-for-mysql
          image: busybox:1.36
          command:
            - sh
            - -c
            - until nc -z mysql 3306; do echo waiting for mysql; sleep 2; done
      containers:
        - name: part-inventory-service
          image: ram1uj/part-inventory-service:latest
          imagePullPolicy: Always
          ports:
            - name: http
              containerPort: 8080
          envFrom:
            - configMapRef:
                name: part-inventory-service-config
          env:
            # pulled individually (not envFrom the whole secret) so the app
            # pod never sees MYSQL_ROOT_PASSWORD or the order service's password
            - name: MYSQL_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: mysql-secret
                  key: INVENTORY_DB_PASSWORD
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

This matches [part-inventory-service/deployment.yaml](../../../part-order-app-java/k8s-with-mysql/part-inventory-service/deployment.yaml) exactly.

<details>
<summary>Full <code>part-inventory-service/service.yaml</code></summary>

```yaml
apiVersion: v1
kind: Service
metadata:
  name: part-inventory-service
  namespace: part-order-app-mysql
  labels:
    app: part-inventory-service
spec:
  # internal only - part-order-service is the only consumer
  type: ClusterIP
  selector:
    app: part-inventory-service
  ports:
    - name: http
      port: 8080
      targetPort: http
```

</details>

This matches [part-inventory-service/service.yaml](../../../part-order-app-java/k8s-with-mysql/part-inventory-service/service.yaml) exactly.

## Common mistakes

| Symptom | Cause | Fix |
| --- | --- | --- |
| Pod stuck at `Init:0/1` | MySQL not reachable: not Ready, wrong Service name, or other Namespace | `kubectl logs <pod> -c wait-for-mysql`; `kubectl get pods mysql-0` |
| App `CrashLoopBackOff`, `Access denied for user` | wrong `MYSQL_USER` or password key, or the init script never ran (Step 4) | check the ConfigMap and `secretKeyRef.key`; verify users with `SELECT user FROM mysql.user` |
| App log: `Unknown database 'part_inventory_db'` | init script did not run | reset the volume (Step 4) |
| `CreateContainerConfigError` | Secret or key name misspelled in `secretKeyRef` | `kubectl describe pod`, fix the name; keys are case-sensitive |
| `/api/parts` returns `[]` | expected: `prod` has no seed data | add a part with `POST /api/parts` |
| Env block ignored | `env:` placed at the wrong indent, or under `envFrom` | it is a sibling of `envFrom`, under the container |

Next: **[Step 7 — The Order Service](07-order-service.md)**.
