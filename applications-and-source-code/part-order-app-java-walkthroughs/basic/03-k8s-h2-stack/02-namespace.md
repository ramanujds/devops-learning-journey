# Step 2 — The Namespace

> [Back to index](README.md) · Previous: [Step 1](01-concepts.md) · Next: [Step 3 — ConfigMaps](03-configmaps.md)

## Goal

Create the `part-order-app` Namespace that every other object in the stack lives in.

## Why this matters

Without a Namespace everything lands in `default`, mixed with whatever else is
there. A dedicated Namespace gives you three things you will use immediately:
**teardown in one command** (deleting a Namespace deletes everything inside),
**name isolation** (the same Service name can exist in another stack's
Namespace without clashing, which is how the four variants in the repo coexist),
and a scope for later controls such as quotas and RBAC. It is also the first
thing that has to exist: applying a Deployment into a Namespace that is not there yet fails.

Background: [10.namespaces.md](../../../../kubernetes/kubernetes-intro/10.namespaces.md).

## 1. Write the manifest

Create `namespace.yaml` in `~/k8s-lab/part-order-app/`:

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: part-order-app
  labels:
    env: dev
```

- `apiVersion: v1`, `kind: Namespace` is a core object, so no group prefix.
- `labels` are free-form tags. `env: dev` costs nothing now and lets you select or report on environments later.

## 2. Apply it

```bash
kubectl apply -f namespace.yaml
```

## Try it

```bash
kubectl get ns part-order-app --show-labels
kubectl get all -n part-order-app
```

Expected:

```text
NAME             STATUS   AGE   LABELS
part-order-app   Active   5s    env=dev,kubernetes.io/metadata.name=part-order-app

No resources found in part-order-app namespace.
```

Every `kubectl get/describe/logs` for this stack needs `-n part-order-app`. To stop retyping it for this terminal session:

```bash
kubectl config set-context --current --namespace=part-order-app
```

This changes your current context, so undo it afterwards with
`kubectl config set-context --current --namespace=default`. The rest of this
walkthrough keeps writing `-n part-order-app` so each command is unambiguous.

Run `kubectl apply -f namespace.yaml` a second time and read the output: `unchanged`. Apply is declarative and safe to repeat.

## Checkpoint

<details>
<summary>Full <code>namespace.yaml</code></summary>

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: part-order-app
  labels:
    env: dev
```

</details>

This matches [namespace.yaml](../../../part-order-app-java/k8s/namespace.yaml) exactly.

## Common mistakes

| Symptom | Cause | Fix |
| --- | --- | --- |
| `error: unable to recognize ... no matches for kind` | typo in `kind` or `apiVersion` | `kubectl api-resources \| grep -i namespace` shows the valid values |
| `kubectl get pods` shows nothing after later steps | looking in `default` | add `-n part-order-app` |
| Namespace stuck in `Terminating` after a delete | something inside has a finalizer or is still shutting down | wait; `kubectl get all -n part-order-app` shows what remains |

Next: **[Step 3 — ConfigMaps](03-configmaps.md)**.
