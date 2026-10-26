# Step 6 — The Order Service as Templates

> [Back to index](README.md) · Previous: [Step 5](05-inventory-templates.md) · Next: [Step 7 — Optional MySQL](07-optional-mysql.md)

## Goal

Add the order service's three templates and make its ConfigMap compute the inventory URL from the same helper that names the inventory Service.

## Why this matters

The order service needs the **exact** name of the inventory Service, and that
name now depends on the release (`shop-part-order-app-part-inventory-service`
for a release called `shop`). Typing it by hand into a ConfigMap would work for
one release name and break for every other, in a way that only shows up when an
order is placed (the same HTTP 500 you met in walkthroughs 02 and 03).

The fix is to never type the name twice. The inventory Service is named by
`include "part-order-app.inventoryServiceName"`, and the order ConfigMap builds
its URL from that same helper plus the Service port from values. Rename the
Service or change its port in one place, and the URL follows. This is the main
reason to template at all: one fact, stated once.

The rest is the Step 5 pattern with the names swapped: the Deployment differs only in component
label, image values, and `replicaCount`; the Service differs only in the default type (`NodePort`) and port.

## 1. The ConfigMap

Create `templates/part-order-service/configmap.yaml`:

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: {{ include "part-order-app.orderServiceName" . }}-config
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "part-order-app.labels" . | nindent 4 }}
    app.kubernetes.io/component: part-order-service
data:
  SPRING_PROFILES_ACTIVE: {{ .Values.springProfile | quote }}
  # short in-cluster DNS name for the inventory Service; used directly by
  # the @FeignClient url attribute in InventoryServiceClient
  INVENTORY_SERVICE_URL: "http://{{ include "part-order-app.inventoryServiceName" . }}:{{ .Values.partInventoryService.service.port }}"
```

`INVENTORY_SERVICE_URL` is `http://` + the inventory Service's name + `:` + its port. The comment explains the Feign client uses it. Its default (`http://localhost:8080`) is what you saw break in the Docker run.

## 2. The Deployment

Create `templates/part-order-service/deployment.yaml`:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "part-order-app.orderServiceName" . }}
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "part-order-app.labels" . | nindent 4 }}
    app.kubernetes.io/component: part-order-service
spec:
  replicas: {{ .Values.partOrderService.replicaCount }}
  selector:
    matchLabels:
      {{- include "part-order-app.selectorLabels" . | nindent 6 }}
      app.kubernetes.io/component: part-order-service
  template:
    metadata:
      labels:
        {{- include "part-order-app.selectorLabels" . | nindent 8 }}
        app.kubernetes.io/component: part-order-service
    spec:
      containers:
        - name: part-order-service
          image: "{{ .Values.partOrderService.image.repository }}:{{ .Values.partOrderService.image.tag }}"
          imagePullPolicy: {{ .Values.image.pullPolicy }}
          ports:
            - name: http
              containerPort: 8080
          envFrom:
            - configMapRef:
                name: {{ include "part-order-app.orderServiceName" . }}-config
          readinessProbe:
            httpGet:
              path: {{ .Values.partOrderService.readinessProbe.path }}
              port: http
            initialDelaySeconds: {{ .Values.partOrderService.readinessProbe.initialDelaySeconds }}
            periodSeconds: {{ .Values.partOrderService.readinessProbe.periodSeconds }}
            failureThreshold: {{ .Values.partOrderService.readinessProbe.failureThreshold }}
          livenessProbe:
            httpGet:
              path: {{ .Values.partOrderService.livenessProbe.path }}
              port: http
            initialDelaySeconds: {{ .Values.partOrderService.livenessProbe.initialDelaySeconds }}
            periodSeconds: {{ .Values.partOrderService.livenessProbe.periodSeconds }}
            failureThreshold: {{ .Values.partOrderService.livenessProbe.failureThreshold }}
          resources:
            {{- toYaml .Values.partOrderService.resources | nindent 12 }}
```

Everything else is the inventory Deployment with `partInventoryService` replaced by `partOrderService` in the values paths, the component label, and the ConfigMap name. You can start by copying the inventory file and editing those few words, then check with `diff`:

```bash
diff templates/part-inventory-service/deployment.yaml templates/part-order-service/deployment.yaml | grep -c '^[<>]'
```

Expect roughly 30 differing lines. Read them: each is one of those substitutions (a values path, the component label, the ConfigMap name, the image), nothing structural.

## 3. The Service

Create `templates/part-order-service/service.yaml`:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: {{ include "part-order-app.orderServiceName" . }}
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "part-order-app.labels" . | nindent 4 }}
    app.kubernetes.io/component: part-order-service
spec:
  type: {{ .Values.partOrderService.service.type }}
  selector:
    {{- include "part-order-app.selectorLabels" . | nindent 4 }}
    app.kubernetes.io/component: part-order-service
  ports:
    - name: http
      port: {{ .Values.partOrderService.service.port }}
      targetPort: http
      {{- if and (eq .Values.partOrderService.service.type "NodePort") .Values.partOrderService.service.nodePort }}
      nodePort: {{ .Values.partOrderService.service.nodePort }}
      {{- end }}
```

## Try it

```bash
cd ~/helm-lab/part-order-app
helm lint .
helm template part-order-app . -n part-order-app | grep -E "^kind:" | sort | uniq -c
helm template part-order-app . -n part-order-app --show-only templates/part-order-service/configmap.yaml | grep URL
helm template shop . -n part-order-app --show-only templates/part-order-service/configmap.yaml | grep URL
helm template shop . -n part-order-app --show-only templates/part-inventory-service/service.yaml | grep -E "  name:"
```

