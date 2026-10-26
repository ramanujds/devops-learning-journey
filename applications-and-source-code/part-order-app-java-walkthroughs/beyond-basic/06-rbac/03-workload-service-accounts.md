# Step 3 — Workload ServiceAccounts: One Identity Per Service, No Token

> [Back to index](README.md) · Previous: [Step 2](02-baseline.md) · Next: [Step 4 — A Role for Developers](04-role-for-developers.md)

## Goal

Give each service its own ServiceAccount with the token switched off, move both Deployments onto them, and prove the credential is gone while the app still works.

## Why this matters

Sharing `default` has two costs. The first is the unused token from Step 2. The
second is that `default` is one identity for everything in the namespace: if you
ever need to give *one* workload API access, binding a Role to `default` hands the
same access to all of them. A ServiceAccount per workload makes every grant
specific, and makes audit logs readable (`system:serviceaccount:part-order-app:part-order-service`
instead of `default`).

Switching the token off is a separate setting, `automountServiceAccountToken: false`.
It can sit on the ServiceAccount (every Pod using it inherits it) or on the Pod
itself (the Pod's value wins). On the account is the safer default, because a
Pod author has to opt back in deliberately.

You are removing a capability the services never used: neither Spring Boot service
talks to the Kubernetes API, so nothing should break, and you will verify that.

## 1. The ServiceAccounts

Make a working folder and create `workload-serviceaccounts.yaml`:

```bash
mkdir -p ~/rbac-lab && cd ~/rbac-lab
```

```yaml
# One identity per workload instead of the shared "default" ServiceAccount.
# Neither service calls the Kubernetes API, so neither gets a token mounted.
apiVersion: v1
kind: ServiceAccount
metadata:
  name: part-inventory-service
  namespace: part-order-app
automountServiceAccountToken: false
---
apiVersion: v1
kind: ServiceAccount
metadata:
  name: part-order-service
  namespace: part-order-app
automountServiceAccountToken: false
```

```bash
kubectl apply -f workload-serviceaccounts.yaml
kubectl get serviceaccounts -n part-order-app
```

Expected: `default`, `part-inventory-service` and `part-order-service`. The new accounts exist but no Pod uses them yet.

## 2. Move the Deployments onto them

```bash
kubectl set serviceaccount deploy/part-inventory-service part-inventory-service -n part-order-app
kubectl set serviceaccount deploy/part-order-service part-order-service -n part-order-app
kubectl rollout status deploy/part-order-service -n part-order-app
kubectl rollout status deploy/part-inventory-service -n part-order-app
```

Changing the Pod template triggers a rolling update. If the inventory Deployment was scaled up by the HPA in walkthrough 03, expect the second `rollout status` to take a few minutes.

## Try it

```bash
kubectl get pods -n part-order-app -o custom-columns=NAME:.metadata.name,SA:.spec.serviceAccountName
kubectl exec -n part-order-app deploy/part-order-service -- ls /var/run/secrets/kubernetes.io/serviceaccount/
```

Expected: every Pod shows its own account (`part-inventory-service` or `part-order-service`; an old Pod that is still terminating may briefly show `default`), and the `ls` fails:

```text
ls: cannot access '/var/run/secrets/kubernetes.io/serviceaccount/': No such file or directory
command terminated with exit code 2
```

The credential from Step 2 is gone. Prove the application did not need it:

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:30080/
```

Expected: `200`. The services never used the Kubernetes API, so nothing noticed the token was missing.

## Making it durable

`kubectl set serviceaccount` edited the live Deployment. Re-applying the original
`deployment.yaml` with `kubectl apply` happens to leave that field alone, but the
repo's manifest still says nothing about a ServiceAccount, so any tool that
reconciles the cluster **to the file** (a GitOps controller, a Helm template with no
such value) will put `default` back. The durable fix is one line in each
Deployment's Pod template:

```yaml
    spec:
      serviceAccountName: part-order-service
```

Exercise 1 in the recap does this. Until it is in the manifest, treat Step 3 as a live change.

## Checkpoint

This matches [manifests/workload-serviceaccounts.yaml](manifests/workload-serviceaccounts.yaml) exactly.

<details>
<summary>Full <code>workload-serviceaccounts.yaml</code></summary>

```yaml
# One identity per workload instead of the shared "default" ServiceAccount.
# Neither service calls the Kubernetes API, so neither gets a token mounted.
apiVersion: v1
kind: ServiceAccount
metadata:
  name: part-inventory-service
  namespace: part-order-app
automountServiceAccountToken: false
---
apiVersion: v1
kind: ServiceAccount
metadata:
  name: part-order-service
  namespace: part-order-app
automountServiceAccountToken: false
```

</details>


## Common mistakes

| Symptom | Cause | Fix |
| --- | --- | --- |
| Pods still show `default` | the `set serviceaccount` rollout has not finished, or the command was not run | `kubectl rollout status`, then re-check |
| `error looking up service account part-order-app/part-order-service: not found` | the ServiceAccount does not exist yet; Pods are rejected | apply `workload-serviceaccounts.yaml` first |
| Token directory still mounted | the Pod (or an old Pod) sets `automountServiceAccountToken: true`, which overrides the account | remove it from the Pod spec; check `kubectl get pod <name> -o yaml` |
| Rollout is slow | inventory has several replicas from the HPA | wait, or `kubectl get hpa -n part-order-app` to see why |

Next: **[Step 4 — A Role for Developers](04-role-for-developers.md)**.
