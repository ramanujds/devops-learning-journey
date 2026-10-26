# Step 5 — Bindings and Groups: Granting the Role, Testing as Someone Else

> [Back to index](README.md) · Previous: [Step 4](04-role-for-developers.md) · Next: [Step 6 — Cluster Scope](06-cluster-scope.md)

## Goal

Bind `app-developer` to a group, bind the built-in `view` role to another, and test both by impersonation, including the one thing neither may touch: a Secret.

## Why this matters

A binding is the sentence "this subject holds that role, here". Two choices in it
matter in practice.

**Bind to a group, not a person.** The subject is a name matched against the
credential. If the RoleBinding names the group `part-order-devs`, then onboarding
a developer means issuing them a credential in that group, and leaving the team
means removing the credential, with no RoleBinding edited either time. A binding that
lists `alice` has to be found and edited for each change.

**Reuse built-in roles where they fit.** `view` already says "read most things in the
namespace". Writing your own for the same purpose would be longer and drift from
what the cluster expects. The binding below is the ClusterRole + RoleBinding pattern
from Step 1: the rules come from a cluster-wide role, and the reach is limited to one
namespace.

You will check all of this with `--as` (the user name) and `--as-group` (a group the
API server pretends the user is in). Impersonating is allowed for you because you are
the administrator. Without it you would need a credential for every test identity.

## 1. Two small shell helpers

These save repeating the flags and, unlike a shell variable, work in both bash and zsh. The flags go **before** the subcommand so that `exec ... -- command` keeps its own arguments intact:

```bash
as_alice() { kubectl --as=alice --as-group=part-order-devs "$@"; }
as_vera()  { kubectl --as=vera --as-group=part-order-viewers "$@"; }
```

## 2. Bind the developer Role

Create `app-developer-binding.yaml`:

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: app-developer-binding
  namespace: part-order-app
subjects:
  # a name taken from a client certificate's O= field, or from --as-group
  - kind: Group
    name: part-order-devs
    apiGroup: rbac.authorization.k8s.io
roleRef:
  kind: Role
  name: app-developer
  apiGroup: rbac.authorization.k8s.io
```

`subjects` says who; `roleRef` says what. The `roleRef` cannot be edited later (Kubernetes rejects `cannot change roleRef`), so a wrong one is fixed by deleting and recreating the binding.

```bash
cd ~/rbac-lab
kubectl apply -f app-developer-binding.yaml
```

## 3. Test it, then try to exceed it

```bash
as_alice auth can-i list pods -n part-order-app
as_alice auth can-i get pods --subresource=log -n part-order-app
as_alice auth can-i create pods --subresource=exec -n part-order-app
as_alice auth can-i patch deployments.apps -n part-order-app
as_alice auth can-i get secrets -n part-order-app
as_alice auth can-i create deployments.apps -n part-order-app
as_alice auth can-i create rolebindings.rbac.authorization.k8s.io -n part-order-app
as_alice auth can-i list pods -n default
```

Expected, in order: `yes`, `yes`, `yes`, `yes`, `no`, `no`, `no`, `no`. Now do real work as Alice instead of asking:

```bash
as_alice logs deploy/part-order-service -n part-order-app --tail=2
as_alice exec -n part-order-app deploy/part-order-service -- curl -s localhost:8080/actuator/health
as_alice rollout restart deploy/part-order-service -n part-order-app
as_alice rollout status deploy/part-order-service -n part-order-app
```

All four work. And the refusals:

```bash
as_alice get secrets -n part-order-app
as_alice delete deployment part-order-service -n part-order-app
as_alice get pods -n default
```

Expected, each: `Error from server (Forbidden): ... User "alice" cannot ... resource "..." in API group "..." in the namespace "..."`. The message names the user, verb, resource, apiGroup and namespace: it is a ready-made recipe for the missing rule.

## 4. The viewer

Create `app-viewer-binding.yaml`:

```yaml
# The built-in "view" ClusterRole, granted in this one namespace only.
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: app-viewer-binding
  namespace: part-order-app
subjects:
  - kind: Group
    name: part-order-viewers
    apiGroup: rbac.authorization.k8s.io
roleRef:
  kind: ClusterRole
  name: view
  apiGroup: rbac.authorization.k8s.io
