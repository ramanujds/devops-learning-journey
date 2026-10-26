# Step 5 — MySQL Probes and Resources

> [Back to index](README.md) · Previous: [Step 4](04-init-script.md) · Next: [Step 6 — The Inventory Service](06-inventory-service.md)

## Goal

Add readiness and liveness probes that ask MySQL itself whether it is up, plus CPU and memory requests and limits, completing `statefulset.yaml`.

## Why this matters

In Step 3 the Pod reported `READY 1/1` within seconds while MySQL was still
initialising. Anything that trusts that signal, including the app Pods you are
about to deploy, will try to connect too early. MySQL has no HTTP endpoint, so
the probes use `exec`: Kubernetes runs a command inside the container and treats
exit code 0 as success. `mysqladmin ping` returns 0 only when the server answers.

There is a subtlety on first start. The official image runs a temporary server
to execute the init scripts, then restarts the real one. That temporary server is
started with `--skip-networking` (you can see it in the image's
`docker-entrypoint.sh`), so it only answers on a local socket. The probe pings
`127.0.0.1` over TCP, so it fails during initialisation and passes only once the
real server is listening, which is the moment the apps could connect too. The
`initialDelaySeconds` values still matter: they stop the liveness probe from
counting failures while initialisation is running, and are deliberately generous
(readiness 20 s, liveness 40 s) so a slow disk never gets MySQL killed mid-initialisation.

The resource values are larger than the app Pods' because a database's working
set (buffer pool and caches) lives in memory. `limits.memory` is a hard ceiling,
and a MySQL killed for exceeding it can leave an unclean shutdown, so the limit
is double the request.

## 1. Readiness probe

In the container, after the `volumeMounts`:

```yaml
          readinessProbe:
            exec:
              command: ["sh", "-c", "mysqladmin ping -h127.0.0.1 -uroot -p\"$MYSQL_ROOT_PASSWORD\""]
            initialDelaySeconds: 20
            periodSeconds: 10
            timeoutSeconds: 5
```

- `sh -c` is needed so `$MYSQL_ROOT_PASSWORD` is expanded inside the container (it is in the environment from the Secret).
- `-h127.0.0.1` forces a TCP connection instead of the socket, which is closer to what the apps will do.

## 2. Liveness probe

```yaml
          livenessProbe:
            exec:
              command: ["sh", "-c", "mysqladmin ping -h127.0.0.1 -uroot -p\"$MYSQL_ROOT_PASSWORD\""]
            initialDelaySeconds: 40
            periodSeconds: 20
            timeoutSeconds: 5
```

Same command, longer delay, longer period. Readiness failing takes MySQL out of the Service; liveness failing restarts it, which is a heavier reaction, so it is slower to trigger.

## 3. Resources

```yaml
          resources:
            requests:
              cpu: 250m
              memory: 512Mi
            limits:
              cpu: "1"
              memory: 1Gi
```

`cpu: "1"` is quoted because a bare `1` would be a number; both forms mean one full core.

## 4. Apply

```bash
kubectl apply -f mysql/statefulset.yaml
kubectl get pods -n part-order-app-mysql -w
```

Because the Pod template changed, `mysql-0` is replaced. Its volume is kept, so the databases and users from Step 4 are still there.

## Try it

Watch `READY`: the new Pod stays `0/1` for at least 20 seconds, then becomes `1/1`.

```bash
kubectl describe pod mysql-0 -n part-order-app-mysql | grep -E "Liveness|Readiness|Limits|Requests" -A2
kubectl exec -n part-order-app-mysql mysql-0 -- sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "SHOW DATABASES"' 2>&1 | grep -v Warning
```

Expected: both probes shown as `exec [sh -c mysqladmin ping ...]`, and `part_inventory_db` and `part_order_db` still listed.

## Checkpoint

<details>
<summary>Full <code>mysql/statefulset.yaml</code></summary>

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
          readinessProbe:
            exec:
              command: ["sh", "-c", "mysqladmin ping -h127.0.0.1 -uroot -p\"$MYSQL_ROOT_PASSWORD\""]
            initialDelaySeconds: 20
            periodSeconds: 10
            timeoutSeconds: 5
          livenessProbe:
            exec:
              command: ["sh", "-c", "mysqladmin ping -h127.0.0.1 -uroot -p\"$MYSQL_ROOT_PASSWORD\""]
            initialDelaySeconds: 40
            periodSeconds: 20
            timeoutSeconds: 5
          resources:
            requests:
              cpu: 250m
              memory: 512Mi
            limits:
              cpu: "1"
              memory: 1Gi
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

This matches [mysql/statefulset.yaml](../../../part-order-app-java/k8s-with-mysql/mysql/statefulset.yaml) exactly.

## Common mistakes

| Symptom | Cause | Fix |
| --- | --- | --- |
| Pod restarts in a loop, `Liveness probe failed` | `initialDelaySeconds` shorter than first-start initialisation | raise the delay; check `kubectl logs mysql-0` |
| Probe fails with `Access denied` | `MYSQL_ROOT_PASSWORD` unset or a different password than the volume was initialised with | the password is fixed at first initialisation; reset the volume or use the original one |
| `$MYSQL_ROOT_PASSWORD` is empty in the probe | command given without `sh -c`, so no shell expansion | keep the `sh -c` form |
| Pod `Pending`, `Insufficient memory` | 512 Mi request does not fit alongside the app Pods | raise Docker Desktop memory |
| `OOMKilled` | `limits.memory` too low for the buffer pool | raise it |

Next: **[Step 6 — The Inventory Service](06-inventory-service.md)**.
