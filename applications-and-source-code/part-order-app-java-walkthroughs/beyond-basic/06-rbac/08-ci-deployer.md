# Step 8 — The CI Deployer: A ServiceAccount Token and a Deploy-Only Role

> [Back to index](README.md) · Previous: [Step 7](07-real-user-certificate.md) · Next: [Step 9 — Pods Calling the API](09-pods-calling-the-api.md)

## Goal

Create an identity for a pipeline that can deploy and restart the two services but cannot read Secrets, change permissions or delete Pods, and use it through a short-lived token.

## Why this matters

A pipeline that deploys to the cluster needs a credential, and the lazy answer is to
paste an administrator's kubeconfig into the CI system. That makes your CI service,
and everyone who can edit its pipeline, a cluster administrator. Pipelines are among
the most attacked parts of a delivery system, so the credential should be
the smallest one that does the job.

A ServiceAccount fits, because it is an API object you can bind a Role to, and it
needs no certificate ceremony. Its credential is a **token**, and
`kubectl create token` issues one that **expires** (one hour by default; you can ask
for less or more). A leaked token stops working on its own, and a deleted
ServiceAccount invalidates every token it ever issued. The old way, long-lived Secret-based tokens, is no longer created automatically in current Kubernetes.

The Role for this identity differs from `app-developer` in the other direction: it may
**create, update and delete** the objects the manifests define (Deployments,
Services, ConfigMaps, the HPA) because that is what `kubectl apply` of
`k8s/` does, but it has no Secrets, no `pods/exec`, and no RBAC objects. Pods and
ReplicaSets are read-only, which is enough for `rollout status`.

## 1. The identity, its Role and the binding

Create `ci-deployer.yaml` (three objects in one file):

```yaml
# The identity a pipeline would use to run "kubectl apply" for this app.
apiVersion: v1
kind: ServiceAccount
metadata:
  name: ci-deployer
  namespace: part-order-app
---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: app-deployer
  namespace: part-order-app
rules:
  - apiGroups: [""]
    resources: ["configmaps", "services"]
    verbs: ["get", "list", "watch", "create", "update", "patch", "delete"]
  - apiGroups: ["apps"]
    resources: ["deployments"]
    verbs: ["get", "list", "watch", "create", "update", "patch", "delete"]
  - apiGroups: ["autoscaling"]
    resources: ["horizontalpodautoscalers"]
    verbs: ["get", "list", "watch", "create", "update", "patch", "delete"]
  # read-only on what a rollout creates, so "rollout status" works
  - apiGroups: [""]
    resources: ["pods"]
    verbs: ["get", "list", "watch"]
  - apiGroups: ["apps"]
    resources: ["replicasets"]
    verbs: ["get", "list", "watch"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: ci-deployer-binding
  namespace: part-order-app
subjects:
  - kind: ServiceAccount
    name: ci-deployer
    namespace: part-order-app
roleRef:
  kind: Role
  name: app-deployer
  apiGroup: rbac.authorization.k8s.io
```

```bash
cd ~/rbac-lab
kubectl apply -f ci-deployer.yaml
kubectl auth can-i --list -n part-order-app --as=system:serviceaccount:part-order-app:ci-deployer | grep -v -E '^\s+\[/|selfsubject'
```

Expected: the six rows of `app-deployer`.

## 2. Get a token and build the pipeline's kubeconfig

```bash
mkdir -p ~/rbac-lab/ci && cd ~/rbac-lab/ci
CLUSTER=$(kubectl config view --minify -o jsonpath='{.clusters[0].name}')
SERVER=$(kubectl config view --minify -o jsonpath='{.clusters[0].cluster.server}')
kubectl config view --raw --minify -o jsonpath='{.clusters[0].cluster.certificate-authority-data}' | base64 -d > ca.crt
TOKEN=$(kubectl create token ci-deployer -n part-order-app --duration=1h)

kubectl config set-cluster "$CLUSTER" --server="$SERVER" --certificate-authority=ca.crt --embed-certs --kubeconfig=ci.kubeconfig
kubectl config set-credentials ci-deployer --token="$TOKEN" --kubeconfig=ci.kubeconfig
kubectl config set-context ci --cluster="$CLUSTER" --user=ci-deployer --namespace=part-order-app --kubeconfig=ci.kubeconfig
kubectl config use-context ci --kubeconfig=ci.kubeconfig
chmod 600 ci.kubeconfig
```

In a real pipeline, the token would be created by whatever holds the right to mint it, stored in the CI system's secret store, and refreshed before it expires. A one-hour token means a stolen copy is useless by lunch.

## Try it

First, who the API server thinks the pipeline is, and what a deployment looks like:

```bash
KUBECONFIG=ci.kubeconfig kubectl auth whoami
```

Expected: `Username  system:serviceaccount:part-order-app:ci-deployer` and the groups `system:serviceaccounts`, `system:serviceaccounts:part-order-app` and `system:authenticated`.

