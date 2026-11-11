# Part 3 — Validation and defaults

Make the API server reject bad objects and fill in defaults.
Files: [examples/03-validation/](examples/03-validation/)

---

## Step 1: Add rules to the schema

`examples/03-validation/crd.yaml` (the schema part)

```yaml
spec:
  type: object
  required: [owner]                         # must be set
  properties:
    owner:
      type: string
    tier:
      type: string
      enum: [critical, standard]            # only these values
      default: standard                     # used when left out
    port:
      type: integer
      minimum: 1                            # allowed range
      maximum: 65535
      default: 8080
    oncall:
      type: string
```

And extra columns for `kubectl get`:

```yaml
additionalPrinterColumns:
  - {name: Owner, type: string,  jsonPath: .spec.owner}
  - {name: Tier,  type: string,  jsonPath: .spec.tier}
  - {name: Port,  type: integer, jsonPath: .spec.port}
```

```bash
kubectl apply -f examples/03-validation/crd.yaml
kubectl delete sce --all          # start fresh
```

---

## Step 2: Defaults are filled in

`examples/03-validation/valid-entry.yaml` only sets `owner`:

```bash
kubectl apply -f examples/03-validation/valid-entry.yaml
kubectl get sce
# NAME                     OWNER            TIER       PORT
# part-inventory-service   inventory-team   standard   8080
```

---

## Step 3: Bad values are rejected

`examples/03-validation/invalid-entry.yaml` — no owner, `tier: gold`, `port: 70000`

```bash
kubectl apply -f examples/03-validation/invalid-entry.yaml
# * spec.port: ... should be less than or equal to 65535
# * spec.tier: Unsupported value: "gold": supported values: "critical", "standard"
# * spec.owner: Required value
```

---

## Summary

| Rule | YAML |
| --- | --- |
| field must exist | `required: [owner]` |
| fixed choices | `enum: [...]` |
| value if missing | `default: ...` |
| number range | `minimum` / `maximum` |

Keep this CRD installed. Next: [04-usecase-service-catalog.md](04-usecase-service-catalog.md)