Expected: 2 ConfigMaps, 2 Deployments, 2 Services. The URL is `http://part-order-app-part-inventory-service:8080` for the release `part-order-app` and `http://shop-part-order-app-part-inventory-service:8080` for `shop`, and the last command prints exactly that same Service name: the URL and the Service always agree.

Finally prove the pair works as the dev stack from walkthrough 03. Port 30080 may be taken by that stack, so override it:

```bash
helm install part-order-app . -n po-helm --create-namespace --set partOrderService.service.nodePort=30090
kubectl rollout status deploy/part-order-app-part-order-service -n po-helm
curl -s -X POST http://localhost:30090/api/part-orders/place-order \
  -H "Content-Type: application/json" -d '{"sku":"SEAT-SAFE45-001","quantity":1}'
helm uninstall part-order-app -n po-helm && kubectl delete ns po-helm
```

Expected: `"status":"Order placed successfully"`. If you removed the 03 stack, you can leave off the `--set` and use port 30080.

## Checkpoint

<details>
<summary>Full <code>templates/part-order-service/configmap.yaml</code></summary>

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: {{ include "part-order-app.orderServiceName" . }}-config
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "part-order-app.labels" . | nindent 4 }}
    app.kubernetes.io/component: part-order-service
data:
  SPRING_PROFILES_ACTIVE: {{ .Values.springProfile | quote }}
  # short in-cluster DNS name for the inventory Service; used directly by
  # the @FeignClient url attribute in InventoryServiceClient
  INVENTORY_SERVICE_URL: "http://{{ include "part-order-app.inventoryServiceName" . }}:{{ .Values.partInventoryService.service.port }}"
```

</details>


<details>
<summary>Full <code>templates/part-order-service/deployment.yaml</code></summary>

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "part-order-app.orderServiceName" . }}
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "part-order-app.labels" . | nindent 4 }}
    app.kubernetes.io/component: part-order-service
spec:
  replicas: {{ .Values.partOrderService.replicaCount }}
  selector:
    matchLabels:
      {{- include "part-order-app.selectorLabels" . | nindent 6 }}
      app.kubernetes.io/component: part-order-service
  template:
    metadata:
      labels:
        {{- include "part-order-app.selectorLabels" . | nindent 8 }}
        app.kubernetes.io/component: part-order-service
    spec:
      containers:
        - name: part-order-service
          image: "{{ .Values.partOrderService.image.repository }}:{{ .Values.partOrderService.image.tag }}"
          imagePullPolicy: {{ .Values.image.pullPolicy }}
          ports:
            - name: http
              containerPort: 8080
          envFrom:
            - configMapRef:
                name: {{ include "part-order-app.orderServiceName" . }}-config
          readinessProbe:
            httpGet:
              path: {{ .Values.partOrderService.readinessProbe.path }}
              port: http
            initialDelaySeconds: {{ .Values.partOrderService.readinessProbe.initialDelaySeconds }}
            periodSeconds: {{ .Values.partOrderService.readinessProbe.periodSeconds }}
            failureThreshold: {{ .Values.partOrderService.readinessProbe.failureThreshold }}
          livenessProbe:
            httpGet:
              path: {{ .Values.partOrderService.livenessProbe.path }}
              port: http
            initialDelaySeconds: {{ .Values.partOrderService.livenessProbe.initialDelaySeconds }}
            periodSeconds: {{ .Values.partOrderService.livenessProbe.periodSeconds }}
            failureThreshold: {{ .Values.partOrderService.livenessProbe.failureThreshold }}
          resources:
            {{- toYaml .Values.partOrderService.resources | nindent 12 }}
```

</details>


<details>
<summary>Full <code>templates/part-order-service/service.yaml</code></summary>

```yaml
apiVersion: v1
kind: Service
metadata:
  name: {{ include "part-order-app.orderServiceName" . }}
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "part-order-app.labels" . | nindent 4 }}
    app.kubernetes.io/component: part-order-service
spec:
  type: {{ .Values.partOrderService.service.type }}
  selector:
    {{- include "part-order-app.selectorLabels" . | nindent 4 }}
    app.kubernetes.io/component: part-order-service
  ports:
    - name: http
      port: {{ .Values.partOrderService.service.port }}
      targetPort: http
      {{- if and (eq .Values.partOrderService.service.type "NodePort") .Values.partOrderService.service.nodePort }}
      nodePort: {{ .Values.partOrderService.service.nodePort }}
      {{- end }}
```

</details>


The Service matches [part-order-service/service.yaml](../../../part-order-app-java/helm/part-order-app/templates/part-order-service/service.yaml) exactly. The ConfigMap and Deployment are intermediate and reach their reference form in Step 7.

## Common mistakes

| Symptom | Cause | Fix |
| --- | --- | --- |
| Order returns HTTP 500 though both Pods are Ready | URL does not match the inventory Service's real name | build it from `inventoryServiceName`, never by hand |
| `nil pointer evaluating interface {} .replicaCount` | used `partInventoryService` in one place and `partOrderService` in another | search the file for the other service's key |
| `provided port is already allocated` | 30080 held by another stack | `--set partOrderService.service.nodePort=...` or remove the other stack |
| Both Deployments fight over Pods | missing component label in one selector | compare both files' selectors |
| Service reachable on wrong port | `port` and `targetPort` mixed up | `port` is the Service's, `targetPort: http` the container's |

Next: **[Step 7 — Optional MySQL](07-optional-mysql.md)**.
