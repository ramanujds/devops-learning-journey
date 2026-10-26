# Step 8 — Environment Overlays and Install Notes

> [Back to index](README.md) · Previous: [Step 7](07-optional-mysql.md) · Next: [Step 9 — Install and Operate](09-install-and-operate.md)

## Goal

Capture "dev" and "prod" as two small values files that set the profile, MySQL switch, replicas and NodePort together, and add post-install notes that tell the user how to reach the app.

## Why this matters

After Step 7 the chart can do both stacks, but only if a person passes the right
combination of six or seven `--set` flags in the right order. That is how
environments drift. An overlay file turns an environment into **a named,
reviewable, version-controlled thing**: `values-prod.yaml` is the answer to "what
is different about prod", and `-f values-prod.yaml` is the whole command.
It also fixes the Step 7 weakness in practice: the file sets `springProfile`
and `mysql.enabled` together, so they cannot disagree.

Overlays only list what **differs** from `values.yaml`. Helm merges maps by key, so
`values-dev.yaml` is allowed to be nearly empty. The repo keeps it anyway, "explicit and
symmetric with prod", and as the place dev-only overrides will grow.

`NOTES.txt` is different. It is a template whose output is printed after
`helm install` and `helm upgrade` (and by `helm status`). Real charts use it to answer
the first question after a deploy: "where is it, and how do I check it?"

## 1. The dev overlay

Create `values-dev.yaml`:

```yaml
# Dev overlay - H2 in-memory DB, no MySQL. Mirrors ../../k8s/.
#
# This is intentionally close to a no-op on top of values.yaml's defaults;
# it exists so `-f values-dev.yaml` is explicit and symmetric with
# values-prod.yaml, and as the place to grow dev-only overrides later.
#
#   helm install part-order-app . -f values-dev.yaml \
#     --namespace part-order-app --create-namespace

springProfile: dev

partInventoryService:
  replicaCount: 1

partOrderService:
  replicaCount: 1
  service:
    type: NodePort
    nodePort: 30080

mysql:
  enabled: false
```

Everything here equals the defaults, on purpose. It pins the dev shape explicitly, so a later change to `values.yaml` cannot silently change what "dev" means.

## 2. The prod overlay

Create `values-prod.yaml`:

```yaml
# Prod overlay - shared MySQL StatefulSet (two databases, two scoped users),
# 2 replicas per service now that state lives in MySQL instead of per-pod.
# Mirrors ../../k8s-with-mysql/.
#
#   helm install part-order-app . -f values-prod.yaml \
#     --namespace part-order-app-mysql --create-namespace
#
# The mysql.auth passwords below are the same dev-safe placeholders as
# values.yaml's defaults, repeated here for visibility - override with
# --set mysql.auth.rootPassword=... (etc.) or --set mysql.existingSecret=...
# for anything beyond local learning use.

springProfile: prod

partInventoryService:
  # state now lives in MySQL, not per-pod, so this can safely run >1 replica
  replicaCount: 2

partOrderService:
  replicaCount: 2
  service:
    type: NodePort
    # distinct from values-dev.yaml's 30080 - NodePorts are cluster-wide, so
    # this stays free in case both stacks are installed side by side
    nodePort: 30081

mysql:
  enabled: true
  auth:
    rootPassword: dev-root-pw
    inventoryDbPassword: dev-inventory-pw
    orderDbPassword: dev-order-pw
```

- `springProfile: prod` and `mysql.enabled: true` flip together.
- Both services go to two replicas. This is safe only because state now lives in MySQL, the lesson of walkthrough 04.
- `nodePort: 30081` differs from dev's 30080, because NodePorts are cluster-wide and the two releases can coexist.
- The passwords repeat the defaults "for visibility". For anything real, override them with `--set` or point `mysql.existingSecret` at a Secret you manage.

## 3. The install notes

Create `templates/NOTES.txt`:

