# Setting This Up and Testing It, Step by Step

For *what* gets installed and *why*, see [README.md](README.md). This file
is the "how".

---

## Step 1: Namespace

```bash
kubectl apply -f namespace.yaml
```

---

## Step 2: Deploy the OTel Collector

```bash
kubectl apply -f otel-collector/configmap.yaml
kubectl apply -f otel-collector/deployment.yaml
kubectl apply -f otel-collector/service.yaml

kubectl -n part-order-app-tracing rollout status deploy/otel-collector
```

---

## Step 3: Install Tempo

```bash
helm repo add grafana https://grafana.github.io/helm-charts   # skip if already added
helm repo update

helm install tempo grafana/tempo --version 1.24.4 \
  -f tempo-values.yaml -n part-order-app-tracing
```

[tempo-values.yaml](tempo-values.yaml) runs Tempo single-binary with local
filesystem storage — no object store needed for a dev cluster. Confirm the
Service name/port Tempo actually landed on (the datasource URL in
[grafana-values.yaml](grafana-values.yaml) assumes `tempo:3100`):

```bash
kubectl -n part-order-app-tracing get svc tempo
```

---

## Step 4: Deploy the app, pointed at the collector

```bash
kubectl apply -f part-inventory-service/
kubectl apply -f part-order-service/

kubectl -n part-order-app-tracing get pods
# both should reach Running/1-1 within ~30s (readiness probe: /actuator/health/readiness)
```

---

## Step 5: Confirm spans are flowing (before Grafana is even involved)

The collector's `debug` exporter (see
[otel-collector/configmap.yaml](otel-collector/configmap.yaml)) prints every
received span to its own stdout — the fastest way to confirm the
app -> collector leg works:

```bash
kubectl -n part-order-app-tracing logs -f deploy/otel-collector
```

In another terminal, generate a trace by placing an order through the app
(see "Reaching the app" below for how to get to `part-order-service`), or
directly:

```bash
kubectl -n part-order-app-tracing port-forward svc/part-order-service 8080:8080 &
curl -X POST http://localhost:8080/api/part-orders/place-order \
  -H "Content-Type: application/json" \
  -d '{"sku":"SEAT-SAFE45-001","quantity":1}'
```

The collector log should show a batch with **two** spans a moment later —
one from `part-order-service`, one from `part-inventory-service` — sharing
the same trace ID.

---

## Step 6: Grafana — install fresh, or upgrade the existing release

**If `k8s-cluster-monitoring-logging`'s Grafana is already installed**
(recommended — you get Prometheus + Loki + Tempo correlated in one place):

```bash
helm upgrade grafana grafana/grafana --version 8.12.0 \
  -f ../k8s-cluster-monitoring-logging/grafana-values.yaml \
  -f grafana-values.yaml
```

Passing both values files merges them (later file wins on overlapping
keys) — the `datasources.datasources.yaml` block in this directory's
`grafana-values.yaml` is the complete set (Prometheus + Loki + Tempo), so
this upgrade alone re-provisions all three correctly.

**Standalone instead** (only tracing, no metrics/logs stack):

```bash
helm install grafana grafana/grafana --version 8.12.0 -f grafana-values.yaml
```

Either way, expose it the same way `k8s-cluster-monitoring-logging` does
(`grafana-nodeport.yaml` from that directory, or a quick port-forward):

```bash
kubectl port-forward svc/grafana 3000:80 &
# http://localhost:3000  (admin / whatever grafana-values.yaml set — admin123 by default)
```

---

## Step 7: Find the trace in Grafana

1. **Explore -> select Tempo** as the datasource
2. Search by service name (`part-order-service` or `part-inventory-service`)
   or paste a trace ID copied from the collector logs / app logs
3. The trace view shows both services' spans on one timeline, with the
   Feign call's client span (order-service) and server span
   (inventory-service) nested under it

**Log <-> trace correlation** (only if Loki is also installed): open a log
line containing `traceId=<hex>` in **Explore -> Loki**, click the line to
expand it, and a **TraceID** link appears — that's the `derivedFields`
config in [grafana-values.yaml](grafana-values.yaml) turning the traceId
logback already prints (see `logback-spring.xml` in both services) into a
jump straight to that trace in Tempo.

---

## Reaching the app

Same as [`../k8s/NOTES.md`](../k8s/NOTES.md), different namespace/NodePort:

- **minikube**: `minikube service part-order-service -n part-order-app-tracing`
- **kind / Docker Desktop**: `kubectl -n part-order-app-tracing port-forward svc/part-order-service 8080:8080`
  then open `http://localhost:8080`
- Direct NodePort: `http://<node-ip>:30083`

---

## Verification commands

```bash
# collector receiving + exporting spans
kubectl -n part-order-app-tracing logs deploy/otel-collector | grep -A5 "ResourceSpans"

# both apps' readiness/liveness
kubectl -n part-order-app-tracing get pods

# Tempo actually storing traces (bypasses Grafana entirely)
kubectl -n part-order-app-tracing port-forward svc/tempo 3100:3100 &
curl -s "http://localhost:3100/api/search?tags=service.name%3Dpart-order-service" | jq .

# app-side: confirm the OTLP exporter isn't erroring
kubectl -n part-order-app-tracing logs deploy/part-order-service | grep -i "Failed to export\|OtlpTracingAutoConfiguration"
```

---

## Troubleshooting

| Symptom | Likely cause |
| --- | --- |
| Collector log shows nothing after placing an order | Check `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` in the app's ConfigMap resolves to `otel-collector`'s ClusterIP Service — `kubectl -n part-order-app-tracing get svc otel-collector` |
| App logs show `ERROR i.o.e.internal.http.HttpExporter - Failed to export spans ... Connection refused` | Collector isn't up yet, or the Service/endpoint is wrong. Non-fatal — the app itself keeps serving requests, spans are just dropped until export succeeds |
| Two services show up as two *separate* traces instead of one | This is the exact failure mode this stack hit during development when `io.github.openfeign:feign-micrometer` was missing from `part-order-service/pom.xml` — confirm it's present and the jar was rebuilt/redeployed (`imagePullPolicy: Always` + `kubectl rollout restart`, see `../k8s/NOTES.md#iterating-on-code`) |
| Grafana Tempo datasource shows "no data" | Confirm the Service name/port match what Step 3 actually installed (`kubectl get svc tempo -n part-order-app-tracing`) — chart versions occasionally change default port; adjust `url` in `grafana-values.yaml` if needed |

## Cleanup

```bash
helm uninstall tempo -n part-order-app-tracing
kubectl delete -f part-inventory-service/ -f part-order-service/
kubectl delete -f otel-collector/
kubectl delete -f namespace.yaml
# if a standalone Grafana was installed for this stack only:
helm uninstall grafana
```