```

```bash
kubectl apply -f app-viewer-binding.yaml
kubectl create secret generic demo-secret -n part-order-app --from-literal=password=s3cret
```

Run the same questions as `vera`:

```bash
as_vera auth can-i list pods -n part-order-app
as_vera auth can-i get pods --subresource=log -n part-order-app
as_vera auth can-i create pods --subresource=exec -n part-order-app
as_vera auth can-i patch deployments.apps -n part-order-app
as_vera auth can-i list rolebindings.rbac.authorization.k8s.io -n part-order-app
as_vera get secret demo-secret -n part-order-app
as_alice get secret demo-secret -n part-order-app
```

Expected: `yes`, `yes`, `no`, `no`, `no`, then two `Forbidden` errors. **Neither** Alice nor Vera can read `demo-secret`.

That is by design for `view`, which excludes Secrets. The built-in `edit` role, by contrast, grants read and write on Secrets and `pods/exec`; check with:

```bash
kubectl get clusterrole edit -o jsonpath='{range .rules[*]}{.resources}{" "}{.verbs}{"\n"}{end}' | grep secrets
```

This is the reason Step 4 wrote a custom role instead of binding `edit`: `edit` would have made every developer a reader of every password in the namespace.

## 5. The name is not the credential, the group is

```bash
kubectl auth can-i list pods -n part-order-app --as=vera
kubectl auth can-i list pods -n part-order-app --as=some-stranger --as-group=part-order-viewers
```

Expected: `no`, then `yes`. The RoleBinding names a **group**, so what counts is the group on the credential. `vera` without the group has nothing; a complete stranger in `part-order-viewers` gets read access. Group membership is therefore only as trustworthy as whoever issues the credentials (Step 7).

## Try it

Side by side, the full picture for each identity:

```bash
as_alice auth can-i --list -n part-order-app | grep -v -E '^\s+\[/|selfsubject'
as_vera auth can-i --list -n part-order-app | grep -v -E '^\s+\[/|selfsubject' | head -12
```

Expected: Alice's table is the nine resources of `app-developer`; Vera's is long and read-only (`get list watch` everywhere, no Secrets, no RBAC objects).

## Checkpoint

This matches [manifests/app-developer-binding.yaml](manifests/app-developer-binding.yaml) and [manifests/app-viewer-binding.yaml](manifests/app-viewer-binding.yaml) exactly.

<details>
<summary>Full <code>app-developer-binding.yaml</code></summary>

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: app-developer-binding
  namespace: part-order-app
subjects:
  # a name taken from a client certificate's O= field, or from --as-group
  - kind: Group
    name: part-order-devs
    apiGroup: rbac.authorization.k8s.io
roleRef:
  kind: Role
  name: app-developer
  apiGroup: rbac.authorization.k8s.io
```

</details>


<details>
<summary>Full <code>app-viewer-binding.yaml</code></summary>

```yaml
# The built-in "view" ClusterRole, granted in this one namespace only.
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: app-viewer-binding
  namespace: part-order-app
subjects:
  - kind: Group
    name: part-order-viewers
    apiGroup: rbac.authorization.k8s.io
roleRef:
  kind: ClusterRole
  name: view
  apiGroup: rbac.authorization.k8s.io
```

</details>


## Common mistakes

| Symptom | Cause | Fix |
| --- | --- | --- |
| Everything `no` after applying the binding | the group name in `subjects` differs from `--as-group` (or from the certificate's `O=`) | names must match exactly, character for character |
| `--as` works for you but a real user is denied | impersonation tests the binding, not the credential's contents | check what the credential carries with `kubectl auth whoami` (Step 7) |
| `The RoleBinding is invalid: roleRef: ... cannot change roleRef` | `roleRef` is immutable | `kubectl delete rolebinding` and apply again |
| `can-i` says `yes` for `pods/log` that should be `no` | `pods/log` was read as a Pod named `log` | `can-i get pods --subresource=log` |
| Subject `kind: ServiceAccount` ignored | `namespace` missing from the subject | ServiceAccount subjects need `name` and `namespace` |

Next: **[Step 6 — Cluster Scope](06-cluster-scope.md)**.
