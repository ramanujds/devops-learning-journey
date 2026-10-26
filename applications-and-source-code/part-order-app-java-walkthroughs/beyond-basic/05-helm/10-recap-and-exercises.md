# Step 10 — Recap and Exercises

> [Back to index](README.md) · Previous: [Step 9](09-install-and-operate.md)

## Quick reference

| Concept | Where it lives |
| --- | --- |
| Chart metadata and version | [Chart.yaml](../../../part-order-app-java/helm/part-order-app/Chart.yaml) |
| Defaults, grouped by component | [values.yaml](../../../part-order-app-java/helm/part-order-app/values.yaml) |
| Environment overlays | [values-dev.yaml](../../../part-order-app-java/helm/part-order-app/values-dev.yaml), [values-prod.yaml](../../../part-order-app-java/helm/part-order-app/values-prod.yaml) |
| Consistent names and labels | [_helpers.tpl](../../../part-order-app-java/helm/part-order-app/templates/_helpers.tpl) |
| The same manifests as walkthroughs 03 and 04, parameterised | `templates/part-inventory-service/`, `part-order-service/`, `mysql/` |
| The dev/prod switch | `springProfile` and `mysql.enabled`, set together in the overlays |
| Post-install message | [templates/NOTES.txt](../../../part-order-app-java/helm/part-order-app/templates/NOTES.txt) |
| Render without installing | `helm template`, `helm lint`, `helm install --dry-run=client` |
| Release lifecycle | `helm install / upgrade / history / rollback / uninstall` |
| What Helm applied | `helm get manifest`, `helm get values` |

## Gotchas

| Gotcha | Where it comes from |
| --- | --- |
| A misspelled value renders as empty and `helm lint` still passes | Step 2; always read the rendered output |
| `helm upgrade` without the earlier `-f` files resets to chart defaults | Step 3 |
| `rollback` adds a revision rather than removing any | Steps 3 and 9 |
| `springProfile` and `mysql.enabled` can disagree without any error | Step 7; the overlays are the only safeguard |
| Deployment selectors are immutable, so a changing label in them breaks upgrades | Step 4 |
| `helm uninstall` leaves the MySQL PVC | Step 9; also documented in the chart's README |
| Plaintext default passwords in `values.yaml` | the chart's own comments say to override them for anything beyond local use |
| `:latest` images do not trigger a rollout | Step 9; pin a tag or restart |
| `--dry-run` without a value is deprecated in Helm 4 | use `--dry-run=client` |
| Argo CD and a manual `helm install` into the same namespace will fight | chart README; see the next walkthrough |

## Discussion questions

1. The template builds the inventory URL from `inventoryServiceName`. What breaks, and when would you find out, if it were hard-coded instead?
2. Why does the selector use only three labels while the metadata uses more?
3. `values-dev.yaml` repeats the defaults. What is the argument for and against keeping it?
4. Two releases of the same chart are installed with the same name. What distinguishes them, and where does Helm keep each one's history?
5. When is `--reuse-values` the wrong choice?

## Exercises

1. Run `helm get manifest` for the dev release and map every object back to a template file using the `# Source:` comments.
2. Make `springProfile` a required value: in a ConfigMap template, write `{{ required "springProfile is required" .Values.springProfile | quote }}`, set it empty, and see the error. Then restore it.
3. Add a `resources` override to `values-prod.yaml` that raises the order service's memory limit, render with `helm template`, and confirm only prod changes.
4. Add a guard to `_helpers.tpl` or a template that calls `fail` when `springProfile` is `prod` and `mysql.enabled` is false (and the reverse). Prove it with `helm template`.
5. Add an `env: dev` / `env: prod` label to every object through the `labels` helper, driven by a new top-level value. Check that selectors did **not** change.
6. Package the chart with `helm package` and install from the `.tgz`. Compare the file list inside against `.helmignore`.
7. Convert `overrides-local.yaml` into a values file for a third environment, `staging`, with its own namespace and NodePort, and install all three releases.
8. Rebuild `part-order-app` from memory in an empty folder, then diff every file against the reference:

   ```bash
   diff -r ~/helm-lab/part-order-app /path/to/part-order-app-java/helm/part-order-app --exclude=README.md --exclude=argocd --exclude='overrides-*'
   ```

## What's next

The chart is now the unit of deployment. Next: walkthrough 06, delivering it with Argo CD, so the cluster is
reconciled to Git instead of someone running `helm install` by hand (it uses `part-order-app/argocd/application.yaml` and the
nginx example). It is not written yet; the series index is at [../../README.md](../../README.md).