```text
part-order-app ({{ .Chart.AppVersion }}) is deployed to namespace {{ .Release.Namespace }}.

Profile: {{ .Values.springProfile }}
MySQL:   {{ if .Values.mysql.enabled }}enabled ({{ include "part-order-app.mysqlName" . }}, {{ .Values.mysql.inventoryDatabase }} / {{ .Values.mysql.orderDatabase }}){{ else }}disabled (H2 in-memory, per pod){{ end }}

part-order-service ({{ .Values.partOrderService.replicaCount }} replica(s), {{ .Values.partOrderService.service.type }}):
{{- if eq .Values.partOrderService.service.type "NodePort" }}
  Visit http://<node-ip>:{{ .Values.partOrderService.service.nodePort }}
  (minikube: `minikube service {{ include "part-order-app.orderServiceName" . }} -n {{ .Release.Namespace }}`)
{{- else }}
  kubectl -n {{ .Release.Namespace }} port-forward svc/{{ include "part-order-app.orderServiceName" . }} 8080:{{ .Values.partOrderService.service.port }}
{{- end }}

part-inventory-service ({{ .Values.partInventoryService.replicaCount }} replica(s)) is internal-only (ClusterIP),
reached by part-order-service via:
  http://{{ include "part-order-app.inventoryServiceName" . }}:{{ .Values.partInventoryService.service.port }}

Check rollout status:
  kubectl -n {{ .Release.Namespace }} rollout status deploy/{{ include "part-order-app.inventoryServiceName" . }}
  kubectl -n {{ .Release.Namespace }} rollout status deploy/{{ include "part-order-app.orderServiceName" . }}
{{- if .Values.mysql.enabled }}
  kubectl -n {{ .Release.Namespace }} rollout status statefulset/{{ include "part-order-app.mysqlName" . }}
{{- end }}
```

It reads the same values the templates use, so it never describes something different from what was deployed: the profile, whether MySQL is on, the replica counts, and a way in that depends on the Service type (`if eq ... "NodePort"`).

## Try it

Compare the two environments as rendered output, which is the honest comparison:

```bash
cd ~/helm-lab/part-order-app
helm lint . -f values-dev.yaml
helm lint . -f values-prod.yaml
helm template part-order-app . -f values-dev.yaml  | grep -c '^kind:'
helm template part-order-app . -f values-prod.yaml | grep -c '^kind:'
diff <(helm template part-order-app . -f values-dev.yaml --show-only templates/part-order-service/deployment.yaml) \
     <(helm template part-order-app . -f values-prod.yaml --show-only templates/part-order-service/deployment.yaml)
```

Expected: both lint clean, `6` and `10` objects, and a diff of the order Deployment that shows exactly `replicas: 1` vs `2`, the `initContainers` block, and the `MYSQL_PASSWORD` env, plus one trailing blank line: nothing else.

Merge order matters when you stack files. Later `-f` files win, and `--set` wins over all files:

```bash
helm template p . -f values-prod.yaml --set partOrderService.replicaCount=3 --show-only templates/part-order-service/deployment.yaml | grep replicas
```

Expected: `replicas: 3`.

See the notes without installing anything:

```bash
helm install part-order-app . -f values-prod.yaml -n part-order-app-mysql --dry-run=client | sed -n '/^NOTES:/,$p'
```

Expected: a block starting `part-order-app (1.0.0) is deployed to namespace part-order-app-mysql.`, `Profile: prod`, `MySQL:   enabled (part-order-app-mysql, part_inventory_db / part_order_db)`, `part-order-service (2 replica(s), NodePort):` and `Visit http://<node-ip>:30081`. A dry run renders everything and talks to no cluster object, so nothing is created.

## Checkpoint

<details>
<summary>Full <code>values-dev.yaml</code></summary>

```yaml
# Dev overlay - H2 in-memory DB, no MySQL. Mirrors ../../k8s/.
#
# This is intentionally close to a no-op on top of values.yaml's defaults;
# it exists so `-f values-dev.yaml` is explicit and symmetric with
# values-prod.yaml, and as the place to grow dev-only overrides later.
#
#   helm install part-order-app . -f values-dev.yaml \
#     --namespace part-order-app --create-namespace

springProfile: dev

partInventoryService:
  replicaCount: 1

partOrderService:
  replicaCount: 1
  service:
    type: NodePort
    nodePort: 30080

mysql:
  enabled: false
```

</details>


This matches [values-dev.yaml](../../../part-order-app-java/helm/part-order-app/values-dev.yaml) exactly.

