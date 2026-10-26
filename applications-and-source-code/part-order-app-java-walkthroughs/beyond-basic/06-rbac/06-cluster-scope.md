# Step 6 — Cluster Scope: ClusterRole and ClusterRoleBinding

> [Back to index](README.md) · Previous: [Step 5](05-bindings-and-groups.md) · Next: [Step 7 — A Real User Certificate](07-real-user-certificate.md)

## Goal

Let the developer group read Nodes, which no namespaced Role can grant, and see why a ClusterRoleBinding is a larger commitment than a RoleBinding.

## Why this matters

Namespaces scope *most* resources, not all. Nodes, Namespaces, PersistentVolumes and
the CRDs of cluster-wide controllers belong to the cluster itself. A `Role` lives in a
namespace and can only name namespaced resources, so a rule about `nodes` placed in a
Role is accepted by the API but can never be exercised. Cluster-scoped resources need
a `ClusterRole`, bound with a `ClusterRoleBinding`.

The difference between the two bindings is the blast radius:

| Binding | ClusterRole it names | Effective |
| --- | --- | --- |
| `RoleBinding` | a ClusterRole | in the binding's one namespace (Step 5: `view`) |
| `ClusterRoleBinding` | the same ClusterRole | **in every namespace**, and for cluster-scoped resources |

Binding `view` with a ClusterRoleBinding instead of the RoleBinding of Step 5 would
let Vera read every namespace in the cluster, including `kube-system`. Always ask
which of the two a task really needs; here only Nodes are cluster-scoped, and
only reading them is wanted.

Why would a developer need Nodes at all? `kubectl get pods -o wide` shows the node
a Pod runs on, `kubectl describe pod` references node events, and capacity questions
("why is my Pod Pending?") need `kubectl get nodes`. It is harmless information, and
a good small example of the second object pair.

## 1. Show the denial first

```bash
as_alice() { kubectl --as=alice --as-group=part-order-devs "$@"; }
as_alice get nodes
```

Expected: `Error from server (Forbidden): nodes is forbidden: User "alice" cannot list resource "nodes" in API group "" at the cluster scope`. Note `at the cluster scope`, in place of `in the namespace`.

## 2. The ClusterRole and its binding

Create `node-viewer.yaml`. Two objects in one file, separated by `---`:

```yaml
# Nodes are cluster-scoped, so a namespaced Role cannot grant them.
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: part-order-node-viewer
rules:
  - apiGroups: [""]
    resources: ["nodes"]
    verbs: ["get", "list", "watch"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: part-order-node-viewer-binding
subjects:
  - kind: Group
    name: part-order-devs
    apiGroup: rbac.authorization.k8s.io
roleRef:
  kind: ClusterRole
  name: part-order-node-viewer
  apiGroup: rbac.authorization.k8s.io
```

The names are prefixed `part-order-` because ClusterRoles share one cluster-wide namespace of names with everyone else's. The binding's subject is the same group as Step 5, so the one credential gets both the namespaced Role and this.

```bash
cd ~/rbac-lab
kubectl apply -f node-viewer.yaml
```

## Try it

```bash
as_alice get nodes
as_alice auth can-i delete nodes
as_alice auth can-i list pods -n default
as_alice auth can-i list pods --all-namespaces
as_vera() { kubectl --as=vera --as-group=part-order-viewers "$@"; }
as_vera get nodes
```

Expected: Alice sees the `docker-desktop` node; `delete nodes` is `no`; `list pods` in `default` and across all namespaces are still `no` (the node grant added nothing for Pods, and `app-developer` is bound only in `part-order-app`); Vera, who is not in `part-order-devs`, is refused. (`can-i` prints a `Warning: resource 'nodes' is not namespace scoped` line first, which is harmless.)

Now see the two grants combined. Alice's access is the **union**:

```bash
as_alice auth can-i --list -n part-order-app | grep -E 'nodes|deployments|pods '
```

Expected: `nodes [get list watch]` from the ClusterRole, plus the `pods` and `deployments.apps` lines from the Role.

## Checkpoint

This matches [manifests/node-viewer.yaml](manifests/node-viewer.yaml) exactly.

<details>
<summary>Full <code>node-viewer.yaml</code></summary>

```yaml
# Nodes are cluster-scoped, so a namespaced Role cannot grant them.
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: part-order-node-viewer
rules:
  - apiGroups: [""]
    resources: ["nodes"]
    verbs: ["get", "list", "watch"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: part-order-node-viewer-binding
subjects:
  - kind: Group
    name: part-order-devs
    apiGroup: rbac.authorization.k8s.io
roleRef:
  kind: ClusterRole
  name: part-order-node-viewer
  apiGroup: rbac.authorization.k8s.io
```

</details>


## Common mistakes

| Symptom | Cause | Fix |
| --- | --- | --- |
| Rule on `nodes` in a Role has no effect | a Role cannot grant cluster-scoped resources | move it to a ClusterRole |
| `RoleBinding` to a ClusterRole still shows nothing for Nodes | RoleBindings only reach the one namespace | use a ClusterRoleBinding for cluster-scoped resources |
| Reader can see every namespace's Pods | a `view` ClusterRole bound with a ClusterRoleBinding | use a RoleBinding per namespace instead |
| Name collision on apply | another team's ClusterRole has the same name | prefix with your project, as `part-order-` here |

Next: **[Step 7 — A Real User Certificate](07-real-user-certificate.md)**.
