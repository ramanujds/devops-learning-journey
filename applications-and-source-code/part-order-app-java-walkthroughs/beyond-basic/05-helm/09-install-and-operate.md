# Step 9 — Install Both Stacks and Operate Them

> [Back to index](README.md) · Previous: [Step 8](08-overlays-and-notes.md) · Next: [Step 10 — Recap and Exercises](10-recap-and-exercises.md)

## Goal

Install the dev and prod releases side by side from one chart, place orders in both, then upgrade, roll back and uninstall, including the leftover MySQL volume.

## Why this matters

This is where the chart earns its keep, and where the Step 3 lesson bites for
real. The dev and prod releases share a chart and a release name, and differ only
in namespace and values file; Helm keeps their histories separate because
releases are scoped to a namespace.

The operational rule that matters: **make the values files, not your shell history,
the record of what is deployed.** If a local override is needed (here, a different
NodePort so the release does not collide with the stacks from walkthroughs 03 and
04), put it in its own small file and pass it on *every* command, after the overlay.
A later `-f` wins, the override survives each upgrade, and a teammate can read the
command and reproduce it. The alternative, a one-off `--set` that must be repeated or
rescued with `--reuse-values`, is how a release ends up in a state nobody can explain.

## 1. Decide how to avoid clashes

The charts use NodePorts 30080 and 30081, the same as the plain stacks from
walkthroughs 03 and 04. Pick one:

- **Remove the earlier stacks** and use the chart's default namespaces and ports: `kubectl delete namespace part-order-app part-order-app-mysql`.
- **Run beside them.** Use new namespaces and an overrides file. This is what the commands below do, and it is the safest for a shared learning cluster.

Create `~/helm-lab/part-order-app/overrides-local.yaml` (not part of the chart; it is yours):

```yaml
partOrderService:
  service:
    nodePort: 30090
```

and `overrides-local-prod.yaml`:

```yaml
partOrderService:
  service:
    nodePort: 30091
```

If you removed the earlier stacks, skip the overrides files, drop the `-f overrides-...` arguments, and use `-n part-order-app` and `-n part-order-app-mysql`.

## 2. Install dev and prod

```bash
cd ~/helm-lab/part-order-app

helm install part-order-app . -n po-helm-dev  --create-namespace -f values-dev.yaml  -f overrides-local.yaml
helm install part-order-app . -n po-helm-prod --create-namespace -f values-prod.yaml -f overrides-local-prod.yaml

helm list -A
kubectl rollout status deploy/part-order-app-part-order-service -n po-helm-dev
kubectl rollout status deploy/part-order-app-part-order-service -n po-helm-prod
```

Expected: `helm list -A` shows **two** releases, both named `part-order-app`, in different namespaces, each at revision 1. The prod rollout takes longer: MySQL initialises first, and the app Pods sit in `Init:0/1` until it answers.

```bash
kubectl get pods,svc -n po-helm-dev
kubectl get pods,svc,pvc -n po-helm-prod
```

Expected: dev has two Pods; prod has `part-order-app-mysql-0`, two of each app Pod, a headless `part-order-app-mysql` Service, and the PVC `data-part-order-app-mysql-0`.

## 3. Use them

Dev has seed data; prod starts empty (walkthrough 04). Order in dev, seed then order in prod:

```bash
curl -s -X POST http://localhost:30090/api/part-orders/place-order \
  -H "Content-Type: application/json" -d '{"sku":"SEAT-SAFE45-001","quantity":1}'; echo

kubectl run seed -n po-helm-prod --rm -i --restart=Never --image=curlimages/curl --command -- sh -c \
  'sleep 3; curl -s -X POST http://part-order-app-part-inventory-service:8080/api/parts -H "Content-Type: application/json" -d "{\"sku\":\"SEAT-SAFE45-001\",\"name\":\"Seat Safe 45 - Model A\",\"price\":29.99,\"stock\":100}"' 2>/dev/null; echo
curl -s -X POST http://localhost:30091/api/part-orders/place-order \
  -H "Content-Type: application/json" -d '{"sku":"SEAT-SAFE45-001","quantity":2}'; echo
```

Expected: `"status":"Order placed successfully"` from both. Note the in-cluster Service name `part-order-app-part-inventory-service`: it comes from the helper, so it is the release name plus the component.

## 4. Upgrade

Change something that restarts Pods: a larger memory limit for inventory in dev. Pass the same `-f` files again, plus the change:

```bash
helm upgrade part-order-app . -n po-helm-dev -f values-dev.yaml -f overrides-local.yaml \
  --set partInventoryService.resources.limits.memory=768Mi
kubectl rollout status deploy/part-order-app-part-inventory-service -n po-helm-dev
kubectl get deploy part-order-app-part-inventory-service -n po-helm-dev \
  -o jsonpath='{.spec.template.spec.containers[0].resources.limits.memory}{"\n"}'
helm history part-order-app -n po-helm-dev
```

