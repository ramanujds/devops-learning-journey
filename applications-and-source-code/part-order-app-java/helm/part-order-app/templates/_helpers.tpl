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

{{- define "part-order-app.mysqlName" -}}
{{- printf "%s-mysql" (include "part-order-app.fullname" .) -}}
{{- end -}}

{{- define "part-order-app.mysqlSecretName" -}}
{{- if .Values.mysql.existingSecret -}}
{{- .Values.mysql.existingSecret -}}
{{- else -}}
{{- include "part-order-app.mysqlName" . -}}
{{- end -}}
{{- end -}}
