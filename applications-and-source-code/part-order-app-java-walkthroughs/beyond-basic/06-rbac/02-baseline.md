# Step 2 — Baseline: Who You Are, What `default` Can Do, and the Token in Your App Pod

> [Back to index](README.md) · Previous: [Step 1](01-concepts.md) · Next: [Step 3 — Workload ServiceAccounts](03-workload-service-accounts.md)

## Goal

Measure the starting point: what your own identity can do, what the namespace's `default` ServiceAccount can do, and what credential your running app Pods already carry.

## Why this matters

You cannot judge a lock-down without knowing what was open. Two surprises are
waiting in a fresh cluster.

First, **you are not subject to RBAC.** On Docker Desktop your kubeconfig
certificate puts you in the group `system:masters`, which is bound to
`cluster-admin`. Every `kubectl` command you ran in the earlier walkthroughs was
allowed by construction. That is why the rest of this walkthrough tests through
`--as`, tokens and a second kubeconfig.

Second, **every Pod gets a credential whether it asked for one or not.** If a Pod
does not name a ServiceAccount it runs as `default`, and Kubernetes mounts that
account's token into the container. The part-order-app never calls the Kubernetes
API, so the token is pure risk: if someone finds a way to run commands inside the
container (a vulnerable library, an exposed actuator endpoint), the first thing
they can try is that token. `default` is granted nothing by itself, which is why
this is not a hole today, but any RoleBinding later added to `default` would silently
reach every Pod in the namespace.

## 1. Who you are

```bash
kubectl auth whoami
kubectl auth can-i '*' '*'
kubectl get clusterrolebinding cluster-admin -o jsonpath='{.subjects}'; echo
```

Expected on Docker Desktop:

```text
ATTRIBUTE                                           VALUE
Username                                            docker-for-desktop
Groups                                              [system:masters system:authenticated]
...
yes
[{"apiGroup":"rbac.authorization.k8s.io","kind":"Group","name":"system:masters"}]
```

The credential ID will differ. You are the user `docker-for-desktop`, in a group
bound to `cluster-admin`, and `can-i '*' '*'` (any verb on any resource) is `yes`.

## 2. What `default` can do

Ask the API server what the `default` ServiceAccount of the app namespace may do, by acting as it:

```bash
kubectl auth can-i list pods -n part-order-app --as=system:serviceaccount:part-order-app:default
kubectl auth can-i --list -n part-order-app --as=system:serviceaccount:part-order-app:default
```

The identity string is `system:serviceaccount:<namespace>:<name>`. Expected: `no`
for listing Pods, and a `--list` table that holds only the `selfsubject*` reviews
(an identity may ask about itself) and a few read-only discovery URLs such as
`/version` and `/healthz`. No Pod, Secret or Deployment appears.

## 3. The token inside your app Pod

```bash
kubectl exec -n part-order-app deploy/part-order-service -- ls /var/run/secrets/kubernetes.io/serviceaccount/
```

Expected:

```text
ca.crt
namespace
token
```

Those three files are the Pod's identity kit: the cluster CA to verify the API
server, the namespace, and the token. Use them the way a real client would:

```bash
kubectl exec -n part-order-app deploy/part-order-service -- sh -c '
T=/var/run/secrets/kubernetes.io/serviceaccount
curl -s --cacert $T/ca.crt -H "Authorization: Bearer $(cat $T/token)" \
  https://kubernetes.default.svc/api/v1/namespaces/part-order-app/pods | grep message'
```

Expected:

```text
  "message": "pods is forbidden: User \"system:serviceaccount:part-order-app:default\" cannot list resource \"pods\" in API group \"\" in the namespace \"part-order-app\"",
```

That is a **403**: the token was accepted (the API server knows who is asking) and
RBAC refused. Now the two other outcomes, so you can tell them apart later:

```bash
kubectl exec -n part-order-app deploy/part-order-service -- sh -c '
T=/var/run/secrets/kubernetes.io/serviceaccount
echo "--- no token"
curl -s --cacert $T/ca.crt https://kubernetes.default.svc/api/v1/namespaces/part-order-app/pods | grep message
echo "--- bad token"
curl -s --cacert $T/ca.crt -H "Authorization: Bearer bogus" https://kubernetes.default.svc/api/v1/namespaces/part-order-app/pods | grep message'
```

Expected: the first is again a 403, but the user is `system:anonymous`; the second is `"message": "Unauthorized"` (a **401**).

| Credential presented | Result | Meaning |
| --- | --- | --- |
| none | 403, user `system:anonymous` | authentication allows anonymous callers by default; RBAC grants them almost nothing |
| invalid or expired token | 401 | authentication failed; RBAC was never consulted |
| valid token, no matching rule | 403, user named in the message | authenticated, not authorized |

## Try it

The sections above are the exercise. One more check to leave you with the baseline:

```bash
kubectl get pods -n part-order-app -o custom-columns=NAME:.metadata.name,SA:.spec.serviceAccountName
```

Expected: every Pod shows `default`. Step 3 changes that.

## Checkpoint

This step creates no file. The state to carry forward:

<details>
<summary>Baseline findings</summary>

```text
your identity            docker-for-desktop, group system:masters (cluster-admin: nothing is ever denied)
default ServiceAccount   may only ask about itself; cannot list pods
app Pods                 run as "default" and have a token mounted at
                         /var/run/secrets/kubernetes.io/serviceaccount/token
```

</details>

## Common mistakes

| Symptom | Cause | Fix |
| --- | --- | --- |
| `can-i` says `yes` to everything | no `--as`, so it answers for you, the administrator | add `--as=...` |
| `ls /var/run/secrets/...` fails in a Pod | the Pod has no token mounted (the goal of Step 3) | expected after Step 3 |
| `--as=default` answers `no` for everything, including things that exist | the identity must be the full `system:serviceaccount:<ns>:<name>` form | use the full string |
| `curl: (60) SSL certificate problem` | `--cacert` omitted or wrong path | use `$T/ca.crt` |

Next: **[Step 3 — Workload ServiceAccounts](03-workload-service-accounts.md)**.
