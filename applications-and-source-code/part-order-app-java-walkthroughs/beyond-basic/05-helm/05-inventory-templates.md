# Step 5 — The Inventory Service as Templates

> [Back to index](README.md) · Previous: [Step 4](04-chart-foundation.md) · Next: [Step 6 — Order Templates](06-order-templates.md)

## Goal

Turn the inventory ConfigMap, Deployment and Service from walkthrough 03 into templates, and render the first real manifests from the chart.

## Why this matters

Start from a manifest you already trust and replace the parts that vary with
values. Only three kinds of text change:

| In the plain manifest | In the template | Source |
| --- | --- | --- |
| `name: part-inventory-service` | `{{ include "part-order-app.inventoryServiceName" . }}` | helper |
| `namespace: part-order-app` | `{{ .Release.Namespace }}` | the `-n` flag |
| `replicas: 1`, image, probe numbers | `{{ .Values.partInventoryService... }}` | `values.yaml` |

Dropping the hard-coded namespace is a quiet but important change: the same chart
now installs wherever you point it, so the dev and prod releases in Step 9 need no
file edits. Everything else (ports, probes, `envFrom`, named port `http`) is
copied unchanged, which is the best sign the template is right: the rendered
output should look like the YAML you already wrote.

Two functions do the heavy lifting in the Deployment:

- `include "..." . | nindent N` inserts a multi-line helper result at the right indentation. Get `N` wrong and YAML either breaks or silently nests in the wrong place.
- `toYaml .Values.x | nindent N` dumps a whole values sub-tree (the `resources` block) as YAML, so the template does not need to know the field names inside it.

## 1. The ConfigMap

Create `templates/part-inventory-service/configmap.yaml`:

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: {{ include "part-order-app.inventoryServiceName" . }}-config
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "part-order-app.labels" . | nindent 4 }}
    app.kubernetes.io/component: part-inventory-service
data:
  SPRING_PROFILES_ACTIVE: {{ .Values.springProfile | quote }}
```

`| quote` wraps the value in double quotes and escapes it. ConfigMap values must be strings, which is why the plain manifest quoted `"dev"`.

## 2. The Deployment

Create `templates/part-inventory-service/deployment.yaml`:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "part-order-app.inventoryServiceName" . }}
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "part-order-app.labels" . | nindent 4 }}
    app.kubernetes.io/component: part-inventory-service
spec:
  replicas: {{ .Values.partInventoryService.replicaCount }}
  selector:
    matchLabels:
      {{- include "part-order-app.selectorLabels" . | nindent 6 }}
      app.kubernetes.io/component: part-inventory-service
  template:
    metadata:
      labels:
        {{- include "part-order-app.selectorLabels" . | nindent 8 }}
        app.kubernetes.io/component: part-inventory-service
    spec:
      containers:
        - name: part-inventory-service
          image: "{{ .Values.partInventoryService.image.repository }}:{{ .Values.partInventoryService.image.tag }}"
          imagePullPolicy: {{ .Values.image.pullPolicy }}
          ports:
            - name: http
              containerPort: 8080
          envFrom:
            - configMapRef:
                name: {{ include "part-order-app.inventoryServiceName" . }}-config
          readinessProbe:
            httpGet:
              path: {{ .Values.partInventoryService.readinessProbe.path }}
              port: http
            initialDelaySeconds: {{ .Values.partInventoryService.readinessProbe.initialDelaySeconds }}
            periodSeconds: {{ .Values.partInventoryService.readinessProbe.periodSeconds }}
            failureThreshold: {{ .Values.partInventoryService.readinessProbe.failureThreshold }}
          livenessProbe:
            httpGet:
              path: {{ .Values.partInventoryService.livenessProbe.path }}
              port: http
            initialDelaySeconds: {{ .Values.partInventoryService.livenessProbe.initialDelaySeconds }}
            periodSeconds: {{ .Values.partInventoryService.livenessProbe.periodSeconds }}
            failureThreshold: {{ .Values.partInventoryService.livenessProbe.failureThreshold }}
          resources:
            {{- toYaml .Values.partInventoryService.resources | nindent 12 }}
```

