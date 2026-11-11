# Part 7 — Cheat sheet

```bash
kubectl get crd                                  # all custom kinds
kubectl api-resources | grep example.com         # kinds from one group
kubectl explain webapp.spec                      # field docs from the schema

kubectl get wa                                   # custom objects work like built-in ones
kubectl get wa hello -o yaml
kubectl patch wa hello --subresource=status --type=merge -p '{"status":{"readyReplicas":1}}'

kubectl delete crd webapps.platform.example.com  # ⚠️ deletes every WebApp too
```

---

## Remember

- Apply the **CRD first**, then the objects (`kubectl wait --for=condition=Established crd/<name>`).
- `spec` is written by users, `status` by the controller.
- `ownerReferences` give you automatic cleanup.
- Empty `status`? The controller isn't running — check its logs.

---

## CRD starter template

```yaml
apiVersion: apiextensions.k8s.io/v1
kind: CustomResourceDefinition
metadata:
  name: <plural>.<group>
spec:
  group: <group>
  scope: Namespaced
  names: {kind: <Kind>, plural: <plural>}
  versions:
    - name: v1alpha1
      served: true
      storage: true
      subresources:
        status: {}
      schema:
        openAPIV3Schema:
          type: object
          properties:
            spec:
              type: object
              properties: {}
            status:
              type: object
              properties: {}
```

Back to [README.md](README.md)
