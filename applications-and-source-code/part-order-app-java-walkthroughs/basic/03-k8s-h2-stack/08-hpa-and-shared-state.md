# Step 8 — The HPA, and What It Does to In-Memory State

> [Back to index](README.md) · Previous: [Step 7](07-order-service.md) · Next: [Step 9 — Operating the Stack](09-operating-the-stack.md)

## Goal

Add a HorizontalPodAutoscaler for the inventory service, watch it scale, and see why autoscaling and per-Pod H2 do not mix.

## Why this matters

An HPA changes `replicas` for you based on observed load. Its rule is roughly
`desired = ceil(current * (current metric / target))`, evaluated per metric, and it
takes the largest answer. "Current metric" for CPU and memory is **utilisation as a
percentage of the Pod's `requests`**, which is why Step 5 set requests, and why
the HPA needs metrics-server from [walkthrough 01, Step 5](../01-docker-desktop-cluster/05-metrics-server.md).
Without it the HPA shows `<unknown>` and never acts.

Here is the catch, and the reason [k8s/NOTES.md](../../../part-order-app-java/k8s/NOTES.md)
says HPA is "not meaningful at 1 replica with in-memory, per-pod state":

1. Each inventory Pod keeps its own H2 database, seeded from `data.sql` at boot.
2. The Service sends each request to **any** Ready Pod.
3. A new replica starts with fresh stock (100), regardless of the orders already placed.
4. So after scaling out, the same question returns different answers depending on which Pod replies.

That is not a Kubernetes failure; Kubernetes did exactly what it was asked. The
application's state is simply not shared. The fix is a shared database
(walkthrough 04) and then autoscaling becomes safe.

A second thing you will observe: an idle Spring Boot JVM sits near 250 Mi against a
256 Mi memory request, so memory utilisation reads about **100% against the 80% target**
even with no traffic. The HPA therefore scales out with nobody using the app.

## 1. The target and the bounds

Create `part-inventory-service/hpa.yaml`:

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: part-inventory-service-hpa
  namespace: part-order-app
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: part-inventory-service
  minReplicas: 1
  maxReplicas: 5
```

`scaleTargetRef` points at the Deployment to scale, by name. `1` to `5` is the range the HPA may choose within.

## 2. The metrics

Append:

```yaml
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
    - type: Resource
      resource:
        name: memory
        target:
          type: Utilization
          averageUtilization: 80
```

## 3. The scaling behaviour

Append:

```yaml
  behavior:
    scaleDown:
      stabilizationWindowSeconds: 300   # wait 5 min before scaling down
    scaleUp:
      stabilizationWindowSeconds: 0     # scale up immediately
```

Scale up fast, scale down slowly, so a brief dip in load does not remove capacity just before the next spike.

## 4. Apply and watch

```bash
kubectl apply -f part-inventory-service/hpa.yaml
kubectl get hpa -n part-order-app -w
```

## Try it

Within a minute the targets show real numbers instead of `<unknown>`:

```text
NAME                         REFERENCE                           TARGETS                         MINPODS   MAXPODS   REPLICAS
part-inventory-service-hpa   Deployment/part-inventory-service   cpu: 8%/70%, memory: 103%/80%   1         5         1
```

Then it scales. In a test of this stack the replica count went 1, 2, 4, 5 over
about four minutes, with memory staying near 97-103%. Read why:

```bash
kubectl describe hpa part-inventory-service-hpa -n part-order-app | tail -8
kubectl get pods -n part-order-app
```

The event says `New size: 2; reason: memory resource utilization (percentage of request) above target`.

Now demonstrate the problem. Place an order so one Pod's stock drops, then ask the Service for the stock repeatedly, from inside the cluster:

```bash
curl -s -X POST http://localhost:30080/api/part-orders/place-order \
  -H "Content-Type: application/json" -d '{"sku":"SEAT-SAFE45-001","quantity":2}'

kubectl run probe -n part-order-app --rm -i --restart=Never --image=curlimages/curl --command -- sh -c \
  'sleep 3; for i in 1 2 3 4 5 6 7 8 9 10 11 12; do curl -s http://part-inventory-service:8080/api/parts | grep -o "\"sku\":\"SEAT-SAFE45-001\",\"stock\":[0-9]*"; done' 2>/dev/null | grep stock | sort | uniq -c
```

The `grep stock` drops the `pod "probe" deleted` line that `kubectl run --rm` prints, and `2>/dev/null` hides its session banner. The `sleep 3` matters: `kubectl run -i` attaches a moment after the container starts, and without the pause the first few lines of output can be lost, leaving you with fewer than 12 samples.

Expected (the split varies):

```text
   8 "sku":"SEAT-SAFE45-001","stock":100
   4 "sku":"SEAT-SAFE45-001","stock":98
```

One address, two answers. The Pod that handled the order says 98, the others still say 100. A real user would see stock jump up and down on refresh.

Restore the intended state: remove the HPA and return to one replica.

```bash
kubectl delete hpa part-inventory-service-hpa -n part-order-app
kubectl scale deploy/part-inventory-service -n part-order-app --replicas=1
```

Deleting the HPA first matters. If you scale the Deployment while the HPA exists, the HPA scales it right back.

## Checkpoint

<details>
<summary>Full <code>part-inventory-service/hpa.yaml</code></summary>

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: part-inventory-service-hpa
  namespace: part-order-app
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: part-inventory-service
  minReplicas: 1
  maxReplicas: 5
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
    - type: Resource
      resource:
        name: memory
        target:
          type: Utilization
          averageUtilization: 80
  behavior:
    scaleDown:
      stabilizationWindowSeconds: 300   # wait 5 min before scaling down
    scaleUp:
      stabilizationWindowSeconds: 0     # scale up immediately
```

</details>

This matches [part-inventory-service/hpa.yaml](../../../part-order-app-java/k8s/part-inventory-service/hpa.yaml) exactly. The file exists in the repo even though `NOTES.md` lists the HPA under "deliberately left out"; the two disagree, which is the subject of the first exercise.

## Common mistakes

| Symptom | Cause | Fix |
| --- | --- | --- |
| `TARGETS` shows `<unknown>/70%` | metrics-server missing or unhealthy, or the Deployment has no `resources.requests` | walkthrough 01 Step 5; check `kubectl top pods`; keep the requests from Step 5 |
| Deployment scales back up after you set `replicas: 1` | the HPA still owns the replica count | delete the HPA, then scale |
| Scales out with no traffic | memory request (256 Mi) is close to the JVM's idle usage, so utilisation exceeds 80% | raise the request or the memory target; this is exercise 3 |
| Stock numbers differ between requests | per-Pod H2 state with more than one replica | one replica for this stack, or a shared database (walkthrough 04) |
| `no matches for kind "HorizontalPodAutoscaler" in version "autoscaling/v2"` | very old cluster | use a supported Kubernetes version |

Next: **[Step 9 — Operating the Stack](09-operating-the-stack.md)**.
