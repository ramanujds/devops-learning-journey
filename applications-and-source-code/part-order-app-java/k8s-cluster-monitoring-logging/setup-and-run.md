# Setting This Up and Testing It, Step by Step

For *what* gets installed and *why*, see [README.md](README.md). This
file is the "how" — every command, in the order it was actually run.

---

## Step 1: Add the Helm repositories

```bash
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo add grafana https://grafana.github.io/helm-charts
helm repo update
```

---

## Step 2: Install Prometheus

```bash
helm install prometheus prometheus-community/prometheus --version 27.8.0
```

This one chart installs Prometheus server, Alertmanager, `node-exporter`,
and `kube-state-metrics` together — everything the PromQL queries later
in this guide depend on.

> **Docker Desktop caveat, confirmed by testing:** `node-exporter` may
> crash-loop with `path / is mounted on / but it is not a shared or
> slave mount` — Docker Desktop's VM doesn't support the mount
> propagation `node-exporter` needs for its `/` hostPath mount. It's an
> environment limitation, not a bad install; only node-level CPU/memory
> metrics and the `NodeHighCPU`/`NodeHighMemory` alerts are affected —
> app-level metrics, logs, and dashboards all work regardless.

---

## Step 3: Install Grafana

```bash
helm install grafana grafana/grafana --version 8.12.0 -f grafana-values.yaml
```

[grafana-values.yaml](grafana-values.yaml) sets the admin password and
provisions the **Prometheus** datasource automatically — no manual
"Add datasource" click needed in the UI.

---

## Step 4: Expose Grafana

```bash
kubectl apply -f grafana-nodeport.yaml
minikube service grafana-ext --url
```

[grafana-nodeport.yaml](grafana-nodeport.yaml) is a plain `NodePort`
Service on `30000` in front of the chart's own `ClusterIP` Service —
same pattern as every other NodePort Service in this repo's other `k8s*`
variants, just pointed at Grafana's pod label instead of an app's.

---

## Step 5: Import the cluster overview dashboard

1. Open Grafana at the URL from Step 4 (`admin` / whatever
   `grafana-values.yaml` set as `adminPassword`)
2. **Dashboards → Import**
3. Dashboard ID: `15661`
4. Data source: **Prometheus**

---

## Step 6: Add Loki for log aggregation

```bash
helm install loki grafana/loki --version 6.29.0 -f loki-values.yaml
```

[loki-values.yaml](loki-values.yaml) runs Loki in single-binary mode with
filesystem storage — no separate read/write/backend components, no
external object store, and no memcached-based `chunksCache`/
`resultsCache` (the chart defaults `chunksCache` alone to an 8Gi memory
request, which won't schedule on a single-node local cluster; both are
pure read-side perf optimizations, skippable at dev/test log volumes) —
the right shape for a local/dev cluster, not for production log volumes.
Loki is reachable inside the cluster at `http://loki:3100`.

Upgrade the existing Grafana release to provision Loki as a second
datasource, alongside Prometheus, with no manual UI setup:

```bash
helm upgrade grafana grafana/grafana --version 8.12.0 -f grafana-values.yaml
```

---

## Step 7: Deploy the Promtail sidecar ConfigMap

```bash
kubectl apply -f promtail-configmap.yaml
```

[promtail-configmap.yaml](promtail-configmap.yaml) is the Promtail config
mounted into every app Pod in the next step — it tails
`/app/logs/*.log`, labels each line `app=${APP_NAME}` (templated per
Deployment) and `job=spring-boot`, collapses multi-line stack traces into
one entry, and extracts a `level` label from the standard logback line
format.

---

## Step 8: Deploy the app, now with a Promtail sidecar

```bash
kubectl apply -f part-inventory-deployment.yaml
kubectl apply -f part-inventory-service.yaml
kubectl apply -f part-order-deployment.yaml
kubectl apply -f part-order-service.yaml
```

Each Pod now runs two containers — the Spring Boot app and `promtail` —
sharing an `emptyDir` volume at `/app/logs`. Confirm both came up:

