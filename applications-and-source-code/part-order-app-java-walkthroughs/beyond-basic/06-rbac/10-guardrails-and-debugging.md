# Step 10 — Guardrails and Debugging: Escalation, Reading a 403, Auditing, Cleanup

> [Back to index](README.md) · Previous: [Step 9](09-pods-calling-the-api.md) · Next: [Step 11 — Recap and Exercises](11-recap-and-exercises.md)

## Goal

See the safety rule that stops someone with limited RBAC rights from granting themselves more, learn to turn a `Forbidden` message into the missing rule, list who holds what, and remove the lab.

## Why this matters

RBAC has an obvious weak point: the objects that grant permissions are themselves
API objects. If "may create a RoleBinding" were an ordinary permission, anyone
holding it could bind `cluster-admin` to themselves and the whole model would be
decoration. Kubernetes closes that hole in the API server: you can create or update a
Role or binding **only if you already hold every permission it contains** (or have
been given the special `escalate` or `bind` verbs, per the Kubernetes docs, which
this walkthrough does not exercise). So the people allowed to *delegate* are still
bounded by what they themselves have.

The other half of working with RBAC day to day is diagnosis. You will see far more
`Forbidden` errors than you write Roles, and the message is a precise
specification of what is missing, if you know how to read it.

## 1. The escalation guard

Create a deliberately powerful, but still limited, identity: it may create RoleBindings in this namespace, and nothing else.

```bash
kubectl create role binding-manager -n part-order-app --verb=create,get,list --resource=rolebindings.rbac.authorization.k8s.io
kubectl create rolebinding mallory-binding-manager -n part-order-app --role=binding-manager --user=mallory
kubectl auth can-i create rolebindings -n part-order-app --as=mallory
```

Expected: `yes`. Mallory is trusted with bindings. Now she tries to make herself an administrator:

```bash
kubectl create rolebinding oops -n part-order-app --clusterrole=cluster-admin --user=mallory --as=mallory 2>&1 | head -4
```

Expected:

```text
error: failed to create rolebinding: rolebindings.rbac.authorization.k8s.io "oops" is forbidden: user "mallory" (groups=["system:authenticated"]) is attempting to grant RBAC permissions not currently held:
{APIGroups:["*"], Resources:["*"], Verbs:["*"]}
{NonResourceURLs:["*"], Verbs:["*"]}
```

She can create bindings, but not to a role that contains permissions she does not
hold herself. The refusal even lists the missing permissions. The same applies in
Step 8: even a pipeline that had been given `create rolebindings` could not have granted itself `cluster-admin`.

Two takeaways for design. Granting `create rolebindings`/`roles` is a delegation of
authority and should be rare. And `escalate` and `bind` bypass this check, so treat them as seriously as `cluster-admin`.

## 2. Read a Forbidden message

Alice tries something a developer might reasonably try:

```bash
as_alice() { kubectl --as=alice --as-group=part-order-devs "$@"; }
as_alice scale deploy/part-order-service --replicas=1 -n part-order-app
```

Expected:

```text
Error from server (Forbidden): deployments.apps "part-order-service" is forbidden: User "alice" cannot patch resource "deployments/scale" in API group "apps" in the namespace "part-order-app"
```

Map each part to the rule it implies:

| Message part | Rule field | Value here |
| --- | --- | --- |
| `User "alice"` | the subject to bind | alice (or her group) |
| `cannot patch` | `verbs` | `patch` |
| `resource "deployments/scale"` | `resources` | `deployments/scale`, a subresource |
| `in API group "apps"` | `apiGroups` | `apps` |
| `in the namespace "part-order-app"` | where the Role and binding go | a Role here, not a ClusterRole |

If you decided Alice should be able to scale, the missing rule is:

```yaml
  - apiGroups: ["apps"]
    resources: ["deployments/scale"]
    verbs: ["patch"]
```

Do **not** add it to the lab: scaling `part-inventory-service` is the HPA's job here, and Alice's `patch` on `deployments` already lets her change replicas by editing the Deployment, which is a good reminder that overlapping rules are common and worth reviewing. The skill is the reading: a precise message you can turn into one precise rule, instead of widening to `*`.

Also read what a *missing* permission looks like from the other side. Alice cannot list RoleBindings:

```bash
as_alice get rolebindings -n part-order-app
```

Expected: `Forbidden`. In this design only administrators see who holds what, which is the usual choice.

## 3. Audit who holds what

As administrator, the questions are "what is bound in this namespace", "who is a cluster administrator", and "what can this identity do":

