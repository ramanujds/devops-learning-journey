# Step 11 — Recap and Exercises

> [Back to index](README.md) · Previous: [Step 10](10-guardrails-and-debugging.md)

## Quick reference

| Concept | Where it lives |
| --- | --- |
| One identity per service, no token | [workload-serviceaccounts.yaml](manifests/workload-serviceaccounts.yaml) |
| Developer permissions derived from tasks | [app-developer-role.yaml](manifests/app-developer-role.yaml) |
| Granting it to a group | [app-developer-binding.yaml](manifests/app-developer-binding.yaml) |
| Reusing a built-in role in one namespace | [app-viewer-binding.yaml](manifests/app-viewer-binding.yaml) |
| Cluster-scoped read access | [node-viewer.yaml](manifests/node-viewer.yaml) |
| A pipeline identity with a short-lived token | [ci-deployer.yaml](manifests/ci-deployer.yaml) |
| A workload that uses its token | [api-client.yaml](manifests/api-client.yaml) |
| A person's credential | a client certificate through the CSR API ([Step 7](07-real-user-certificate.md)) |
| Testing an identity | `kubectl auth can-i`, `--as`, `--as-group`, `kubectl auth whoami` |
| The longer study note | [09-rbac.md](../../../../kubernetes/kubernetes-intermediate/09-rbac.md) |

## Gotchas

| Gotcha | Where it comes from |
| --- | --- |
| As cluster administrator nothing is ever denied; test with `--as`, a token or a second kubeconfig | Step 2 |
| 401 means authentication failed, 403 means RBAC refused; an anonymous request is a 403 | Step 2 |
| Every Pod gets a token from `default` unless you say otherwise | Steps 2 and 3 |
| Pod-level `automountServiceAccountToken` overrides the ServiceAccount's | Steps 3 and 9 |
| `kubectl set serviceaccount` edits the live Deployment, not your manifest | Step 3 |
| Restarts re-pull `:latest` (`imagePullPolicy: Always`); if Docker Hub is unreachable the rollout stalls while the old Pod keeps serving | Steps 3, 5 and 8 |
| A Role does nothing until a binding names a subject | Step 4 |
| Logs and exec are subresources (`pods/log`, `pods/exec`) with their own rules | Step 4 |
| `can-i get pods/log` means the Pod named `log`; use `--subresource=log` | Steps 4 and 5 |
| `roleRef` cannot be changed after creation | Step 5 |
| The built-in `edit` role can read Secrets and exec into Pods; `view` cannot read Secrets | Step 5 |
| A RoleBinding on a group matches whatever group is on the credential, so trust follows the issuer | Step 5 |
| A Role cannot grant cluster-scoped resources such as Nodes | Step 6 |
| A ClusterRole bound with a ClusterRoleBinding applies in every namespace | Step 6 |
| Certificates cannot be revoked; remove the bindings, and keep lifetimes short | Step 7 |
| Deleting the CSR object does not invalidate a certificate already issued | Step 7 |
| A deleted ServiceAccount's tokens stop working, after a short cache window | Step 8 |
| You cannot create a Role or binding containing rights you do not hold | Step 10 |

## Discussion questions

1. A colleague says "I used `--as` and it worked, so Alice's kubeconfig will work". What have they not tested?
2. Why bind Roles to groups from certificates rather than to individual users? What must you trust for that to be safe?
3. The two services run without a token. Name a change to the app that would make you grant one, and describe the narrowest Role for it.
4. `app-developer` can `patch` Deployments but not `create` them. What can Alice still do to the running stack that you might not intend?
5. Why is `kubectl delete csr` not a way to remove someone's access, and what is?

## Exercises

1. Add `serviceAccountName: part-inventory-service` and `serviceAccountName: part-order-service` to the Pod templates in your `deployment.yaml` files, apply them, and confirm `kubectl get pods -o custom-columns=NAME:.metadata.name,SA:.spec.serviceAccountName` still shows them after a `rollout restart`.
2. Narrow the `app-deployer` Role with `resourceNames`: let the pipeline `patch` only `part-order-service` and `part-inventory-service`, not any other Deployment. (Careful: `create` cannot be restricted by name.) Test with a third, throwaway Deployment.
3. Build a `part-order-viewers` binding that grants `view` in `part-order-app-mysql` as well (walkthrough 04's namespace). Which object would you rather have: two RoleBindings, or one ClusterRoleBinding? What does the second one also grant?
4. Make `alice` unable to run `kubectl exec` without touching her other rights. Prove it with `can-i`. Then decide whether production should have a different Role from development.
5. Issue a second user, `bob`, in the group `part-order-viewers` through the CSR flow, and confirm he can read Pods but not Secrets. Revoke him without waiting for the certificate to expire.
6. Create a Role for `pod-lister` that also allows `watch`, then write a ten-line Pod that watches the Pods endpoint with `curl --no-buffer "...pods?watch=true"` while you delete a Pod from another terminal.
7. Write a ClusterRole `part-order-namespace-reader` that lists Namespaces, and a ClusterRoleBinding for `part-order-devs`. Test it from `alice.kubeconfig`. Which warning from Step 6 applies?
8. Rebuild all seven manifests from memory in an empty folder, then diff each against the reference:

   ```bash
   for f in workload-serviceaccounts app-developer-role app-developer-binding app-viewer-binding node-viewer ci-deployer api-client; do
     diff ~/rbac-lab/$f.yaml /path/to/06-rbac/manifests/$f.yaml && echo "$f ok"
   done
   ```

## What's next

You now know how to give each actor in the cluster its own narrow identity. The next walkthroughs in the series, listed in the [series index](../../README.md), install controllers (a GitOps engine, an operator) that run in the cluster and need ServiceAccounts and ClusterRoles of their own: when you read their install manifests, check what they ask for with the questions from Step 10.
