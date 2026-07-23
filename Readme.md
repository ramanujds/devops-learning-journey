# DevOps Learning Journey

Notes and a working reference app for learning Docker and Kubernetes,
written for someone who's comfortable with one and picking up the other.

## How this repo is organized

- **`docker/`** and **`kubernetes-intro/` / `kubernetes-intermediate/`** —
  study notes, one topic per file, numbered in the order they're meant to
  be read.
- **`part-order-app-java/`** — a real two-service Spring Boot app, plus two
  different Kubernetes manifest sets for running it. The notes' examples
  use throwaway `nginx`/`busybox` Pods; this is the "how it looks for a
  real app" counterpart.

Every note file follows the same format: `##` sections separated by `---`
(read like slides), a Mermaid diagram per concept, and runnable
`kubectl`/`docker` commands over long prose. Files cross-link to each
other with relative Markdown links instead of repeating an already-covered
concept.

---

## Docker

| File | Covers |
| --- | --- |
| [01-docker-intro.md](docker/01-docker-intro.md) | Why Docker exists — the "works on my machine" problem, illustrated for a Java and a Node.js app |
| [02-docker-images-containers.md](docker/02-docker-images-containers.md) | Image vs. container, the container lifecycle, layers — all using `nginx` |
| [03-docker-build.md](docker/03-docker-build.md) | Writing a Dockerfile, multi-stage builds (Java + Node examples), layer caching, `docker build` / `buildx` |
| [04-docker-best-practice.md](docker/04-docker-best-practice.md) | Image size, security (non-root, secrets, scanning), and runtime hygiene (signals, health checks) |

## Kubernetes — intro

| File | Covers |
| --- | --- |
| [01-intro.md](kubernetes-intro/01-intro.md) | What problem Kubernetes solves over plain Docker, the desired-state/reconciliation-loop model |
| [02-kubernetes-components.md](kubernetes-intro/02-kubernetes-components.md) | Tour of the core building blocks (Pod → ReplicaSet → Deployment → Service → Ingress), all via `kubectl` |
| [03-pods-and-services.md](kubernetes-intro/03-pods-and-services.md) | Pod-to-Pod communication over a raw IP vs. through a Service |
| [04-deployments.md](kubernetes-intro/04-deployments.md) | Why a bare Pod isn't enough, and what a Deployment adds — including why labels matter |
| [05-services.md](kubernetes-intro/05-services.md) | The cluster networking model and all five Service types (ClusterIP, NodePort, LoadBalancer, Headless, ExternalName) |
| [06-cluster-and-nodes.md](kubernetes-intro/06-cluster-and-nodes.md) | What a cluster/Node actually is, and running one locally with Docker Desktop, minikube, or kind (incl. multi-node) |
| [07-configmap-and-secrets.md](kubernetes-intro/07-configmap-and-secrets.md) | ConfigMaps vs. Secrets, where they're stored (etcd), env vars vs. volume mounts |
| [08-deployment-vs-statefulsets.md](kubernetes-intro/08-deployment-vs-statefulsets.md) | Why a database needs a StatefulSet instead of a Deployment, using MySQL |
| [09-ingress.md](kubernetes-intro/09-ingress.md) | Routing HTTP into the cluster, popular Ingress Controllers, and Ingress vs. Gateway API |

## Kubernetes — intermediate

| File | Covers |
| --- | --- |
| [01-debugging-common-issues.md](kubernetes-intermediate/01-debugging-common-issues.md) | Diagnosing `Pending`, `CrashLoopBackOff`, `OOMKilled`, no-endpoints Services, and more |
| [02-scaling-vpa-hpa.md](kubernetes-intermediate/02-scaling-vpa-hpa.md) | Horizontal (HPA) vs. vertical (VPA) autoscaling, and the metrics-server pipeline both depend on |
| [03-rolling-update.md](kubernetes-intermediate/03-rolling-update.md) | How rolling updates actually work under the hood, plus Recreate/Blue-Green/Canary strategies |
| [04-networking-policy.md](kubernetes-intermediate/04-networking-policy.md) | Locking down the flat cluster network with NetworkPolicy (ingress + egress rules) |
| [05-sidecars.md](kubernetes-intermediate/05-sidecars.md) | Multi-container Pods — logging, service-mesh proxy, Vault Agent, ambassador, and adapter patterns |
| [06-helm.md](kubernetes-intermediate/06-helm.md) | Packaging and templating manifests across environments instead of hand-copying YAML |

---

## The reference app: `part-order-app-java/`

Two independent Spring Boot services:

- **`part-inventory-service`** — owns part inventory (SKU, stock count)
- **`part-order-service`** — the user-facing UI; calls
  `part-inventory-service` over REST (OpenFeign) to check stock and place
  orders

Deployable two ways, side by side:

- **`k8s/`** — `dev` profile, in-memory H2, 1 replica each — the simplest
  version to run locally
- **`k8s-with-mysql/`** — `prod` profile, one shared MySQL `StatefulSet`
  backing both services, 2 replicas each

Each has its own `NOTES.md` with an architecture diagram, request-flow
sequence diagram, and apply/troubleshooting steps:
[`k8s/NOTES.md`](part-order-app-java/k8s/NOTES.md) ·
[`k8s-with-mysql/NOTES.md`](part-order-app-java/k8s-with-mysql/NOTES.md)

See [`CLAUDE.md`](CLAUDE.md) for build/run commands and the full
architecture rundown.