Points to notice:

- The selector and the Pod labels both include `selectorLabels` **and** the component label. Without the component label the order and inventory Deployments would select each other's Pods, since they share name and instance.
- `image: "{{ repository }}:{{ tag }}"` builds the image from two values.
- `envFrom` names the ConfigMap by the same helper plus `-config`, so it always matches the ConfigMap's own name.
- Probe paths and timings come from values; the structure (`httpGet`, `port: http`) is fixed.

## 3. The Service

Create `templates/part-inventory-service/service.yaml`:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: {{ include "part-order-app.inventoryServiceName" . }}
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "part-order-app.labels" . | nindent 4 }}
    app.kubernetes.io/component: part-inventory-service
spec:
  type: {{ .Values.partInventoryService.service.type }}
  selector:
    {{- include "part-order-app.selectorLabels" . | nindent 4 }}
    app.kubernetes.io/component: part-inventory-service
  ports:
    - name: http
      port: {{ .Values.partInventoryService.service.port }}
      targetPort: http
      {{- if and (eq .Values.partInventoryService.service.type "NodePort") .Values.partInventoryService.service.nodePort }}
      nodePort: {{ .Values.partInventoryService.service.nodePort }}
      {{- end }}
```

The `nodePort` line is wrapped in `{{- if and (eq ... "NodePort") ... }}`: it is only emitted when the type is `NodePort` **and** a number is given. A `nodePort` on a `ClusterIP` Service is an API error, so the guard keeps one template valid for both types. The `{{-` trims the whitespace before the tag so no blank line is left behind.

## Try it

Render, then compare with the plain manifest from walkthrough 03:

```bash
cd ~/helm-lab/part-order-app
helm lint .
helm template part-order-app . -n part-order-app > /tmp/inv.yaml
grep -E "^kind:|name: part-order-app-part-inventory|replicas|image:|namespace:" /tmp/inv.yaml
```

Expected: a ConfigMap, a Deployment and a Service, all in namespace `part-order-app`, all named `part-order-app-part-inventory-service[-config]`, `replicas: 1`, and `image: "ram1uj/part-inventory-service:latest"`.

Change values and re-render, without touching a template:

```bash
helm template part-order-app . -n qa --set partInventoryService.replicaCount=2 --set partInventoryService.image.tag=v2 \
  | grep -E "replicas|image:|namespace:" | sort | uniq
helm template part-order-app . --set partInventoryService.service.type=NodePort --set partInventoryService.service.nodePort=30500 \
  --show-only templates/part-inventory-service/service.yaml | grep -E "type|nodePort"
helm template part-order-app . --set partInventoryService.service.nodePort=30500 \
  --show-only templates/part-inventory-service/service.yaml | grep -E "type|nodePort"
```

Expected: `replicas: 2`, `image: "ram1uj/part-inventory-service:v2"`, `namespace: qa`; then `type: NodePort` with `nodePort: 30500`; then `type: ClusterIP` with **no** `nodePort` line, which is the guard doing its job.

You can also install just this much, to see the service start from a chart. Walkthrough 03's stack must not be using the same names (it does not; the chart's names differ):

```bash
helm install part-order-app . -n po-helm --create-namespace
kubectl rollout status deploy/part-order-app-part-inventory-service -n po-helm
helm uninstall part-order-app -n po-helm && kubectl delete ns po-helm
```

## Checkpoint

<details>
<summary>Full <code>templates/part-inventory-service/configmap.yaml</code></summary>

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: {{ include "part-order-app.inventoryServiceName" . }}-config
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "part-order-app.labels" . | nindent 4 }}
    app.kubernetes.io/component: part-inventory-service
data:
  SPRING_PROFILES_ACTIVE: {{ .Values.springProfile | quote }}
```

</details>


<details>
<summary>Full <code>templates/part-inventory-service/deployment.yaml</code></summary>

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "part-order-app.inventoryServiceName" . }}
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "part-order-app.labels" . | nindent 4 }}
    app.kubernetes.io/component: part-inventory-service