Expected: `768Mi`, and two history rows. The NodePort is still 30090 because the overrides file was passed again. Skip it and the Service quietly returns to 30080.

Preview before you apply, which is cheap insurance:

```bash
helm upgrade part-order-app . -n po-helm-dev -f values-dev.yaml -f overrides-local.yaml --dry-run=client \
  | grep -n "nodePort"
```

If a quick tweak is all you need and you do not have the files to hand, `--reuse-values` keeps the previous release's values:

```bash
helm upgrade part-order-app . -n po-helm-dev --reuse-values --set partInventoryService.resources.limits.memory=512Mi
```

## 5. Roll back

```bash
helm rollback part-order-app 1 -n po-helm-dev
kubectl rollout status deploy/part-order-app-part-inventory-service -n po-helm-dev
kubectl get deploy part-order-app-part-inventory-service -n po-helm-dev \
  -o jsonpath='{.spec.template.spec.containers[0].resources.limits.memory}{"\n"}'
helm history part-order-app -n po-helm-dev
```

Expected: `512Mi` (revision 1's value) and a new last row, `Rollback to 1`. Rolling back to revision 1 means "re-apply the manifest as of revision 1", so history grows rather than shrinks. `helm get values part-order-app -n po-helm-dev` shows the values it used.

## 6. Pick up a new image

Both images are `:latest`, so a new push changes no manifest and Helm sees nothing to do. Either pin a tag and upgrade:

```bash
helm upgrade part-order-app . -n po-helm-dev -f values-dev.yaml -f overrides-local.yaml \
  --set partOrderService.image.tag=<your-new-tag>
```

or force a restart exactly as before:

```bash
kubectl rollout restart deploy/part-order-app-part-inventory-service -n po-helm-dev
kubectl rollout restart deploy/part-order-app-part-order-service -n po-helm-dev
```

Pinning a tag is the better habit: the tag becomes part of the release history, and `helm rollback` can restore the old one.

## 7. Uninstall, and the volume that stays

```bash
helm uninstall part-order-app -n po-helm-dev
helm uninstall part-order-app -n po-helm-prod
kubectl get all,pvc -n po-helm-prod
kubectl get pvc -n po-helm-prod --show-labels
```

Expected: every Deployment, Service and the StatefulSet are gone, but the PVC `data-part-order-app-mysql-0` remains, with labels including `app.kubernetes.io/component=mysql`. The chart's own README documents this: volumes created by a StatefulSet's `volumeClaimTemplates` are deliberately not deleted, so an uninstall cannot destroy a database. Remove it with the label the chart README gives, then the namespaces:

```bash
kubectl delete pvc -l app.kubernetes.io/component=mysql -n po-helm-prod
kubectl delete namespace po-helm-dev po-helm-prod
helm list -A
```

If you reinstall into the same namespace **without** deleting the PVC, MySQL starts on the old data, including the old passwords and users: the same "initialise once" rule as walkthrough 04.

## Try it

Sections 2-7 are the exercise. Afterwards you can check the chart's own README against what you observed: its install, upgrade and uninstall commands, and its note that `helm uninstall` keeps the MySQL PVC, all match the behaviour above.

Using the chart through GitOps instead of `helm install` is the next walkthrough.

## Checkpoint

This step creates no chart files. The only files are your local overrides:

<details>
<summary>Full <code>overrides-local.yaml</code> and <code>overrides-local-prod.yaml</code></summary>

```yaml
# overrides-local.yaml
partOrderService:
  service:
    nodePort: 30090
```

```yaml
# overrides-local-prod.yaml
partOrderService:
  service:
    nodePort: 30091
```

</details>

These are yours, not in the repo. Expected state before cleanup: `helm list -A` shows both releases at revision 1 or higher, `STATUS deployed`.

## Common mistakes

| Symptom | Cause | Fix |
| --- | --- | --- |
| `provided port is already allocated` | NodePort held by the plain stacks from 03/04 | use the overrides file, or remove those stacks |
| Upgrade quietly changed the NodePort back | override not passed on the upgrade | pass the same `-f` files every time, or `--reuse-values` |
| `INSTALLATION FAILED: cannot re-use a name` | release name already exists in that namespace | `helm upgrade --install`, or a different name |
| Prod Pods stuck at `Init:0/1` | MySQL not ready, or the volume has stale data with other credentials | `kubectl logs mysql-0`; walkthrough 04, Step 4 |
| New image not picked up | `:latest` unchanged | pin a tag, or `kubectl rollout restart` |
| MySQL PVC left behind after uninstall | by design | `kubectl delete pvc -l app.kubernetes.io/component=mysql -n NS` |
| Argo CD and `helm install` fight over the same objects | two owners for one release | pick one |

Next: **[Step 10 — Recap and Exercises](10-recap-and-exercises.md)**.
