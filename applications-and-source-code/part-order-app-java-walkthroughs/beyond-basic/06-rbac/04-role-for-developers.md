# Step 4 — A Role for Developers: Rules Derived from Tasks

> [Back to index](README.md) · Previous: [Step 3](03-workload-service-accounts.md) · Next: [Step 5 — Bindings and Groups](05-bindings-and-groups.md)

## Goal

Write the `app-developer` Role by starting from what a developer actually does on this stack, finding the API call behind each task, and turning those calls into rules.

## Why this matters

The usual way to write a Role is to guess a long list of verbs and widen it every
time something returns `Forbidden`. That ends at `*`. The reliable way is the other
direction: list the **tasks**, find the **API request** each one makes, and grant
exactly those. `kubectl` will tell you the request if you ask for verbosity 6.

The rule format has three parts you already met in Step 1: apiGroup, resource, verb.
There is one more detail that catches people. Some actions are **subresources**
with their own permission: reading logs is `get` on `pods/log`, and opening a shell is `create` on
`pods/exec`. Granting `get` on `pods` does **not** include either.

## 1. List the tasks and find their API calls

Ask `kubectl` what it sends (this output is from your admin identity; it only shows requests, it is not an RBAC test):

```bash
kubectl get pods -n part-order-app -v=6 2>&1 | grep -E 'GET|PATCH|POST' | sed 's/^.*\] //' | cut -c1-140
kubectl logs deploy/part-order-service -n part-order-app --tail=1 -v=6 2>&1 | grep -E 'GET' | sed 's/^.*\] //' | cut -c1-140
kubectl rollout restart deploy/part-order-service -n part-order-app -v=6 2>&1 | grep -E 'GET|PATCH' | sed 's/^.*\] //' | cut -c1-140
```

You will see lines such as `"Response" verb="GET" url=".../namespaces/part-order-app/pods?limit=500"`, a `PATCH` on `.../deployments/part-order-service` and a `GET` on `.../pods/<name>/log`. Reading them gives this table (the `exec` row comes from the same technique):

| Task | Command | What RBAC checks |
| --- | --- | --- |
| See the stack | `kubectl get pods,svc,deploy,hpa` | `list` on pods, services, deployments, horizontalpodautoscalers |
| Follow events | `kubectl get events` | `list` on events |
| Read logs | `kubectl logs deploy/x` | `get` deployments, `list` pods, `get` on `pods/log` |
| Shell into a Pod | `kubectl exec deploy/x -- ...` | `get` deployments, `list` pods, `create` on `pods/exec` |
| Restart a service | `kubectl rollout restart` | `get` and `patch` deployments |
| Roll back | `kubectl rollout undo` | `get` deployments, `list` replicasets, `patch` deployments |
| Edit config | `kubectl edit configmap` | `get`, `update`/`patch` configmaps |
| Remove a stuck Pod | `kubectl delete pod` | `delete` pods |