```bash
kubectl get pods
# both should show READY 2/2

kubectl logs <pod-name> -c promtail -f
# confirms Promtail is tailing files and pushing to Loki
```

For a real end-to-end proof (not just "the sidecar started"), query Loki
directly, bypassing Grafana entirely:

```bash
kubectl exec <inventory-pod-name> -c part-inventory-service -- tail -5 /app/logs/application.log
# confirms the app itself is actually writing the file promtail is tailing

kubectl port-forward svc/loki 3100:3100 &

curl -s "http://127.0.0.1:3100/loki/api/v1/label/app/values" | jq .
# {"status":"success","data":["part-inventory-service","part-order-service"]}
# both service names present = both sidecars are successfully pushing to Loki

curl -s "http://127.0.0.1:3100/loki/api/v1/label/level/values" | jq .
# {"status":"success","data":["INFO","WARN"]}  (ERROR shows up once one actually occurs)
# confirms Promtail's regex pipeline stage is extracting the level label correctly
```

---

## Step 9: Query logs in Grafana

**Explore → select Loki** as the datasource, then use LogQL:

```logql
# all logs from the inventory service
{app="part-inventory-service"}

# only ERROR logs from the order service
{app="part-order-service", level="ERROR"}

# filter by log message content
{app="part-inventory-service"} |= "place-order"
```

---

## Step 10: Confirm Spring Boot metrics are exposed

Both services already carry `micrometer-registry-prometheus` in
`pom.xml` and expose the endpoint via Actuator:

```yaml
# application.yml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
  metrics:
    tags:
      application: ${spring.application.name}   # adds an `application` label to every metric
```

```bash
kubectl port-forward svc/part-inventory-service 8080:80 -n inventory-service &
curl http://localhost:8080/actuator/prometheus | grep http_server_requests | head -10
```

---

## Step 11: Confirm Prometheus is scraping both services

Prometheus discovers scrape targets via Pod annotations, already present
on both Deployments in this directory:

```yaml
annotations:
  prometheus.io/scrape: "true"
  prometheus.io/path: "/actuator/prometheus"
  prometheus.io/port: "8080"
```

```bash
minikube service prometheus-server-ext --url
# open /targets — look for part-inventory-service and part-order-service, State: UP
```

---

## Key PromQL queries

Open Prometheus's **Graph** tab, or paste directly into a Grafana panel.

**Request rate (traffic)**

```promql
rate(http_server_requests_seconds_count{application="part-inventory-service"}[5m])

sum by (application) (
  rate(http_server_requests_seconds_count[5m])
)
```

**Error rate**

```promql
rate(http_server_requests_seconds_count{
  application="part-order-service",
  status=~"5.."
}[5m])

sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
/
sum(rate(http_server_requests_seconds_count[5m]))
* 100
```

**Latency (p95 / p99)**

```promql
histogram_quantile(0.95,
  rate(http_server_requests_seconds_bucket{
    application="part-inventory-service",
    method="GET"
  }[5m])
)

histogram_quantile(0.99,
  sum by (le, application) (
    rate(http_server_requests_seconds_bucket[5m])
  )
)
```

**Pod CPU and memory**

```promql
sum by (pod) (
  rate(container_cpu_usage_seconds_total{
    namespace="inventory-service",
    container!=""
  }[5m])
)

container_memory_working_set_bytes{
  namespace="inventory-service",
  container!=""
}

container_memory_working_set_bytes{namespace="inventory-service"}
/
container_spec_memory_limit_bytes{namespace="inventory-service"}
* 100
```

**Pod availability**

```promql
sum by (deployment) (
  kube_deployment_status_replicas_ready
)

kube_pod_status_ready{condition="false", namespace="inventory-service"}
```

---

## Grafana dashboards

**Dashboard 1 — Kubernetes Cluster Overview** (imported in Step 5, ID
`15661`) — node CPU/memory, pod count, per-namespace resource
consumption.

