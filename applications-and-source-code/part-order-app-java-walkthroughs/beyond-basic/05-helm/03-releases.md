# Step 3 — Releases: Install, Upgrade, Roll Back

> [Back to index](README.md) · Previous: [Step 2](02-first-chart.md) · Next: [Step 4 — The Application Chart](04-chart-foundation.md)

## Goal

Install `nginx-app` as a release, change it with values overlays and `--set`, read its history, roll it back, and remove it.

## Why this matters

Rendering is half of Helm. The other half is that every change is recorded as a
numbered **revision**, so "what is running" and "how do I get back to
yesterday" have answers. A rollback is not an undo of Kubernetes objects; it
re-applies the manifest stored for an older revision, and it is itself recorded as a **new** revision.

The mistake to learn here is how values are carried between upgrades. By
default `helm upgrade` computes the new release from the **chart defaults plus
only the values you pass on that command line**. Anything you supplied on an
earlier install and do not repeat is dropped. Install with `-f values-prod.yaml`,
then upgrade with just `--set replicaCount=2`, and the release quietly returns
to the chart's default image. The two safe habits are to pass the same `-f` files every time
(preferred: the files are your source of truth), or to use `--reuse-values` for a quick tweak.

## 1. Overlay files

An overlay is a values file that only lists what differs from `values.yaml`. Create `values-dev.yaml`:

```yaml
replicaCount: 1
image:
  repository: nginx
  tag: "latest"
service:
  type: ClusterIP
  port: 80
```

and `values-prod.yaml`:

```yaml
replicaCount: 1
image:
  repository: caddy
  tag: "latest"
service:
  type: NodePort
  port: 80
```

These are the demo values used by the GitOps walkthrough: `values-dev.yaml` keeps nginx but exposes it only inside the cluster, and `values-prod.yaml` swaps the image to Caddy and publishes a NodePort so a change is visible in the browser. Values merge by key, so a file does not have to repeat what it does not change (these two repeat everything for readability).

## 2. Install

```bash
cd ~/helm-lab
helm install web nginx-app -n helm-lab --create-namespace
helm list -n helm-lab
kubectl get pods,svc -n helm-lab
```

- `web` is the release name; it becomes the `{{ .Release.Name }}` in the templates.
- `-n helm-lab --create-namespace` puts the release in its own namespace, creating it if needed. The namespace is not part of the chart.

Reach it (Docker Desktop publishes NodePorts on localhost):

```bash
PORT=$(kubectl get svc web-nginx -n helm-lab -o jsonpath='{.spec.ports[0].nodePort}')
curl -sI http://localhost:$PORT | head -2
```

Expected: `HTTP/1.1 200 OK` and `Server: nginx/1.25...`.

## 3. Upgrade with an overlay

```bash
helm upgrade web nginx-app -n helm-lab -f nginx-app/values-prod.yaml
kubectl rollout status deploy/web-nginx -n helm-lab
kubectl get deploy web-nginx -n helm-lab -o jsonpath='{.spec.template.spec.containers[0].image}{"\n"}'
curl -sI http://localhost:$PORT | head -3
```

Expected: image `caddy:latest`, and the response no longer says nginx. The NodePort stays the same because the Service was updated, not replaced.

Then tweak one value on top, remembering the earlier values with `--reuse-values`:

```bash
helm upgrade web nginx-app -n helm-lab --reuse-values --set replicaCount=3
helm get values web -n helm-lab
```

Expected: the user-supplied values show `image.repository: caddy` **and** `replicaCount: 3`.

## 4. Watch the values reset

Now do it the wrong way:

```bash
helm upgrade web nginx-app -n helm-lab --set replicaCount=2
kubectl get deploy web-nginx -n helm-lab -o jsonpath='{.spec.replicas} {.spec.template.spec.containers[0].image}{"\n"}'
```

Expected: `2 nginx:1.25`. The replica change applied, but Caddy is gone: the upgrade started again from the chart defaults. Keep this in mind for Step 9.

## 5. History and rollback

```bash
helm history web -n helm-lab
helm rollback web 2 -n helm-lab
kubectl rollout status deploy/web-nginx -n helm-lab
kubectl get deploy web-nginx -n helm-lab -o jsonpath='{.spec.replicas} {.spec.template.spec.containers[0].image}{"\n"}'
helm history web -n helm-lab
```

Expected: the first history lists revisions 1-4, and the last revision (4) says `Upgrade complete`. After the rollback the Deployment is back to `1 caddy:latest` (revision 2's values), and history now has a fifth row with description `Rollback to 2`. Rolling back added a revision; it did not delete 3 and 4.

Switch to the dev overlay to see a Service type change:

```bash
helm upgrade web nginx-app -n helm-lab -f nginx-app/values-dev.yaml
kubectl get svc web-nginx -n helm-lab
```

Expected: `TYPE ClusterIP`.

## 6. Look inside, then remove

```bash
helm get manifest web -n helm-lab | head -8
helm get values web -n helm-lab -a | head -12
kubectl get secret -n helm-lab -l owner=helm
helm uninstall web -n helm-lab
kubectl get all -n helm-lab
```

- `get manifest` is what Helm applied; `get values -a` shows user-supplied plus defaults.
- The Secrets are the revision records (`sh.helm.release.v1.web.v1`, ...). Uninstall deletes them and every object the release created.
- The namespace stays: `--create-namespace` creates it outside the release, so uninstalling does not remove it. Remove it yourself:

```bash
kubectl delete namespace helm-lab
```

## Try it

Sections 2-6 are the exercise. A good self-check: after `helm uninstall`, can you get back to a release whose Service is `ClusterIP` and whose image is `nginx:latest` with a single command and no `--set`? (`helm install web nginx-app -n helm-lab --create-namespace -f nginx-app/values-dev.yaml` does it.)

## Checkpoint

<details>
<summary>Full <code>nginx-app/values-dev.yaml</code></summary>

```yaml
replicaCount: 1
image:
  repository: nginx
  tag: "latest"
service:
  type: ClusterIP
  port: 80
```

</details>

This matches [nginx-app/values-dev.yaml](../../../part-order-app-java/helm/nginx-app/values-dev.yaml) exactly (the repo file has no trailing newline).

<details>
<summary>Full <code>nginx-app/values-prod.yaml</code></summary>

```yaml
replicaCount: 1
image:
  repository: caddy
  tag: "latest"
service:
  type: NodePort
  port: 80
```

</details>

This matches [nginx-app/values-prod.yaml](../../../part-order-app-java/helm/nginx-app/values-prod.yaml) exactly (the repo file has no trailing newline).

## Common mistakes

| Symptom | Cause | Fix |
| --- | --- | --- |
| Upgrade silently reverts earlier settings | values not repeated and no `--reuse-values` | pass the same `-f` files each time, or add `--reuse-values` |
| `cannot re-use a name that is still in use` | release name already installed in that namespace | `helm upgrade --install`, or choose another name |
| `helm list` shows nothing | you are in a different namespace | `helm list -A` |
| Unsure what a past revision contained | only the latest manifest is easy to see | `helm get manifest web --revision N` and `helm get values web --revision N` |
| `--dry-run` deprecation warning (Helm 4) | bare `--dry-run` is deprecated | use `--dry-run=client` (render only) or `--dry-run=server` |

Next: **[Step 4 — The Application Chart](04-chart-foundation.md)**.