Two things are left out on purpose. **Secrets** (a developer debugging the app does
not need the database password) and **creating or deleting Deployments** (that is the
pipeline's job, Step 8). Also absent: any RBAC object, so a developer cannot grant
themselves more.

## 2. Generate the skeleton, then write the file

`kubectl create role --dry-run=client -o yaml` produces correct YAML for one rule without sending anything:

```bash
kubectl create role demo --verb=get,list,watch --resource=pods,services -n part-order-app --dry-run=client -o yaml
```

Use it to avoid typos in apiGroup spelling, then assemble the real file. Create `app-developer-role.yaml`:

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: app-developer
  namespace: part-order-app
rules:
  # look at the stack
  - apiGroups: [""]
    resources: ["pods", "services", "configmaps", "events"]
    verbs: ["get", "list", "watch"]
  - apiGroups: ["apps"]
    resources: ["deployments", "replicasets"]
    verbs: ["get", "list", "watch"]
  - apiGroups: ["autoscaling"]
    resources: ["horizontalpodautoscalers"]
    verbs: ["get", "list", "watch"]
  # debug: logs, a shell in a Pod, delete a stuck Pod
  - apiGroups: [""]
    resources: ["pods/log"]
    verbs: ["get"]
  - apiGroups: [""]
    resources: ["pods/exec"]
    verbs: ["create"]
  - apiGroups: [""]
    resources: ["pods"]
    verbs: ["delete"]
  # change: edit config, restart or roll back a Deployment
  - apiGroups: [""]
    resources: ["configmaps"]
    verbs: ["update", "patch"]
  - apiGroups: ["apps"]
    resources: ["deployments"]
    verbs: ["update", "patch"]
  # secrets, roles and bindings are deliberately absent
```

Read it in four groups: look (get/list/watch), debug (logs, exec, delete Pod), change (configmaps, deployments) and the comment at the end naming what is deliberately missing. `replicasets` is read-only because `rollout undo` needs to read them but never write them.

## 3. Apply it, and see that nothing happens

```bash
cd ~/rbac-lab
kubectl apply -f app-developer-role.yaml
kubectl auth can-i list pods -n part-order-app --as=alice --as-group=part-order-devs
```

Expected: `no`. The Role exists, but no one is bound to it, so `alice` still has no permissions. Step 5 binds it.

## Try it

Check that the Role object holds what you wrote, and compare it with a built-in one for scale:

```bash
kubectl describe role app-developer -n part-order-app
kubectl get clusterrole view -o jsonpath='{.rules[*].resources[*]}' | wc -w
```

Expected: a table of resources and verbs matching the file, and a count of about sixty resource names (61 on Kubernetes v1.36.1) for the built-in `view` role, against the nine you chose.

## Checkpoint

This matches [manifests/app-developer-role.yaml](manifests/app-developer-role.yaml) exactly.

<details>
<summary>Full <code>app-developer-role.yaml</code></summary>

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: app-developer
  namespace: part-order-app
rules:
  # look at the stack
  - apiGroups: [""]
    resources: ["pods", "services", "configmaps", "events"]
    verbs: ["get", "list", "watch"]
  - apiGroups: ["apps"]
    resources: ["deployments", "replicasets"]
    verbs: ["get", "list", "watch"]
  - apiGroups: ["autoscaling"]
    resources: ["horizontalpodautoscalers"]
    verbs: ["get", "list", "watch"]
  # debug: logs, a shell in a Pod, delete a stuck Pod
  - apiGroups: [""]
    resources: ["pods/log"]
    verbs: ["get"]
  - apiGroups: [""]
    resources: ["pods/exec"]
    verbs: ["create"]
  - apiGroups: [""]
    resources: ["pods"]
    verbs: ["delete"]
  # change: edit config, restart or roll back a Deployment
  - apiGroups: [""]
    resources: ["configmaps"]
    verbs: ["update", "patch"]
  - apiGroups: ["apps"]
    resources: ["deployments"]
    verbs: ["update", "patch"]
  # secrets, roles and bindings are deliberately absent
```

</details>


## Common mistakes

| Symptom | Cause | Fix |
| --- | --- | --- |
| `kubectl logs` forbidden although `get pods` works | logs are the `pods/log` subresource | add `pods/log` with `get` |
| `kubectl exec` forbidden | `pods/exec` needs `create` | add `pods/exec` with `create` (grant it sparingly: a shell can read mounted secrets) |
| `rollout undo` forbidden | it reads ReplicaSets | add `replicasets` with `get`, `list` |
| `forbidden: ... in API group "apps"` | the resource was put under `apiGroups: [""]` | `deployments` and `replicasets` live in `apps` |
| `can-i get pods/log` answers `yes` for the wrong reason | `can-i` reads `pods/log` as "the Pod named `log`" | use `can-i get pods --subresource=log` |

Next: **[Step 5 — Bindings and Groups](05-bindings-and-groups.md)**.
