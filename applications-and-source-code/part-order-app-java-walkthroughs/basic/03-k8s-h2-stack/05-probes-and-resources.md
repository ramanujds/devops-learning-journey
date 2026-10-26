# Step 5 — Probes and Resources

> [Back to index](README.md) · Previous: [Step 4](04-inventory-deployment.md) · Next: [Step 6 — Inventory Service](06-inventory-service.md)

## Goal

Add readiness and liveness probes and CPU/memory requests and limits, completing the inventory Deployment.

## Why this matters

**Probes** answer two different questions, and mixing them up causes outages:

| Probe | Question | On failure |
| --- | --- | --- |
| Readiness | "Should traffic be sent to this Pod right now?" | Pod is removed from Service endpoints; not restarted |
| Liveness | "Is this process stuck beyond recovery?" | Container is killed and restarted |

A Spring Boot app takes 10 to 40 seconds to start. Without a readiness probe,
traffic and rolling updates treat it as ready the moment the process exists
(what you saw in Step 4), so users get errors during every deploy. Without a
sensible liveness delay, a slow start gets killed and restarted in a loop. Both
probes call endpoints that exist because `management.endpoint.health.probes.enabled`
is turned on in the app: `/actuator/health/readiness` and `/actuator/health/liveness`.

**Requests and limits** are a contract with the scheduler and the kernel:

| Field | Meaning | If exceeded |
| --- | --- | --- |
| `requests` | what the scheduler reserves for this Pod | not applicable; it decides where the Pod fits |
| `limits.cpu` | hard ceiling | the container is throttled |
| `limits.memory` | hard ceiling | the container is killed (`OOMKilled`) |

They matter twice more later: the HPA in Step 8 computes utilisation as a
percentage **of the request**, and a Pod with no requests cannot be autoscaled on CPU or memory at all.

## 1. Readiness probe

Append under the container, at the same level as `envFrom:`:

```yaml
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: http
            initialDelaySeconds: 20
            periodSeconds: 10
            failureThreshold: 3
```

Wait 20 seconds before the first check, then check every 10 seconds, and declare the Pod not ready after 3 consecutive failures. `port: http` refers to the named port from Step 4.

## 2. Liveness probe

Append:

```yaml
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: http
            initialDelaySeconds: 30
            periodSeconds: 15
            failureThreshold: 3
```

The liveness delay (30 s) is deliberately longer than readiness (20 s) so a slow start is never mistaken for a hang.

## 3. Resources

Append:

```yaml
          resources:
            requests:
              cpu: 100m
              memory: 256Mi
            limits:
              cpu: 500m
              memory: 512Mi
```

`100m` is a tenth of a CPU core. The NOTES for this stack call these
"conservative placeholders, not load-tested", which is honest and worth repeating to trainees.

## 4. Re-apply and watch

```bash
kubectl apply -f part-inventory-service/deployment.yaml
kubectl get pods -n part-order-app -w
```

The change is to the Pod template, so Kubernetes performs a **rolling update**: it starts a new Pod, waits for it to become Ready, and only then stops the old one.

## Try it

Watch `READY` on the new Pod: it shows `0/1` while `STATUS` is already `Running`, and flips to `1/1` after roughly 30-40 seconds. Compare that with Step 4.

```bash
kubectl describe pod -n part-order-app -l app=part-inventory-service | sed -n '/Liveness/,/Environment/p'
kubectl port-forward -n part-order-app deploy/part-inventory-service 8081:8080
```

In a second terminal:

```bash
curl http://localhost:8081/actuator/health/readiness
curl http://localhost:8081/api/parts | head -c 200
```

Expected: `{"status":"UP"}` and a JSON list of parts. Stop the port-forward with Ctrl+C.

See the resources the scheduler recorded:

```bash
kubectl describe node | grep -A8 "Allocated resources"
```

## Checkpoint

<details>
<summary>Full <code>part-inventory-service/deployment.yaml</code></summary>

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: part-inventory-service
  namespace: part-order-app
  labels:
    app: part-inventory-service
spec:
  # single replica: dev profile uses per-pod H2 in-memory DB, so state isn't
  # shared across replicas. Bump this once a real shared DB is wired up.
  replicas: 1
  selector:
    matchLabels:
      app: part-inventory-service
  template:
    metadata:
      labels:
        app: part-inventory-service
    spec:
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

This matches [part-inventory-service/deployment.yaml](../../../part-order-app-java/k8s/part-inventory-service/deployment.yaml) exactly.

## Common mistakes

| Symptom | Cause | Fix |
| --- | --- | --- |
| Pod restarts repeatedly, `Liveness probe failed` in events | `initialDelaySeconds` shorter than the app's start time | raise the delay; check boot time with `kubectl logs` |
| Pod stays `0/1` forever | readiness path wrong (404) or probes not enabled in the app | `kubectl describe pod`, read the `Readiness probe failed` event; test the path with port-forward |
| `OOMKilled` in `kubectl describe pod` | memory limit below what the JVM needs | raise `limits.memory` |
| Pod `Pending`, `Insufficient memory` | requests larger than free node memory | lower requests or raise Docker Desktop resources |
| Wrong indentation error on apply | probes not aligned with `image:` | all container fields share the same indent under `- name:` |

Next: **[Step 6 — Inventory Service](06-inventory-service.md)**.