<details>
<summary>Full <code>values-prod.yaml</code></summary>

```yaml
# Prod overlay - shared MySQL StatefulSet (two databases, two scoped users),
# 2 replicas per service now that state lives in MySQL instead of per-pod.
# Mirrors ../../k8s-with-mysql/.
#
#   helm install part-order-app . -f values-prod.yaml \
#     --namespace part-order-app-mysql --create-namespace
#
# The mysql.auth passwords below are the same dev-safe placeholders as
# values.yaml's defaults, repeated here for visibility - override with
# --set mysql.auth.rootPassword=... (etc.) or --set mysql.existingSecret=...
# for anything beyond local learning use.

springProfile: prod

partInventoryService:
  # state now lives in MySQL, not per-pod, so this can safely run >1 replica
  replicaCount: 2

partOrderService:
  replicaCount: 2
  service:
    type: NodePort
    # distinct from values-dev.yaml's 30080 - NodePorts are cluster-wide, so
    # this stays free in case both stacks are installed side by side
    nodePort: 30081

mysql:
  enabled: true
  auth:
    rootPassword: dev-root-pw
    inventoryDbPassword: dev-inventory-pw
    orderDbPassword: dev-order-pw
```

</details>


This matches [values-prod.yaml](../../../part-order-app-java/helm/part-order-app/values-prod.yaml) exactly.

<details>
<summary>Full <code>templates/NOTES.txt</code></summary>

```text
part-order-app ({{ .Chart.AppVersion }}) is deployed to namespace {{ .Release.Namespace }}.

Profile: {{ .Values.springProfile }}
MySQL:   {{ if .Values.mysql.enabled }}enabled ({{ include "part-order-app.mysqlName" . }}, {{ .Values.mysql.inventoryDatabase }} / {{ .Values.mysql.orderDatabase }}){{ else }}disabled (H2 in-memory, per pod){{ end }}

part-order-service ({{ .Values.partOrderService.replicaCount }} replica(s), {{ .Values.partOrderService.service.type }}):
{{- if eq .Values.partOrderService.service.type "NodePort" }}
  Visit http://<node-ip>:{{ .Values.partOrderService.service.nodePort }}
  (minikube: `minikube service {{ include "part-order-app.orderServiceName" . }} -n {{ .Release.Namespace }}`)
{{- else }}
  kubectl -n {{ .Release.Namespace }} port-forward svc/{{ include "part-order-app.orderServiceName" . }} 8080:{{ .Values.partOrderService.service.port }}
{{- end }}

part-inventory-service ({{ .Values.partInventoryService.replicaCount }} replica(s)) is internal-only (ClusterIP),
reached by part-order-service via:
  http://{{ include "part-order-app.inventoryServiceName" . }}:{{ .Values.partInventoryService.service.port }}

Check rollout status:
  kubectl -n {{ .Release.Namespace }} rollout status deploy/{{ include "part-order-app.inventoryServiceName" . }}
  kubectl -n {{ .Release.Namespace }} rollout status deploy/{{ include "part-order-app.orderServiceName" . }}
{{- if .Values.mysql.enabled }}
  kubectl -n {{ .Release.Namespace }} rollout status statefulset/{{ include "part-order-app.mysqlName" . }}
{{- end }}
```

</details>


This matches [templates/NOTES.txt](../../../part-order-app-java/helm/part-order-app/templates/NOTES.txt) exactly.

## Common mistakes

| Symptom | Cause | Fix |
| --- | --- | --- |
| Prod release has no MySQL | `mysql.enabled` missing from the overlay, or the wrong file passed | `helm get values RELEASE` shows what was applied |
| Overlay setting ignored | an earlier or later `-f`/`--set` overrides it | order of precedence: chart defaults, then `-f` files in order, then `--set` |
| Replicas of 2 on a dev (H2) release | `replicaCount` raised without MySQL | state is per-Pod; see walkthrough 03, Step 8 |
| Notes show `<node-ip>` literally | the template does not know your cluster's address | on Docker Desktop use `localhost`; on minikube use the command in the notes |
| NodePort conflict between releases | both overlays using the same port | keep 30080 and 30081 distinct, or override with `--set` |

Next: **[Step 9 — Install and Operate](09-install-and-operate.md)**.
