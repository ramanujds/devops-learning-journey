# Step 8 — State and Failure: Scoped Users, Restarts, Wiped Volumes

> [Back to index](README.md) · Previous: [Step 7](07-order-service.md) · Next: [Step 9 — Recap and Exercises](09-recap-and-exercises.md)

## Goal

Test the guarantees this stack claims: users cannot cross databases, state survives Pod and MySQL restarts, and know the one failure the design does not protect against.

## Why this matters

Moving state into MySQL changed what can go wrong. App Pods became disposable,
which is the whole point, but now there is one place whose loss is catastrophic.
It helps to test each claim deliberately, because the failure modes are not the ones
you rehearsed with H2:

| Claim | How it is guaranteed | How you test it |
| --- | --- | --- |
| Each service sees only its own data | scoped `GRANT` per user | log in as one, read the other's database |
| App Pods are disposable | state is in MySQL, not the Pod | delete Pods mid-use |
| MySQL restarts do not lose data | the PVC outlives the Pod | delete `mysql-0` |
| A wiped volume is recoverable | **not guaranteed** | wipe it and see |

The last row is the important one. The schema is created by Hibernate only when an
app *starts* (`ddl-auto: update`). If the volume is wiped under a running app, MySQL
comes back empty and re-runs the init script, but the app's tables do not exist and
nothing recreates them until the app restarts. This stack has no backup and no replica, so a wiped or corrupted volume is data loss by design (see "left out" in `NOTES.md`).

## 1. Scoped users hold

Read the inventory database as `inventory_app`, then try the order database:

```bash
PW=$(kubectl -n part-order-app-mysql get secret mysql-secret -o jsonpath='{.data.INVENTORY_DB_PASSWORD}' | base64 -d)
kubectl -n part-order-app-mysql exec mysql-0 -- mysql -uinventory_app -p"$PW" part_inventory_db -e "SELECT sku,stock FROM parts" 2>&1 | grep -v Warning
kubectl -n part-order-app-mysql exec mysql-0 -- mysql -uinventory_app -p"$PW" -e "SHOW TABLES FROM part_order_db" 2>&1 | grep -v Warning
```

Expected: the first query returns the part and its stock. The second fails with `ERROR 1044 (42000): Access denied for user 'inventory_app'@'%' to database 'part_order_db'`. That is the isolation the init script built in Step 4.

## 2. Application Pods are disposable

```bash
kubectl delete pod -n part-order-app-mysql -l app=part-order-service
kubectl delete pod -n part-order-app-mysql -l app=part-inventory-service
kubectl get pods -n part-order-app-mysql
```

Replacements start; wait until all four are `1/1`. Then:

```bash
curl -s http://localhost:30081/api/part-orders | head -c 250
```

Expected: your order is still listed, written by a Pod that no longer exists. With the H2 stack it would be gone.

Scaling is now a safe operation too:

```bash
kubectl scale deploy/part-inventory-service -n part-order-app-mysql --replicas=3
kubectl rollout status deploy/part-inventory-service -n part-order-app-mysql
kubectl scale deploy/part-inventory-service -n part-order-app-mysql --replicas=2
```

A third replica starts, connects to the same database, and answers with the same stock as the other two.

## 3. MySQL restarts

```bash
kubectl delete pod mysql-0 -n part-order-app-mysql
curl -s -m 10 -o /dev/null -w "during outage: HTTP %{http_code}\n" http://localhost:30081/api/part-orders
kubectl get pods -n part-order-app-mysql -w
```

During the outage, requests that need the database fail or hang (the exact result depends on timing). The app Pods stay `Running`: they do not restart, because their liveness probe only checks the app. When `mysql-0` is `1/1` again, the connection pool reconnects by itself:

```bash
curl -s http://localhost:30081/api/part-orders | head -c 250
```

Expected: the same order as before. The volume persisted, and the apps recovered without a restart.

## 4. The failure that is not covered: a wiped volume

This destroys all data in the Namespace. Do it only in the lab.

```bash
kubectl delete pvc data-mysql-0 -n part-order-app-mysql --wait=false
kubectl delete pod mysql-0 -n part-order-app-mysql
```

Wait about 20 seconds for MySQL to initialise again (the init script from Step 4 runs, because the volume is empty), then ask the apps, without restarting them:

```bash
curl -s -m 20 -o /dev/null -w "orders: HTTP %{http_code}\n" http://localhost:30081/api/part-orders
kubectl logs -n part-order-app-mysql deploy/part-inventory-service --tail=200 | grep "doesn't exist" | head -1
```

Expected: `HTTP 500`, and a log line such as `Table 'part_inventory_db.parts' doesn't exist`. MySQL is healthy, the databases and users exist, but the tables are gone and the apps do not know. Restart them so Hibernate recreates the schema:

```bash
kubectl rollout restart deploy/part-inventory-service deploy/part-order-service -n part-order-app-mysql
kubectl rollout status deploy/part-inventory-service -n part-order-app-mysql
kubectl rollout status deploy/part-order-service -n part-order-app-mysql
```

Check the result with the in-cluster call from Step 6: the parts list is `[]`. Everything you created, parts and orders, is gone and must be re-seeded.

## 5. Teardown

```bash
kubectl delete namespace part-order-app-mysql
kubectl get pv,pvc -A
```

Deleting the Namespace removes the PVC, and with Docker Desktop's `hostpath` StorageClass (reclaim policy `Delete`) the volume and its data are removed too, so nothing is left to clean up. That differs from `kubectl delete -f mysql/`, which removes the StatefulSet but **keeps** the PVC and the data.

## Try it

Sections 1-4 are the exercise. After section 4, finish by re-seeding so the stack is usable again:

```bash
kubectl run seed -n part-order-app-mysql --rm -i --restart=Never --image=curlimages/curl --command -- sh -c \
  'sleep 3; curl -s -X POST http://part-inventory-service:8080/api/parts -H "Content-Type: application/json" -d "{\"sku\":\"SEAT-SAFE45-001\",\"name\":\"Seat Safe 45 - Model A\",\"price\":29.99,\"stock\":100}"' 2>/dev/null
```

## Checkpoint

This step creates no file. The expected end state before teardown: `mysql-0` `1/1`, two Pods of each service `1/1`, and a seeded `parts` table.

<details>
<summary>Expected <code>kubectl get pods -n part-order-app-mysql</code></summary>

```text
NAME                                      READY   STATUS    RESTARTS   AGE
mysql-0                                   1/1     Running   0          ...
part-inventory-service-<hash>-<suffix>    1/1     Running   0          ...
part-inventory-service-<hash>-<suffix>    1/1     Running   0          ...
part-order-service-<hash>-<suffix>        1/1     Running   0          ...
part-order-service-<hash>-<suffix>        1/1     Running   0          ...
```

</details>

## Common mistakes

| Symptom | Cause | Fix |
| --- | --- | --- |
| Apps return 500 after the volume was reset | Hibernate creates tables only at start | `kubectl rollout restart` both Deployments |
| `Access denied` for the app user after a reset | the init script did not run, so the users do not exist | check `kubectl logs mysql-0 \| grep init-users`; reset the volume again |
| `pvc ... Terminating` for a long time | the Pod still mounts it | delete the Pod as well |
| Data still present after `kubectl delete -f mysql/` | the StatefulSet's PVC is kept on purpose | delete the PVC, or the Namespace |
| Orders vanish after a reset and nobody noticed | no backups in this stack | treat this stack as disposable, or add a backup job (exercise 6) |

Next: **[Step 9 — Recap and Exercises](09-recap-and-exercises.md)**.
