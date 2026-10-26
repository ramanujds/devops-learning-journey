# Step 3 — MySQL: Headless Service, StatefulSet and a Persistent Volume

> [Back to index](README.md) · Previous: [Step 2](02-namespace-and-secret.md) · Next: [Step 4 — The Init Script](04-init-script.md)

## Goal

Run a single MySQL 8 instance whose data survives the Pod being deleted, reachable inside the cluster by the name `mysql`.

## Why this matters

Run MySQL as a Deployment and you hit two problems at once. A replacement Pod
gets an empty filesystem, so the data is gone. And two Pods could start against
the same storage during a rollout, which corrupts a database. A StatefulSet
fixes both: Pods are numbered (`mysql-0`), replaced one at a time, and each
ordinal keeps its own PersistentVolumeClaim, created from `volumeClaimTemplates`
as `data-mysql-0`. When `mysql-0` is recreated it re-attaches `data-mysql-0` and finds its files.

The Service is **headless** (`clusterIP: None`). A normal Service gives a
virtual IP and load-balances; a headless one has no virtual IP, and its DNS name
resolves directly to the Pod(s). With one MySQL instance there is nothing to
balance, and a StatefulSet's `serviceName` must point at a headless Service
so each Pod gets a stable per-Pod name (`mysql-0.mysql`). The apps simply use the name `mysql`.

## 1. The headless Service

Create `mysql/service.yaml`:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: mysql
  namespace: part-order-app-mysql
  labels:
    app: mysql
spec:
  # headless: gives the StatefulSet pod a stable DNS name, no load-balancing
  # needed since there's a single MySQL instance
  clusterIP: None
  selector:
    app: mysql
  ports:
    - name: mysql
      port: 3306
      targetPort: 3306
```

## 2. The StatefulSet: identity and container

Create `mysql/statefulset.yaml`:

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
```

`serviceName: mysql` ties the StatefulSet to the Service above. `envFrom: secretRef` exposes **all three** Secret keys to this container: `MYSQL_ROOT_PASSWORD` is used by the image itself, and the other two are used by the init script in Step 4.

## 3. The volume

Two additions. First, in the container, after `envFrom`, mount the volume:

```yaml
          volumeMounts:
            - name: data
              mountPath: /var/lib/mysql
```

Second, at the end of the file, indented as a direct child of `spec:` (a sibling of `template:`, not inside it), declare where the volume comes from:

```yaml
  volumeClaimTemplates:
    - metadata:
        name: data
      spec:
        accessModes: ["ReadWriteOnce"]
        resources:
          requests:
            storage: 2Gi
```

- `/var/lib/mysql` is where MySQL keeps its data directory. Mounting the claim there is what makes data survive.
- `ReadWriteOnce` means one node may mount it read-write, which is right for a single database.
- The claim's name, `data`, plus the StatefulSet and ordinal give `data-mysql-0`.

## 4. Apply

```bash
kubectl apply -f mysql/service.yaml -f mysql/statefulset.yaml
kubectl get pods,pvc -n part-order-app-mysql
```

## Try it

Expected: `mysql-0` `Running` within seconds and a PVC `data-mysql-0` `Bound`, `2Gi`. Notice `READY 1/1` appears almost instantly. There is no readiness probe yet (Step 5), so that says nothing about MySQL being usable. Check directly:

```bash
kubectl exec -n part-order-app-mysql mysql-0 -- sh -c 'mysqladmin ping -h127.0.0.1 -uroot -p"$MYSQL_ROOT_PASSWORD"'
```

Expected `mysqld is alive` once it has finished starting (retry after a few seconds if you get a connection error).

Now prove persistence. Create a database and a row, then destroy the Pod:

```bash
kubectl exec -n part-order-app-mysql mysql-0 -- sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "SHOW DATABASES; CREATE DATABASE scratch; CREATE TABLE scratch.t(id INT); INSERT INTO scratch.t VALUES (42); SELECT * FROM scratch.t;"' 2>&1 | grep -v Warning
kubectl delete pod mysql-0 -n part-order-app-mysql
kubectl get pods -n part-order-app-mysql
```

Wait until it is running again (about 15 seconds), then:

```bash
kubectl exec -n part-order-app-mysql mysql-0 -- sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "SELECT * FROM scratch.t;"' 2>&1 | grep -v Warning
```

Expected: `42`. The Pod is brand new, the name is the same, and the data is still there. The first `SHOW DATABASES` lists only the built-in databases: there is no `part_inventory_db` yet. That is Step 4.

## Checkpoint

<details>
<summary>Full <code>mysql/service.yaml</code></summary>

```yaml
apiVersion: v1
kind: Service
metadata:
  name: mysql
  namespace: part-order-app-mysql
  labels:
    app: mysql
spec:
  # headless: gives the StatefulSet pod a stable DNS name, no load-balancing
  # needed since there's a single MySQL instance
  clusterIP: None
  selector:
    app: mysql
  ports:
    - name: mysql
      port: 3306
      targetPort: 3306
```

</details>

This matches [mysql/service.yaml](../../../part-order-app-java/k8s-with-mysql/mysql/service.yaml) exactly.

<details>
<summary>Full <code>mysql/statefulset.yaml</code> (after Step 3)</summary>

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

This is an intermediate version; Steps 4 and 5 complete it.

## Common mistakes

| Symptom | Cause | Fix |
| --- | --- | --- |
| `mysql-0` `Pending`, PVC `Pending` | no default StorageClass | `kubectl get storageclass`; install a provisioner |
| Pod `CrashLoopBackOff`, log says root password option is required | the Secret is missing or `MYSQL_ROOT_PASSWORD` is not set | apply Step 2; check `kubectl describe pod` for `CreateContainerConfigError` |
| `READY 1/1` but connections are refused | no readiness probe, MySQL is still initialising | wait, or add the probes in Step 5 |
| `volumeClaimTemplates` change rejected on re-apply | StatefulSet fields like this are immutable | delete the StatefulSet (the PVC stays) and apply again |
| Data still there after you "deleted everything" | a StatefulSet's PVC outlives the StatefulSet and Pod | `kubectl delete pvc data-mysql-0 -n part-order-app-mysql` to wipe it |

Next: **[Step 4 — The Init Script](04-init-script.md)**.
