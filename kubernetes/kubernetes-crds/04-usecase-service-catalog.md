# Part 4 — Use case 1: a service catalog (CRD only)

**Problem:** "Who owns this service? Who do I page?" — the answer is in
an out-of-date wiki.

**Solution:** store it in the cluster as `ServiceCatalogEntry` objects,
kept in Git. No controller needed — we only want to store and query data.

Files: [examples/04-service-catalog/](examples/04-service-catalog/)

```mermaid
flowchart LR
    Git["entries.yaml\nin Git"] -->|kubectl apply| API["API server"]
    API -->|kubectl get| You["anyone on the team"]
```

---

## Step 1: Use the CRD from part 3

```bash
kubectl apply -f examples/03-validation/crd.yaml
kubectl delete sce --all
```

---

## Step 2: Add the part-order-app's components

`examples/04-service-catalog/entries.yaml` (one of three entries)

```yaml
apiVersion: platform.example.com/v1alpha1
kind: ServiceCatalogEntry
metadata:
  name: part-order-service
spec:
  owner: orders-team
  tier: critical
  oncall: orders-pager
```

```bash
kubectl apply -f examples/04-service-catalog/entries.yaml
kubectl get sce
# NAME                     OWNER            TIER       PORT
# part-inventory-service   inventory-team   standard   8080
# part-mysql               dba-team         critical   3306
# part-order-service       orders-team      critical   8080
```

---

## Step 3: Ask questions with kubectl

```bash
# who do I page?
kubectl get sce -o custom-columns=SERVICE:.metadata.name,TIER:.spec.tier,ONCALL:.spec.oncall

# one value, for a script
kubectl get sce part-order-service -o jsonpath='{.spec.oncall}'
# orders-pager
```

---

## Clean up

```bash
kubectl delete -f examples/04-service-catalog/entries.yaml
```

**Takeaway:** a CRD on its own is a validated, queryable record — useful
even without any code.

Next: [05-usecase-webapp-controller.md](05-usecase-webapp-controller.md)
