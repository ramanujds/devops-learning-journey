# Step 10 — Recap and Exercises

> [Back to index](README.md) · Previous: [Step 9](09-operating-the-stack.md)

## Quick reference

| Concept | Where it lives |
| --- | --- |
| Isolation and one-command teardown | [namespace.yaml](../../../part-order-app-java/k8s/namespace.yaml) |
| Profile and inventory address, outside the image | `configmap.yaml` in each service folder, consumed by `envFrom` |
| Keep N Pods alive, rolling updates, rollback | `deployment.yaml`, `kubectl rollout status/undo/restart` |
| Not-ready vs stuck | `readinessProbe` and `livenessProbe` on `/actuator/health/{readiness,liveness}` |
| Scheduler reservation and ceilings | `resources.requests` and `resources.limits` |
| Stable name for changing Pods | `service.yaml`; DNS `<service>.<namespace>.svc.cluster.local` |
| Reach the UI from the laptop | order Service `NodePort` 30080 |
| Autoscaling | [hpa.yaml](../../../part-order-app-java/k8s/part-inventory-service/hpa.yaml), needs metrics-server |
| Design notes and request flow | [k8s/NOTES.md](../../../part-order-app-java/k8s/NOTES.md) |

## Gotchas

| Gotcha | Where it comes from |
| --- | --- |
| Autoscaling the inventory service breaks data consistency | each Pod has its own in-memory H2; stock differs per replica (Step 8) |
| HPA scales an idle app because memory reads about 100% of its request | a JVM idles near 250 Mi against a 256 Mi request, with an 80% target (Step 8) |
| `NOTES.md` says the HPA is left out, but `hpa.yaml` is in the folder | the two disagree; `kubectl apply -f part-inventory-service/` applies the HPA |
| Inventory Service type: comment says internal only, working copy says `NodePort` | a local edit; `ClusterIP` is the committed value and the design intent (Step 6) |
| Rebuilt and pushed `:latest`, nothing changed | no manifest diff, so no rollout; use `rollout restart` (Step 9) |
| ConfigMap edit has no effect | env vars are read at container start (Step 3) |
| Order returns 500 while health is `UP` | `INVENTORY_SERVICE_URL` wrong; health does not check downstream services (Step 7) |
| `kubectl get pods` shows nothing | wrong Namespace, add `-n part-order-app` (Step 2) |
| `provided port is already allocated` for 30080 | another stack uses that NodePort; the four repo variants use different ones |
| Logs flooded with SQL | `dev` profile enables `org.hibernate.SQL` DEBUG |

## Discussion questions

1. The Deployment selector and Pod labels must match. What exactly breaks if they do not, and where would you see it?
2. Why does `replicas: 1` appear with a comment in both Deployments, and what single change would make 2 replicas safe?
3. A readiness probe fails on one Pod of three. What happens to traffic, and what happens to the Pod? Now repeat for a liveness failure.
4. Memory `requests` is 256 Mi and `limits` is 512 Mi. The HPA measures against which one, and why does that choice surprise people?
5. Why is `INVENTORY_SERVICE_URL` a ConfigMap value instead of being built into the image?

## Exercises

1. Compare the claims in `NOTES.md` ("Deliberately left out: HPA") with the files in the folder, and decide which should change. Write the one-line fix for whichever you choose.
2. Run `kubectl diff -f part-inventory-service/deployment.yaml` after editing `replicas` to 2. Read the output before applying, then revert.
3. Make the HPA behave: change either the memory request in `deployment.yaml` or the memory `averageUtilization` in `hpa.yaml` so an idle app does not scale out. Apply, wait five minutes, and show the replica count stays at 1.
4. Change the inventory Service to `ClusterIP`. Which of your earlier `curl` commands stop working, and what replaces them?
5. Put a second copy of the whole stack in another Namespace (change `namespace:` in every file, and the NodePort). Show that both work at the same time, and explain why `INVENTORY_SERVICE_URL` needed no change.
6. Delete `part-order-service` Pod while placing orders in a loop. Observe the gap, then add `replicas: 2` to the order Deployment and repeat. What is lost, and what is now inconsistent? (Hint: the orders list lives in each Pod's H2.)
7. Rebuild all eight manifests from memory in an empty folder, then diff each against the reference:

   ```bash
   diff -r ~/k8s-lab/part-order-app /path/to/part-order-app-java/k8s --exclude=NOTES.md
   ```

## What's next

The recurring problem in this walkthrough is state that lives inside a Pod.
Next: walkthrough 04, `k8s-with-mysql/`, which moves the data into a shared MySQL StatefulSet so replicas become safe. It is not written yet; the series index is at [../../README.md](../../README.md).