spec:
  replicas: {{ .Values.partInventoryService.replicaCount }}
  selector:
    matchLabels:
      {{- include "part-order-app.selectorLabels" . | nindent 6 }}
      app.kubernetes.io/component: part-inventory-service
  template:
    metadata:
      labels:
        {{- include "part-order-app.selectorLabels" . | nindent 8 }}
        app.kubernetes.io/component: part-inventory-service
    spec:
      containers:
        - name: part-inventory-service
          image: "{{ .Values.partInventoryService.image.repository }}:{{ .Values.partInventoryService.image.tag }}"
          imagePullPolicy: {{ .Values.image.pullPolicy }}
          ports:
            - name: http
              containerPort: 8080
          envFrom:
            - configMapRef:
                name: {{ include "part-order-app.inventoryServiceName" . }}-config
          readinessProbe:
            httpGet:
              path: {{ .Values.partInventoryService.readinessProbe.path }}
              port: http
            initialDelaySeconds: {{ .Values.partInventoryService.readinessProbe.initialDelaySeconds }}
            periodSeconds: {{ .Values.partInventoryService.readinessProbe.periodSeconds }}
            failureThreshold: {{ .Values.partInventoryService.readinessProbe.failureThreshold }}
          livenessProbe:
            httpGet:
              path: {{ .Values.partInventoryService.livenessProbe.path }}
              port: http
            initialDelaySeconds: {{ .Values.partInventoryService.livenessProbe.initialDelaySeconds }}
            periodSeconds: {{ .Values.partInventoryService.livenessProbe.periodSeconds }}
            failureThreshold: {{ .Values.partInventoryService.livenessProbe.failureThreshold }}
          resources:
            {{- toYaml .Values.partInventoryService.resources | nindent 12 }}
```

</details>


<details>
<summary>Full <code>templates/part-inventory-service/service.yaml</code></summary>

```yaml
apiVersion: v1
kind: Service
metadata:
  name: {{ include "part-order-app.inventoryServiceName" . }}
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "part-order-app.labels" . | nindent 4 }}
    app.kubernetes.io/component: part-inventory-service
spec:
  type: {{ .Values.partInventoryService.service.type }}
  selector:
    {{- include "part-order-app.selectorLabels" . | nindent 4 }}
    app.kubernetes.io/component: part-inventory-service
  ports:
    - name: http
      port: {{ .Values.partInventoryService.service.port }}
      targetPort: http
      {{- if and (eq .Values.partInventoryService.service.type "NodePort") .Values.partInventoryService.service.nodePort }}
      nodePort: {{ .Values.partInventoryService.service.nodePort }}
      {{- end }}
```

</details>


The Service matches [part-inventory-service/service.yaml](../../../part-order-app-java/helm/part-order-app/templates/part-inventory-service/service.yaml) exactly and will not change again. The ConfigMap and Deployment are intermediate: Step 7 adds the MySQL conditionals to each and they then match their references.

## Common mistakes

| Symptom | Cause | Fix |
| --- | --- | --- |
| `error converting YAML to JSON: ... did not find expected key` | wrong `nindent` width | compare rendered indentation with the plain manifest |
| Blank lines or stray text in the output | `{{ }}` instead of `{{- }}` on a control line | use `{{-` on lines that only hold an `if`/`end` |
| `nil pointer evaluating interface {}.image` | value path misspelled, or a parent key missing | check the nesting in `values.yaml` |
| Inventory and order Deployments adopt each other's Pods | component label missing from the selector | keep `app.kubernetes.io/component` in selector and template labels |
| `nodePort` rejected on a ClusterIP Service | the `if` guard was removed | restore the guard |
| Install says namespace already has the resource | an earlier release with the same names | `helm uninstall`, or use another release name |

Next: **[Step 6 — Order Templates](06-order-templates.md)**.
