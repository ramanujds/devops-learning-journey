# Step 9 — Recap and Exercises

> [Back to index](README.md) · Previous: [Step 8](08-state-and-failure.md)

## Quick reference

| Concept | Where it lives |
| --- | --- |
| Isolated Namespace, own NodePort | [namespace.yaml](../../../part-order-app-java/k8s-with-mysql/namespace.yaml), order Service `30081` |
| Credentials, one key per consumer | [mysql/secret.yaml](../../../part-order-app-java/k8s-with-mysql/mysql/secret.yaml), `secretKeyRef` in each Deployment |
| Stable DB identity and storage | [mysql/statefulset.yaml](../../../part-order-app-java/k8s-with-mysql/mysql/statefulset.yaml) with `volumeClaimTemplates` |
| DNS name for the DB | [mysql/service.yaml](../../../part-order-app-java/k8s-with-mysql/mysql/service.yaml) (headless, `clusterIP: None`) |
| Two databases, two scoped users | [mysql/init-configmap.yaml](../../../part-order-app-java/k8s-with-mysql/mysql/init-configmap.yaml), runs once on an empty volume |
| `prod` profile and connection settings | `configmap.yaml` in each service folder; `application-prod.yml` in the app |
| Start-up ordering | `wait-for-mysql` initContainer in each Deployment |
| Safe scaling | `replicas: 2`, state in MySQL |
| Design notes, sequence diagram, left-out table | [k8s-with-mysql/NOTES.md](../../../part-order-app-java/k8s-with-mysql/NOTES.md) |

## Gotchas

| Gotcha | Where it comes from |
| --- | --- |
| Init scripts only run on an empty data directory | the official `mysql` image; hit when the script is added to a used volume (Step 4) |
| Root password changes in the Secret have no effect after first start | the password is stored in the data directory at initialisation (Step 5) |
| `READY 1/1` before MySQL can accept connections | no probe until Step 5 |
| `Init:0/1` on app Pods is normal | the `wait-for-mysql` initContainer is waiting (Step 6) |
| `prod` has no seed data; the parts list starts as `[]` | `sql.init.mode: never` (Step 6) |
| Order service defaults to inventory's database name | copy-paste leftover in `application-prod.yml`; the ConfigMap overrides it (Step 7) |
| Wiped volume gives HTTP 500 until the apps restart | schema is created only at app start (Step 8) |
| `kubectl delete -f mysql/` keeps the data; deleting the Namespace removes it | PVCs created by a StatefulSet are not owned by it (Step 8) |
| Plaintext passwords in git | the Secret manifest is for local use only, per its own header |
| No MySQL HA, backups, TLS or NetworkPolicy | listed as deliberately left out in `NOTES.md` |

## Discussion questions

1. Why can a Deployment scale MySQL badly, and what does the StatefulSet give you instead? What would happen with `replicas: 2` on this StatefulSet?
2. The init script lives in a ConfigMap, yet editing the ConfigMap does not change the databases. Why, and what are two ways to apply a schema change to a running database?
3. Why does each app Deployment take one key from the Secret with `secretKeyRef` instead of using `envFrom` like MySQL does?
4. The `wait-for-mysql` initContainer checks the port. What does it not check, and how would a failure in that gap look?
5. Compare the failure modes of this stack with walkthrough 03: which things became safer, and which became possible to lose for good?

## Exercises

1. List every `kubectl` command from this walkthrough that would have failed against the H2 stack, and why.
2. Change `MYSQL_DATABASE` in the order ConfigMap to `part_inventory_db`, restart the order service, and read the error. Restore it.
3. Add a third database and user to the init script. Show that it does not appear until the volume is reset, then find a way to create it **without** wiping the data.
4. Connect from your laptop with a port-forward to MySQL (`kubectl port-forward svc/mysql 3306:3306`, which works through a headless Service to its single Pod) and inspect the tables with a local client.
5. Scale `part-order-service` to 3 and place orders in a loop while deleting Pods. Is any order lost? Is any returned twice?
6. Write a `CronJob` that runs `mysqldump` against both databases to a second PVC, and prove you can restore from it after a Step 8 wipe.
7. Run this stack and the walkthrough 03 stack at the same time and list every name that is the same and every name that differs.
8. Rebuild all eleven manifests from memory in an empty folder, then diff each against the reference:

   ```bash
   diff -r ~/k8s-lab/part-order-app-mysql /path/to/part-order-app-java/k8s-with-mysql --exclude=NOTES.md
   ```

## What's next

You now have the two basic deployment shapes: state in the Pod, and state in a shared database.
Next come the topics outside basic Kubernetes (Helm, GitOps, Ingress and Gateway API, a MySQL operator, monitoring, service mesh and tracing). Those are not written yet; the series index is at [../../README.md](../../README.md).
