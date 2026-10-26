# Step 4 — The Init Script: Two Databases, Two Scoped Users

> [Back to index](README.md) · Previous: [Step 3](03-mysql-statefulset.md) · Next: [Step 5 — MySQL Probes and Resources](05-mysql-probes-and-resources.md)

## Goal

Create `part_inventory_db` / `inventory_app` and `part_order_db` / `order_app` automatically, using a script delivered by a ConfigMap, and learn the rule that decides when that script runs.

## Why this matters

Each service should connect as its own user with rights to its own database only.
Doing that by hand after every fresh install does not scale, so the official
`mysql` image supports it: any `*.sh` or `*.sql` file in
`/docker-entrypoint-initdb.d/` is executed during first start-up. A ConfigMap mounted there is a clean way to ship the script with the manifests.

The rule that catches everyone: **the image runs those scripts only when the data
directory is empty.** On any later start it sees an initialised database and
skips them. So if you add or change the script after MySQL has already started once
with its volume (which is exactly your situation right now, from Step 3), nothing
happens. The mount succeeds, the file is visible inside the Pod, and the databases never appear. You will see this happen below. The remedy is
to delete the PVC so the next start is a first start, which also destroys the data.

The script itself uses the passwords from the Secret you loaded with `envFrom`
in Step 3. Its here-document is unquoted, so the shell expands `${INVENTORY_DB_PASSWORD}` before MySQL sees the SQL.

## 1. The ConfigMap with the script

Create `mysql/init-configmap.yaml`:

```yaml
# Runs once, only when the StatefulSet's PVC is first initialized (the
# official mysql image only executes /docker-entrypoint-initdb.d/* against an
# empty datadir). Creates one database + one scoped user per service, so
# part-inventory-service and part-order-service can share this MySQL instance
# without either one being able to touch the other's tables.
apiVersion: v1
kind: ConfigMap
metadata:
  name: mysql-init-scripts
  namespace: part-order-app-mysql
data:
  init-users.sh: |
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

- `set -e` aborts on the first error, so a failed script is visible rather than half-applied.
- `'%'` as the host means "from any host", needed because the apps connect from other Pods.
- `GRANT ... ON part_inventory_db.*` is the scoping: that user has no rights anywhere else.
- `IF NOT EXISTS` makes the script safe to run twice.

## 2. Mount it into the StatefulSet

In `mysql/statefulset.yaml`, add a second `volumeMounts` entry:

```yaml
            - name: init-scripts
              mountPath: /docker-entrypoint-initdb.d
```

and a `volumes:` section at Pod level, between the containers and `volumeClaimTemplates`:

```yaml
      volumes:
        - name: init-scripts
          configMap:
            name: mysql-init-scripts
```

`data` comes from the claim template; `init-scripts` comes from the ConfigMap, which is why it needs its own `volumes` entry.

## 3. Apply, and watch it not work

```bash
kubectl apply -f mysql/init-configmap.yaml -f mysql/statefulset.yaml
kubectl get pods -n part-order-app-mysql
```

Changing the Pod template makes the StatefulSet replace `mysql-0`. When it is running again:

```bash
kubectl exec -n part-order-app-mysql mysql-0 -- ls /docker-entrypoint-initdb.d
kubectl exec -n part-order-app-mysql mysql-0 -- sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "SHOW DATABASES"' 2>&1 | grep -v Warning
```

Expected: the script `init-users.sh` is present, and the database list shows `scratch` from Step 3 but **no** `part_inventory_db` and **no** `part_order_db`. The script was mounted and ignored.

## 4. Reset the volume so the script runs

```bash
kubectl delete pvc data-mysql-0 -n part-order-app-mysql --wait=false
kubectl delete pod mysql-0 -n part-order-app-mysql
```

The PVC is protected while the Pod uses it, so deleting the Pod lets the deletion finish and the StatefulSet recreates both. Wait about 15 seconds, then:

## Try it

```bash
kubectl exec -n part-order-app-mysql mysql-0 -- sh -c \
  'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "SHOW DATABASES; SELECT user,host FROM mysql.user WHERE user LIKE \"%_app\"; SHOW GRANTS FOR inventory_app@\"%\"" ' 2>&1 | grep -v Warning
kubectl logs -n part-order-app-mysql mysql-0 | grep "init-users"
```

Expected: `part_inventory_db` and `part_order_db` are listed, `scratch` is gone (the volume was wiped), both `*_app` users exist with host `%`, and the grants show `ALL PRIVILEGES` on `part_inventory_db` only. The log contains `sourcing /docker-entrypoint-initdb.d/init-users.sh`.

If the list is still missing the new databases, the first start had not finished; wait and repeat. If it never appears, read `kubectl logs mysql-0` for the script's error.

## Checkpoint

<details>
<summary>Full <code>mysql/init-configmap.yaml</code></summary>

```yaml
# Runs once, only when the StatefulSet's PVC is first initialized (the
# official mysql image only executes /docker-entrypoint-initdb.d/* against an
# empty datadir). Creates one database + one scoped user per service, so
# part-inventory-service and part-order-service can share this MySQL instance
# without either one being able to touch the other's tables.
apiVersion: v1
kind: ConfigMap
metadata:
  name: mysql-init-scripts
  namespace: part-order-app-mysql
data:
  init-users.sh: |
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

This matches [mysql/init-configmap.yaml](../../../part-order-app-java/k8s-with-mysql/mysql/init-configmap.yaml) exactly.

<details>
<summary>Full <code>mysql/statefulset.yaml</code> (after Step 4)</summary>

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: mysql
  namespace: part-order-app-mysql
spec:
  serviceName: mysql
  replicas: 1
  selector:
    matchLabels:
      app: mysql
  template:
    metadata:
      labels:
        app: mysql
    spec:
      containers:
        - name: mysql
          image: mysql:8.0
          ports:
            - name: mysql
              containerPort: 3306
          envFrom:
            - secretRef:
                name: mysql-secret
          volumeMounts:
            - name: data
              mountPath: /var/lib/mysql
            - name: init-scripts
              mountPath: /docker-entrypoint-initdb.d
      volumes:
        - name: init-scripts
          configMap:
            name: mysql-init-scripts
  volumeClaimTemplates:
    - metadata:
        name: data
      spec:
        accessModes: ["ReadWriteOnce"]
        resources:
          requests:
            storage: 2Gi
```

</details>

This is an intermediate version; Step 5 completes it.

## Common mistakes

| Symptom | Cause | Fix |
| --- | --- | --- |
| Script is mounted but the databases do not exist | the volume was already initialised | `kubectl delete pvc data-mysql-0` then delete the Pod (this wipes data) |
| Edited the script, changes never apply | same rule: init scripts run once per empty data directory | reset the volume, or run the new SQL by hand with `kubectl exec` |
| Script runs but fails with `Access denied` | `MYSQL_ROOT_PASSWORD` not in the environment | `envFrom: secretRef` must still be on the container |
| `Unknown database` from an app later | script failed part-way (`set -e`) | `kubectl logs mysql-0` for the error; reset the volume |
| `pvc ... is being deleted` stays `Terminating` | the Pod still mounts it | delete the Pod too |
| Password with special characters breaks the SQL | passwords are interpolated into SQL text | keep dev passwords simple, or quote and escape for real ones |

Next: **[Step 5 — MySQL Probes and Resources](05-mysql-probes-and-resources.md)**.