Now do what a pipeline does, using the manifests from walkthrough 03 (if you do not have `~/k8s-lab/part-order-app`, use the repo's `k8s/` folder and skip `namespace.yaml`: the pipeline cannot create namespaces):

```bash
KUBECONFIG=ci.kubeconfig kubectl apply -f ~/k8s-lab/part-order-app/part-inventory-service/ -f ~/k8s-lab/part-order-app/part-order-service/
KUBECONFIG=ci.kubeconfig kubectl rollout restart deploy/part-inventory-service
KUBECONFIG=ci.kubeconfig kubectl rollout status deploy/part-inventory-service
```

Expected: `unchanged` lines (or `configured` if you edited something), then the rollout completes. `rollout restart` followed by `rollout status` is the repo's own loop after pushing new `:latest` images.

Then everything a compromised pipeline should **not** be able to do:

```bash
KUBECONFIG=ci.kubeconfig kubectl get secrets
KUBECONFIG=ci.kubeconfig kubectl create secret generic x --from-literal=a=b
KUBECONFIG=ci.kubeconfig kubectl delete pod --all
KUBECONFIG=ci.kubeconfig kubectl get namespaces
KUBECONFIG=ci.kubeconfig kubectl apply -f ~/rbac-lab/app-developer-role.yaml
KUBECONFIG=ci.kubeconfig kubectl create rolebinding sneaky --clusterrole=cluster-admin --serviceaccount=part-order-app:ci-deployer
```

Expected: every command is refused with `Forbidden` (`delete pod --all` prints one error per Pod). The last one is the interesting one: even if the pipeline could create RoleBindings, it could not grant itself `cluster-admin`. Step 10 shows why.

### Invalidating the credential

```bash
kubectl delete serviceaccount ci-deployer -n part-order-app
sleep 15
KUBECONFIG=ci.kubeconfig kubectl get deploy
```

Expected: `error: You must be logged in to the server (Unauthorized)`. The token was valid for another hour, but it was bound to that ServiceAccount; with the account deleted, it is useless. (Immediately after the delete the old token may still work for a few seconds, because the API server caches a successful token check briefly, hence the `sleep`.) Recreating the account does not revive the old token, because the token identifies the account by its unique ID, which is new:

```bash
kubectl apply -f ~/rbac-lab/ci-deployer.yaml
sleep 15
KUBECONFIG=ci.kubeconfig kubectl get deploy
```

Expected: `Unauthorized` again. You would issue a new token with `kubectl create token`.

## Checkpoint

This matches [manifests/ci-deployer.yaml](manifests/ci-deployer.yaml) exactly.

<details>
<summary>Full <code>ci-deployer.yaml</code></summary>

```yaml
# The identity a pipeline would use to run "kubectl apply" for this app.
apiVersion: v1
kind: ServiceAccount
metadata:
  name: ci-deployer
  namespace: part-order-app
---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: app-deployer
  namespace: part-order-app
rules:
  - apiGroups: [""]
    resources: ["configmaps", "services"]
    verbs: ["get", "list", "watch", "create", "update", "patch", "delete"]
  - apiGroups: ["apps"]
    resources: ["deployments"]
    verbs: ["get", "list", "watch", "create", "update", "patch", "delete"]
  - apiGroups: ["autoscaling"]
    resources: ["horizontalpodautoscalers"]
    verbs: ["get", "list", "watch", "create", "update", "patch", "delete"]
  # read-only on what a rollout creates, so "rollout status" works
  - apiGroups: [""]
    resources: ["pods"]
    verbs: ["get", "list", "watch"]
  - apiGroups: ["apps"]
    resources: ["replicasets"]
    verbs: ["get", "list", "watch"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: ci-deployer-binding
  namespace: part-order-app
subjects:
  - kind: ServiceAccount
    name: ci-deployer
    namespace: part-order-app
roleRef:
  kind: Role
  name: app-deployer
  apiGroup: rbac.authorization.k8s.io
```

</details>


## Common mistakes

| Symptom | Cause | Fix |
| --- | --- | --- |
| `kubectl apply` fails on `namespace.yaml` | creating or reading a Namespace is cluster-scoped; the Role is namespaced | apply the Namespace separately as an administrator |
| `apply` says `cannot get resource "deployments"` | the Role lacks `get`; apply must read the live object first | keep `get` with `patch`/`create` |
| `rollout status` hangs, then `forbidden ... replicasets` | the Role has no read access to ReplicaSets | add `replicasets` with `get`, `list`, `watch` |
| `Unauthorized` an hour later | the token expired | issue a new one with `kubectl create token` |
| Pipeline needs `kubectl delete pod` | a real need or a habit? | grant it narrowly if needed; do not add `*` |
| Token appears in CI logs | `--token=` echoed on the command line or `set -x` | feed it from the secret store; do not print it |

Next: **[Step 9 — Pods Calling the API](09-pods-calling-the-api.md)**.
