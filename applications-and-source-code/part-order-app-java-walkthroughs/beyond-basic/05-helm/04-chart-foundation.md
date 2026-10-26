# Step 4 — The Application Chart: Metadata, Values and Helpers

> [Back to index](README.md) · Previous: [Step 3](03-releases.md) · Next: [Step 5 — Inventory Templates](05-inventory-templates.md)

## Goal

Create the `part-order-app` chart skeleton: metadata, an ignore file, the values for both services, and a helpers file that builds consistent names and labels.

## Why this matters

The templates in the next steps contain no hard-coded names. Where
walkthrough 03 wrote `name: part-inventory-service`, the chart writes
`{{ include "part-order-app.inventoryServiceName" . }}`. That looks heavier, and it
pays for itself immediately: install the chart twice under two release names in
one namespace and nothing collides, because every name starts from the
release name. Without that, a second install fails on its first object.

Three ideas from this step carry through the rest:

- **Named templates** in `_helpers.tpl` (a leading underscore means "not a manifest"). `define` creates one, `include` calls it. Prefer `include` over `template`, because `include` returns a string you can pipe into functions like `nindent`.
- **Labels follow a standard.** `app.kubernetes.io/name` and `app.kubernetes.io/instance` identify the app and this release. Selectors use only those two plus a component label, because **a Deployment's selector is immutable**: put a changing value (a chart version) in it and every upgrade fails.
- **Values are grouped by component** (`partInventoryService`, `partOrderService`). A flat file of 60 keys is unreadable; grouping mirrors how people think about the system.

## 1. Chart metadata

```bash
mkdir -p ~/helm-lab/part-order-app/templates/{part-inventory-service,part-order-service,mysql}
cd ~/helm-lab/part-order-app
```

Create `Chart.yaml`:

```yaml
apiVersion: v2
name: part-order-app
description: >-
  Helm chart for the part-order-app-java reference app (part-inventory-service
  + part-order-service), with an optional shared MySQL StatefulSet backend.
type: application
version: 0.1.0
appVersion: "1.0.0"
keywords:
  - spring-boot
  - kubernetes-learning
  - reference-app
```

`type: application` marks a deployable chart (the alternative, `library`, only supplies templates to other charts). The folded `description: >-` joins its lines into one string.

## 2. Keep editor noise out of the package

Create `.helmignore`:

```text
# Patterns to ignore when building packages.
.DS_Store
.git/
.gitignore
*.swp
*.bak
*.tmp
*.orig
.idea/
.vscode/
```

Helm skips these when packaging or installing. It works like `.gitignore` for charts.

## 3. Values for the two services

Create `values.yaml`. For now it holds only the shared and per-service settings; the MySQL block arrives in Step 7:

```yaml
# Default values for part-order-app.
#
# These defaults describe the dev/H2 stack (equivalent to ../../k8s/).
# Use -f values-dev.yaml or -f values-prod.yaml to select an environment
# explicitly; see values-prod.yaml for the MySQL-backed alternative
# (equivalent to ../../k8s-with-mysql/).

nameOverride: ""
fullnameOverride: ""

image:
  # shared pull policy for both app images; mysql has its own (below)
  pullPolicy: Always

# spring profile shared by both services:
#   dev  -> H2 in-memory DB, per-pod, schema.sql/data.sql run on every boot
#   prod -> MySQL, ddl-auto: update, no seed data (requires mysql.enabled: true)
springProfile: dev

partInventoryService:
  image:
    repository: ram1uj/part-inventory-service
    tag: latest
  # single replica: dev profile uses per-pod H2, so state isn't shared across
  # replicas. values-prod.yaml bumps this once MySQL is wired up.
  replicaCount: 1
  service:
    # internal only - part-order-service is the only consumer
    type: ClusterIP
    port: 8080
    nodePort: null
  resources:
    requests:
      cpu: 100m
      memory: 256Mi
    limits:
      cpu: 500m
      memory: 512Mi
  readinessProbe:
    path: /actuator/health/readiness
    initialDelaySeconds: 20
    periodSeconds: 10
    failureThreshold: 3
  livenessProbe:
    path: /actuator/health/liveness
    initialDelaySeconds: 30
    periodSeconds: 15
    failureThreshold: 3

partOrderService:
  image:
    repository: ram1uj/part-order-service
    tag: latest
  replicaCount: 1
  service:
    # NodePort so the web UI is reachable from outside the cluster in a
    # local dev cluster (minikube/kind/Docker Desktop)
    type: NodePort
    port: 8080
    nodePort: 30080
  resources:
    requests:
      cpu: 100m
      memory: 256Mi
    limits:
      cpu: 500m
      memory: 512Mi
  readinessProbe:
    path: /actuator/health/readiness
    initialDelaySeconds: 20
    periodSeconds: 10
    failureThreshold: 3
  livenessProbe:
    path: /actuator/health/liveness
    initialDelaySeconds: 30
    periodSeconds: 15
    failureThreshold: 3
```

- The defaults describe the **dev** stack: profile `dev`, one replica each, inventory `ClusterIP`, order `NodePort` 30080. That is the same shape as `k8s/` from walkthrough 03.
- `image.pullPolicy` is shared; each service has its own `image.repository` and `tag`. Changing the tag later is how you roll out a new version.
- `nodePort: null` for inventory means "unset", and the Service template will only emit a `nodePort` when the type is `NodePort` **and** a number is set.
- Probes and resources are values, not template text, so an environment can tune them without editing the template. The numbers are the ones you chose by hand in walkthrough 03.