**Dashboard 2 — Spring Boot Services** — a custom dashboard for this app
specifically, included as
[spring-boot-services-dashboard.json](spring-boot-services-dashboard.json):

```bash
# Dashboards → Import → Upload JSON file → spring-boot-services-dashboard.json
```

Per service: RPS, error rate, p95 latency, pod count, JVM heap usage.

**Dashboard 3 — Kubernetes Workloads** (import ID `15760`) — per-namespace
drill-down: CPU/memory per pod, network I/O, restarts.

**Dashboard 4 — Log Explorer (Loki)** — same LogQL queries as Step 9, run
from **Explore** instead of a saved panel.

---

## Prometheus alerting rules

Two ways to define the same rules, depending on which Prometheus setup is
running:

**With `kube-prometheus-stack`** (adds the `PrometheusRule` CRD):

```bash
kubectl apply -f prometheus-rules.yaml
```

**With the standalone `prometheus-community/prometheus` chart used in
Step 2** (no `PrometheusRule` CRD — rules go through Helm values
instead):

```bash
helm upgrade prometheus prometheus-community/prometheus \
  -f prometheus-rules-values.yaml
```

```bash
# confirm rules landed, either way
kubectl get configmap prometheus-server -o yaml | grep -A5 "rules:"
```

See [README.md](README.md#alerting-rules-included) for what each rule
actually checks.

---

## Alertmanager: routing alerts to Slack

```yaml
# alertmanager-values.yaml — passed to the same `helm upgrade prometheus` as the rules above
alertmanager:
  config:
    global:
      slack_api_url: "https://hooks.slack.com/services/YOUR/WEBHOOK/URL"

    route:
      group_by: ["alertname", "namespace"]
      group_wait: 30s
      group_interval: 5m
      repeat_interval: 12h
      receiver: "slack-critical"
      routes:
        - match:
            severity: critical
          receiver: "slack-critical"
        - match:
            severity: warning
          receiver: "slack-warnings"

    receivers:
      - name: "slack-critical"
        slack_configs:
          - channel: "#alerts-critical"
            title: "{{ .GroupLabels.alertname }}"
            text: "{{ range .Alerts }}{{ .Annotations.description }}{{ end }}"
            send_resolved: true

      - name: "slack-warnings"
        slack_configs:
          - channel: "#alerts-warnings"
            title: "{{ .GroupLabels.alertname }}"
            text: "{{ range .Alerts }}{{ .Annotations.summary }}{{ end }}"
            send_resolved: true
```

```bash
helm upgrade prometheus prometheus-community/prometheus \
  -f alertmanager-values.yaml

minikube service prometheus-alertmanager-ext --url
```

`severity: critical` and `severity: warning` are exactly the labels set
on each alert in `prometheus-rules.yaml` — that's what `route.routes`
matches against to pick a channel.

---

## Health checks: liveness, readiness, and startup probes

The Kubernetes-native health mechanism, working alongside (not
instead of) Prometheus — these control traffic routing and container
restarts directly, with no dashboard in the loop:

```yaml
containers:
  - name: part-inventory-service
    startupProbe:
      httpGet:
        path: /actuator/health/readiness
        port: 8080
      failureThreshold: 30      # 30 x 10s = 5 minutes for JVM startup
      periodSeconds: 10
    livenessProbe:
      httpGet:
        path: /actuator/health/liveness
        port: 8080
      initialDelaySeconds: 0    # startup probe guards this, so 0 is fine
      periodSeconds: 10
      failureThreshold: 3       # restart after 3 consecutive failures (30s)
    readinessProbe:
      httpGet:
        path: /actuator/health/readiness
        port: 8080
      periodSeconds: 5
      failureThreshold: 3       # remove from Service endpoints after 15s
```

Spring Boot Actuator maps liveness and readiness to separate health
groups automatically:

```bash
curl http://localhost:8080/actuator/health/liveness
# {"status":"UP","components":{"livenessState":{"status":"UP"}}}

curl http://localhost:8080/actuator/health/readiness
# {"status":"UP","components":{"readinessState":{"status":"UP"},"db":{"status":"UP"}}}
```

When the database goes down, `readiness` flips to `DOWN` → the Pod is
pulled from Service endpoints → traffic stops, while `liveness` stays
`UP` → the Pod is **not** restarted, since restarting a Pod can't fix a
database outage.

```promql
# pods currently failing readiness
kube_pod_status_ready{condition="false", namespace="inventory-service"}

# restart count per container — a rising counter means CrashLoop or liveness failures
increase(kube_pod_container_status_restarts_total{namespace="inventory-service"}[1h])

kube_pod_container_status_restarts_total{namespace="inventory-service"} > 5
```

---

## Lab: trigger and observe an alert end-to-end

**1. Confirm Prometheus is scraping (already done in Step 11)**

```bash
kubectl port-forward svc/prometheus-server 9090:80 &
# http://localhost:9090/targets — both services should show State: UP
```

**2. Inject a failure and watch the alert fire**

```bash
kubectl scale deployment part-inventory-service -n inventory-service --replicas=0
```

```text
Prometheus UI → Alerts tab → watch PodNotReady transition:
INACTIVE → PENDING (for: 2m) → FIRING
```

**3. Observe it from Grafana**

```bash
kubectl port-forward svc/grafana 3000:80 &
# http://localhost:3000 (admin / whatever grafana-values.yaml set)
```

- **Kubernetes Cluster Overview** dashboard → pod count drops
- **Explore → Prometheus** → `kube_pod_status_ready{condition="false"}`
- **Explore → Loki** → `{app="part-order-service"} |= "Connection refused"`
  (the order service's own calls to inventory now failing)

**4. Restore and watch recovery**

```bash
kubectl scale deployment part-inventory-service -n inventory-service --replicas=2
# alert transitions FIRING → RESOLVED once the readiness probe passes again
# Prometheus sends the resolved notification through to Alertmanager
```

---

## Grafana's own alerting (as an alternative to Alertmanager)

Grafana can evaluate alert conditions and notify independently of
Alertmanager — useful for alerts tied directly to a dashboard panel's own
query, rather than a cluster-wide `PrometheusRule`:

```text
Grafana UI → Alerting → Alert Rules → New Alert Rule

Query A:
  sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
  /
  sum(rate(http_server_requests_seconds_count[5m]))

Condition: IS ABOVE 0.01
Evaluation: every 1m, for 2m
Labels: severity=critical, service=part-inventory-service
Notification: contact point → Slack webhook
```

Rule of thumb: Prometheus alert rules for infrastructure-level conditions
(node down, `OOMKilled`); Grafana alerting for anything you're already
watching on a specific dashboard panel.

---

## Verification commands

```bash
# Prometheus scrape targets
curl http://localhost:9090/api/v1/targets | jq '.data.activeTargets[] | {job:.labels.job, health:.health}'

# Active alerts
curl http://localhost:9090/api/v1/alerts | jq '.data.alerts[] | {name:.labels.alertname, state:.state}'

# Alertmanager — current alerts being routed
curl http://localhost:9093/api/v1/alerts | jq '.data[].labels'

# Grafana datasource health
curl -u admin:<password> http://localhost:3000/api/datasources | jq '.[].name'

# Pod restart counts
kubectl get pods -n inventory-service -o custom-columns="NAME:.metadata.name,RESTARTS:.status.containerStatuses[0].restartCount"

# Events — probe failures show up here
kubectl get events -n inventory-service --sort-by='.lastTimestamp' | tail -20
```

---

## Cleanup

```bash
helm uninstall prometheus grafana loki
kubectl delete -f grafana-nodeport.yaml
kubectl delete -f promtail-configmap.yaml
kubectl delete -f part-inventory-deployment.yaml -f part-inventory-service.yaml
kubectl delete -f part-order-deployment.yaml -f part-order-service.yaml
kubectl delete -f prometheus-rules.yaml   # only if the PrometheusRule CRD path was used
```
