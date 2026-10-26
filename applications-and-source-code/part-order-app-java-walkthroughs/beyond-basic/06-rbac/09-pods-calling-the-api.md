# Step 9 — Pods Calling the API: A Workload That Legitimately Uses Its Token

> [Back to index](README.md) · Previous: [Step 8](08-ci-deployer.md) · Next: [Step 10 — Guardrails and Debugging](10-guardrails-and-debugging.md)

## Goal

Run a Pod that really does talk to the Kubernetes API, give it the minimum it needs, and see how the credential reaches it, as the counterpart to Step 3 where the services were given none.

## Why this matters

Step 3 removed a token because the app does not use it. Plenty of workloads do:
controllers, operators, a Prometheus that discovers Pods, an Argo CD
that applies manifests, a job that scales something. For those the question is not
"should it have a token" but "what exactly may it do with it".

The mechanism is the same one you disabled. When a Pod runs as a ServiceAccount,
the kubelet mounts three files into every container: the CA, the namespace and a
token. The token is not a stored Secret; it is requested for that Pod, it **expires**
(after about an hour, and the kubelet renews it before then) and it is bound to the Pod it was
issued for. A client library such as `client-go` or the Java
client reads these files itself, which is called in-cluster configuration.

So the full lifecycle of a legitimate use is: a ServiceAccount, a Role with the few
verbs needed on the few resources needed, a RoleBinding, and a Pod that names
the ServiceAccount. You will build that now with the smallest possible example, a
shell Pod that may list Pods in its own namespace and nothing else.

## 1. The identity, the Role, the binding and the Pod

Create `api-client.yaml`:

```yaml
# A workload that really does call the Kubernetes API: it may list Pods in its own namespace.
apiVersion: v1
kind: ServiceAccount
metadata:
  name: pod-lister
  namespace: part-order-app
---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: pod-lister
  namespace: part-order-app
rules:
  - apiGroups: [""]
    resources: ["pods"]
    verbs: ["get", "list"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: pod-lister-binding
  namespace: part-order-app
subjects:
  - kind: ServiceAccount
    name: pod-lister
    namespace: part-order-app
roleRef:
  kind: Role
  name: pod-lister
  apiGroup: rbac.authorization.k8s.io
---
apiVersion: v1
kind: Pod
metadata:
  name: api-client
  namespace: part-order-app
spec:
  serviceAccountName: pod-lister
  containers:
    - name: curl
      image: curlimages/curl
      command: ["sleep", "3600"]
```

Note what the Pod does **not** say: no `automountServiceAccountToken: false`. It does
not need to, because this ServiceAccount (unlike the two from Step 3) leaves the default on.

```bash
cd ~/rbac-lab
kubectl apply -f api-client.yaml
kubectl wait --for=condition=Ready pod/api-client -n part-order-app --timeout=120s
```

## 2. Use the token from inside the Pod

```bash
kubectl exec -n part-order-app api-client -- ls /var/run/secrets/kubernetes.io/serviceaccount
kubectl get pod api-client -n part-order-app -o jsonpath='{.spec.volumes[0].projected.sources[0].serviceAccountToken}'; echo
```

Expected: `ca.crt`, `namespace`, `token`, and `{"expirationSeconds":3607,"path":"token"}`. The Pod spec contains a **projected volume** that Kubernetes added for you, and the token inside it is the short-lived kind from Step 8.

Now call the API three ways with the Pod's own credential:

```bash
kubectl exec -n part-order-app api-client -- sh -c '
T=/var/run/secrets/kubernetes.io/serviceaccount
AUTH="Authorization: Bearer $(cat $T/token)"
API=https://kubernetes.default.svc/api/v1
echo "--- list pods here";   curl -s --cacert $T/ca.crt -H "$AUTH" $API/namespaces/part-order-app/pods | grep -E "^ {8}\"name\":"
echo "--- list secrets";     curl -s -o /dev/null -w "%{http_code}\n" --cacert $T/ca.crt -H "$AUTH" $API/namespaces/part-order-app/secrets
echo "--- other namespace";  curl -s -o /dev/null -w "%{http_code}\n" --cacert $T/ca.crt -H "$AUTH" $API/namespaces/default/pods
echo "--- delete a pod";     curl -s -o /dev/null -w "%{http_code}\n" -X DELETE --cacert $T/ca.crt -H "$AUTH" $API/namespaces/part-order-app/pods/api-client'
```

Expected: the names of the Pods in `part-order-app` (including `api-client` itself), then `403`, `403`, `403`. The one allowed call is exactly the one the Role names (`get`, `list` on `pods`, in this namespace); reading Secrets, looking at another namespace, and deleting are all refused with the same RBAC machinery you tested from outside.

## Try it

Compare the two kinds of Pod in the namespace:

```bash
kubectl get pods -n part-order-app -o custom-columns=NAME:.metadata.name,SA:.spec.serviceAccountName
kubectl exec -n part-order-app deploy/part-order-service -- ls /var/run/secrets/kubernetes.io/serviceaccount
```

Expected: the services run as their own accounts and the `ls` fails (no token), while `api-client` runs as `pod-lister` and has one. Same cluster, same namespace, two deliberate decisions.

Clean up the demo Pod; keep the Role and ServiceAccount for the recap exercises:

```bash
kubectl delete pod api-client -n part-order-app
```

## Checkpoint

This matches [manifests/api-client.yaml](manifests/api-client.yaml) exactly.

<details>
<summary>Full <code>api-client.yaml</code></summary>

```yaml
# A workload that really does call the Kubernetes API: it may list Pods in its own namespace.
apiVersion: v1
kind: ServiceAccount
metadata:
  name: pod-lister
  namespace: part-order-app
---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: pod-lister
  namespace: part-order-app
rules:
  - apiGroups: [""]
    resources: ["pods"]
    verbs: ["get", "list"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: pod-lister-binding
  namespace: part-order-app
subjects:
  - kind: ServiceAccount
    name: pod-lister
    namespace: part-order-app
roleRef:
  kind: Role
  name: pod-lister
  apiGroup: rbac.authorization.k8s.io
---
apiVersion: v1
kind: Pod
metadata:
  name: api-client
  namespace: part-order-app
spec:
  serviceAccountName: pod-lister
  containers:
    - name: curl
      image: curlimages/curl
      command: ["sleep", "3600"]
```

</details>


## Common mistakes

| Symptom | Cause | Fix |
| --- | --- | --- |
| Pod rejected: `error looking up service account ... not found` | the Pod was applied before its ServiceAccount existed | create the ServiceAccount first; here the file lists it first |
| No token in the Pod although the account allows it | the Pod sets `automountServiceAccountToken: false` (Pod value wins over the account's) | remove it from the Pod spec |
| A Pod gets a token although its ServiceAccount says `false` | the Pod sets `automountServiceAccountToken: true`, which overrides the account | remove it, or set `false` on the Pod too |
| 401 from inside the Pod | the token was read once and cached past its expiry | re-read the file on each use; client libraries do this for you |
| 403 from a client library that worked in a test | the library lists or watches more than the Role grants (`watch`, other resources) | read the 403 message (Step 10) and add the exact missing verb |

Next: **[Step 10 — Guardrails and Debugging](10-guardrails-and-debugging.md)**.