## 4. The helpers

Create `templates/_helpers.tpl`:

```gotmpl
{{/*
Chart name, honoring nameOverride.
*/}}
{{- define "part-order-app.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Release-scoped full name, honoring fullnameOverride. Standard helm-create pattern.
*/}}
{{- define "part-order-app.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "part-order-app.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "part-order-app.labels" -}}
helm.sh/chart: {{ include "part-order-app.chart" . }}
{{ include "part-order-app.selectorLabels" . }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{- define "part-order-app.selectorLabels" -}}
app.kubernetes.io/name: {{ include "part-order-app.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{/*
Per-component Service/Deployment names. Kept as plain "<release>-part-inventory-service"
style (not hashed/templated further) since they're referenced directly by the
Feign client URL and MYSQL_HOST env vars baked into the app config.
*/}}
{{- define "part-order-app.inventoryServiceName" -}}
{{- printf "%s-part-inventory-service" (include "part-order-app.fullname" .) -}}
{{- end -}}

{{- define "part-order-app.orderServiceName" -}}
{{- printf "%s-part-order-service" (include "part-order-app.fullname" .) -}}
{{- end -}}
```

What each named template does:

| Template | Produces | Why |
| --- | --- | --- |
| `part-order-app.name` | chart name, or `nameOverride` | base for labels |
| `part-order-app.fullname` | release-scoped name | if the release name already contains the chart name (`part-order-app`), use it as is, otherwise `<release>-<chart>`; trimmed to 63 characters, the DNS label limit |
| `part-order-app.chart` | `part-order-app-0.1.0` | the `helm.sh/chart` label; `+` replaced because labels forbid it |
| `part-order-app.labels` | full label set | on every object, for `kubectl get -l` and tooling |
| `part-order-app.selectorLabels` | `name` and `instance` only | safe for immutable selectors |
| `inventoryServiceName`, `orderServiceName` | `<fullname>-part-inventory-service`, `<fullname>-part-order-service` | one place to change a Service name; the order service's URL will reuse it |

## Try it

There are no manifests yet, so test the helpers through a throwaway template:

```bash
cat > templates/debug.yaml <<'EOF'
fullname: {{ include "part-order-app.fullname" . }}
inventory: {{ include "part-order-app.inventoryServiceName" . }}
order: {{ include "part-order-app.orderServiceName" . }}
labels:
{{ include "part-order-app.labels" . | indent 2 }}
EOF
helm lint .
helm template part-order-app . --show-only templates/debug.yaml
helm template shop . --show-only templates/debug.yaml | head -4
helm template shop . --set fullnameOverride=pt --show-only templates/debug.yaml | grep fullname
rm templates/debug.yaml
```

Expected for the release `part-order-app`:

```text
fullname: part-order-app
inventory: part-order-app-part-inventory-service
order: part-order-app-part-order-service
```

and for the release `shop`: `fullname: shop-part-order-app`, `inventory: shop-part-order-app-part-inventory-service`. The first case shows the `contains` rule (no doubled `part-order-app-part-order-app`), the second the normal prefix. The last command, with `--set fullnameOverride=pt`, prints `fullname: pt`.

Delete the debug file, as in the last command: it must not ship.

## Checkpoint

<details>
<summary>Full <code>Chart.yaml</code></summary>

```yaml
apiVersion: v2
name: part-order-app
description: >-
  Helm chart for the part-order-app-java reference app (part-inventory-service
  + part-order-service), with an optional shared MySQL StatefulSet backend.
type: application
version: 0.1.0
appVersion: "1.0.0"
keywords:
  - spring-boot
  - kubernetes-learning
  - reference-app
```

</details>


This matches [part-order-app/Chart.yaml](../../../part-order-app-java/helm/part-order-app/Chart.yaml) exactly.

<details>
<summary>Full <code>.helmignore</code></summary>

```text
# Patterns to ignore when building packages.
.DS_Store
.git/
.gitignore
*.swp
*.bak
*.tmp
*.orig
.idea/
.vscode/
```

</details>


This matches [part-order-app/.helmignore](../../../part-order-app-java/helm/part-order-app/.helmignore) exactly.

`values.yaml` and `_helpers.tpl` above are intermediate versions: Step 7 appends the MySQL values and the two MySQL helpers, and each ends as an exact match with the reference.

## Common mistakes

| Symptom | Cause | Fix |
| --- | --- | --- |
| `template: no template "part-order-app.fullname" associated with template "gotpl"` | typo in the `include` name, or helpers file not named `_helpers.tpl` | names are exact; any file starting with `_` in `templates/` is loaded as helpers |
| Debug file shows up as an applied object | left `templates/debug.yaml` in place | delete it before installing |
| Resource names doubled (`part-order-app-part-order-app-...`) | `fullname` without the `contains` check | keep the helper as written |
| `nindent` / `indent` output misaligned | wrong width or wrong function | `indent` adds spaces to every line; `nindent` also starts with a newline |
| Upgrade fails with `field is immutable` | a changing value in `selectorLabels` | keep selectors to name, instance and component only |

Next: **[Step 5 — Inventory Templates](05-inventory-templates.md)**.