```bash
kubectl get rolebindings -n part-order-app -o wide
kubectl get clusterrolebindings -o wide | grep -E '^NAME|cluster-admin '
kubectl describe rolebinding app-developer-binding -n part-order-app
kubectl auth can-i --list -n part-order-app --as=system:serviceaccount:part-order-app:part-order-service | grep -v -E '^\s+\[/|selfsubject'
```

Expected: the first shows one row per binding with `USERS`, `GROUPS` and `SERVICEACCOUNTS` columns (you will see `app-developer-binding` with group `part-order-devs`, `ci-deployer-binding`, and others); the second shows the bindings to `cluster-admin` (at least the built-in `cluster-admin` and `kubeadm:cluster-admins` on a kubeadm cluster); the last prints only the table header with no rows: the app's own identity has no rights, which is the Step 3 outcome.

Regular reviews of the second command are the single most valuable RBAC habit: every extra `cluster-admin` binding is a way to take over the cluster.

Impersonation itself is a permission. Alice cannot use `--as`:

```bash
KUBECONFIG=~/rbac-lab/alice/alice.kubeconfig kubectl get pods --as=system:admin
```

Expected: `Forbidden`, with a message that mentions `impersonate` (the exact wording differs between Kubernetes versions). You, as administrator, may impersonate, which is how this whole walkthrough tested identities.

## 4. Clean up the lab

Remove everything this walkthrough added, keeping only the hardened services (Step 3):

```bash
cd ~/rbac-lab
kubectl delete -f api-client.yaml -f ci-deployer.yaml -f node-viewer.yaml -f app-viewer-binding.yaml -f app-developer-binding.yaml -f app-developer-role.yaml --ignore-not-found
kubectl delete rolebinding mallory-binding-manager -n part-order-app
kubectl delete role binding-manager -n part-order-app
kubectl delete secret demo-secret -n part-order-app
kubectl delete csr alice --ignore-not-found
```

Then check that what remains is only your services' own accounts:

```bash
kubectl get serviceaccounts,roles,rolebindings -n part-order-app
kubectl get clusterrole,clusterrolebinding | grep part-order
```

Expected: `default`, `part-inventory-service` and `part-order-service` accounts, no Roles or RoleBindings, and no output from the second command.

Optionally, put the Deployments back on `default`, then remove the dedicated accounts:

```bash
kubectl set serviceaccount deploy/part-inventory-service default -n part-order-app
kubectl set serviceaccount deploy/part-order-service default -n part-order-app
kubectl delete -f workload-serviceaccounts.yaml
```

And remove the credentials you generated (they are secrets, and the certificate would expire in a day anyway):

```bash
rm -r ~/rbac-lab/alice ~/rbac-lab/ci
```

## Try it

The cleanup commands above are the exercise. The end state to check:

```bash
kubectl get pods -n part-order-app
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:30080/
```

Expected: the Pods are `Running` and the site answers `200`: removing the lab did not touch the application.

## Checkpoint

This step creates no file. The expected namespace after cleanup, keeping the hardened services:

<details>
<summary>Expected <code>kubectl get serviceaccounts,roles,rolebindings -n part-order-app</code></summary>

```text
NAME                                    AGE
serviceaccount/default                  ...
serviceaccount/part-inventory-service   ...
serviceaccount/part-order-service       ...
```

</details>

## Common mistakes

| Symptom | Cause | Fix |
| --- | --- | --- |
| `attempting to grant RBAC permissions not currently held` | creating a Role or binding that contains rights you do not have | have a holder of those rights create it, or grant `bind`/`escalate` knowingly |
| Cannot find which Role grants a permission | access is the union of many bindings | `kubectl get rolebindings,clusterrolebindings -A -o wide` and check the subject's groups too |
| `no` from `can-i` while a real request works | `can-i` was run without the group or namespace the real request has | repeat with the same `--as`, `--as-group` and `-n` |
| Wildcards crept in (`resources: ["*"]`) | widened to silence a Forbidden error | read the message and add the one rule it names |
| `cluster-admin` bound to an application | convenience during a fix | replace with a specific Role; audit with the `cluster-admin` command above |
| New Pods fail with `serviceaccount ... not found` after the cleanup | `workload-serviceaccounts.yaml` was deleted while the Deployments still named those accounts | run `kubectl set serviceaccount ... default` first, then delete the accounts |

Next: **[Step 11 — Recap and Exercises](11-recap-and-exercises.md)**.
