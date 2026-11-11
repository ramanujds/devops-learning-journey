# Kubernetes CRDs — step by step

Learn CustomResourceDefinitions by building three small, real examples.
One-page overview instead: [04-crds.md](../kubernetes-advanced/04-crds.md).

| # | Part | You build |
| --- | --- | --- |
| 1 | [What are CRDs?](01-what-are-crds.md) | the idea (no commands) |
| 2 | [Your first CRD](02-your-first-crd.md) | a `ServiceCatalogEntry` kind |
| 3 | [Validation and defaults](03-validation-and-defaults.md) | the same kind, with rules |
| 4 | [Use case 1: service catalog](04-usecase-service-catalog.md) | a catalog of the part-order-app — CRD only |
| 5 | [Use case 2: WebApp + shell controller](05-usecase-webapp-controller.md) | a `WebApp` kind that deploys the part-order-app |
| 6 | [Use case 3: WebApp operator in Go](06-usecase-webapp-operator-kubebuilder.md) | the same `WebApp`, built with Kubebuilder |
| 7 | [Cheat sheet](07-cheatsheet.md) | — |

---

## Setup

```bash
kind create cluster --name crd-lab
kubectl config current-context      # must say kind-crd-lab

cd kubernetes/kubernetes-crds       # all commands run from here
```

Clean up when done:

```bash
kind delete cluster --name crd-lab
```
