# Step 4 — The Inventory Deployment

> [Back to index](README.md) · Previous: [Step 3](03-configmaps.md) · Next: [Step 5 — Probes and Resources](05-probes-and-resources.md)

## Goal

Run `part-inventory-service` in the cluster with a Deployment that pulls the image, opens its port, loads the ConfigMap, and heals itself.

## Why this matters

You never run a bare Pod in practice: if it dies, nothing brings it back. A
Deployment says "there should always be N of these", and a controller keeps
making it true. It does that through a ReplicaSet, which is why a Pod's name
looks like `part-inventory-service-76b955c694-h2tpf`: Deployment name, ReplicaSet
hash, Pod suffix. The same indirection is what makes rolling updates and rollbacks possible later (Step 9).

Three details here carry most of the weight:

- **The label contract.** `selector.matchLabels` must equal the Pod template's `labels`. That is the only way the Deployment knows which Pods are its own, and the same label is what the Service will select in Step 6.
- **`replicas: 1`** is a decision, not a default. With in-memory H2 each replica holds its own copy of the data, so two replicas would disagree about stock. The comment in the file records that reasoning for the next reader.
- **A named port.** `name: http` lets probes and Services say `port: http` instead of repeating `8080`. Changing the container port later then touches one line.

## 1. Metadata, replicas and the label contract

Create `part-inventory-service/deployment.yaml`:

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
```

`metadata.labels` (on the Deployment) are for you to query with; `template.metadata.labels` (on the Pods) are the ones that must match the selector.

## 2. The container

Append, indented under `template:` at the same level as `metadata:`:

```yaml
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
```

- `imagePullPolicy: Always` pulls from Docker Hub on every Pod start. [Walkthrough 02, Step 7](../02-build-docker-images/07-images-in-kubernetes.md) explains why your local `:local` builds are ignored.
- `envFrom` turns every key of the ConfigMap into an environment variable, so `SPRING_PROFILES_ACTIVE=dev` reaches Spring with no further wiring.

## 3. Apply it

```bash
kubectl apply -f part-inventory-service/deployment.yaml
```

## Try it

```bash
kubectl get pods -n part-order-app -w          # Ctrl+C when Running
kubectl get deployment,replicaset,pods -n part-order-app
```

Expected: a Deployment `1/1`, one ReplicaSet, one Pod `Running`. Look at the Pod's `READY` column, then check the application log at once:

```bash
kubectl logs -n part-order-app deploy/part-inventory-service | tail -5
```

You will see `READY 1/1` within a few seconds, while the log shows Spring Boot **still starting** (the "Started ..." line arrives several seconds later). This Deployment has no probes, so Kubernetes treats the Pod as ready as soon as the container process exists. Step 5 fixes that.

Now watch self-healing:

```bash
kubectl delete pod -n part-order-app -l app=part-inventory-service
kubectl get pods -n part-order-app
```

Expected: the old Pod `Terminating` and a new one with a different suffix already `Running`. You deleted a Pod; the ReplicaSet saw 0 of 1 and created another.

## Checkpoint

<details>
<summary>Full <code>part-inventory-service/deployment.yaml</code> (after Step 4)</summary>

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
```

</details>

This is an intermediate version; Step 5 adds probes and resources and ends matching the repo file.

## Common mistakes

| Symptom | Cause | Fix |
| --- | --- | --- |
| `selector does not match template labels` | `matchLabels` and the Pod template labels differ | make them identical |
| Pod `ImagePullBackOff` | no internet, Docker Hub rate limit, or a typo in the image name | `kubectl describe pod -n part-order-app` and read Events |
| Pod `CreateContainerConfigError` | `envFrom` names a ConfigMap that does not exist | apply Step 3, check the name matches exactly |
| Pod `Running` but app errors | profile or config wrong | `kubectl logs -n part-order-app deploy/part-inventory-service` |
| Pod `Pending` | not enough CPU or memory in Docker Desktop | raise resources (walkthrough 01, Step 2) |

Next: **[Step 5 — Probes and Resources](05-probes-and-resources.md)**.
