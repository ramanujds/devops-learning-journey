# Part 2 — Your first CRD

Create a new kind, `ServiceCatalogEntry`, and one object of it.
Files: [examples/02-first-crd/](examples/02-first-crd/)

---

## Step 1: The CRD

`examples/02-first-crd/crd.yaml`

```yaml
apiVersion: apiextensions.k8s.io/v1
kind: CustomResourceDefinition
metadata:
  name: servicecatalogentries.platform.example.com   # <plural>.<group>
spec:
  group: platform.example.com
  scope: Namespaced
  names:
    kind: ServiceCatalogEntry
    plural: servicecatalogentries
    shortNames: [sce]
  versions:
    - name: v1alpha1
      served: true
      storage: true
      schema:
        openAPIV3Schema:
          type: object
          properties:
            spec:
              type: object
              properties:
                owner:
                  type: string
                tier:
                  type: string
                port:
                  type: integer
```

- `group` + version → the `apiVersion` you'll write: `platform.example.com/v1alpha1`
- `names` → the `kind`, and what you type in `kubectl get`
- `schema` → which fields are allowed

---

## Step 2: Install it

```bash
kubectl apply -f examples/02-first-crd/crd.yaml
kubectl get crd
kubectl api-resources | grep sce
```

---

## Step 3: Create an object

`examples/02-first-crd/inventory-entry.yaml`

```yaml
apiVersion: platform.example.com/v1alpha1
kind: ServiceCatalogEntry
metadata:
  name: part-inventory-service
spec:
  owner: inventory-team
  tier: standard
  port: 8080
```

```bash
kubectl apply -f examples/02-first-crd/inventory-entry.yaml
kubectl get sce
kubectl get sce part-inventory-service -o yaml
```

It works like any built-in object: `get`, `describe`, `edit`, `delete`.

---

## Step 4: A typo is rejected

`examples/02-first-crd/typo-entry.yaml` has `prot:` instead of `port:`

```bash
kubectl apply -f examples/02-first-crd/typo-entry.yaml
# Error ... unknown field "spec.prot"
```

The schema already protects you.

> ⚠️ `kubectl delete crd <name>` also deletes **every object** of that kind.

Next: [03-validation-and-defaults.md](03-validation-and-defaults.md)
