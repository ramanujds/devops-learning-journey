# Part 1 — What is a service mesh?

Goal: build the mental model before installing anything. No commands in
this part.

---

## The problem: every service re-solves the same network problems

Once you have more than a few services calling each other, every one of
them needs the same things:

```mermaid
flowchart TB
    subgraph "Service A (Java)"
        A["business logic"] --- AL["retries, timeouts,\nTLS certs, metrics,\nauth checks"]
    end
    subgraph "Service B (Go)"
        B["business logic"] --- BL["retries, timeouts,\nTLS certs, metrics,\nauth checks"]
    end
    subgraph "Service C (Node.js)"
        C["business logic"] --- CL["retries, timeouts,\nTLS certs, metrics,\nauth checks"]
    end
```

- the same logic gets written 3 times, in 3 libraries, with 3 sets of defaults
- changing a retry policy means a code change + redeploy in every service
- nobody can easily prove that "all internal traffic is encrypted"

A service mesh moves all of that **out of the app** and into the
infrastructure.

---

## The idea: put a proxy next to every app

This is the sidecar pattern from
[05-sidecars.md](../kubernetes-intermediate/05-sidecars.md), applied to
**every** Pod automatically.

```mermaid
flowchart LR
    subgraph "Pod: client"
        C["app"] --> CP["proxy\n(Envoy)"]
    end
    subgraph "Pod: backend"
        BP["proxy\n(Envoy)"] --> B["app"]
    end
    CP -->|"mTLS, retries,\nmetrics, auth"| BP
```

- the app still sends plain `http://backend`, so **no code changes**
- the proxy on the sending side does the routing, retries, timeouts, and encryption
- the proxy on the receiving side decrypts, checks who's calling, and records metrics
- the proxy doesn't care what language the app is written in

---

## Data plane vs control plane

```mermaid
flowchart TB
    subgraph CP["Control plane"]
        Istiod["istiod\n• turns your YAML into proxy config\n• issues a TLS cert to every proxy\n• watches Services + Endpoints"]
    end
    subgraph DP["Data plane"]
        P1["Envoy\n(client Pod)"]
        P2["Envoy\n(backend-v1 Pod)"]
        P3["Envoy\n(backend-v2 Pod)"]
    end
    You["you: kubectl apply\nVirtualService, etc."] --> Istiod
    Istiod -->|"config + certs (xDS)"| P1
    Istiod -->|"config + certs"| P2
    Istiod -->|"config + certs"| P3
    P1 <-->|"actual traffic"| P2
    P1 <-->|"actual traffic"| P3
```

| | Data plane | Control plane |
| --- | --- | --- |
| What | The Envoy proxies, one per Pod | `istiod`, one Deployment in `istio-system` |
| Touches requests? | Yes, every request goes through it | No, it never sees app traffic |
| If it goes down | That Pod's traffic stops | Traffic keeps flowing on the last config; only new config/certs wait |

---

## How the sidecar gets there, and how traffic reaches it

```mermaid
sequenceDiagram
    participant You
    participant API as API server
    participant Hook as istiod (webhook)
    participant Pod

    You->>API: create Pod (in a namespace labeled istio-injection=enabled)
    API->>Hook: mutating admission webhook
    Hook-->>API: add istio-proxy container (+ init step)
    API->>Pod: start Pod with 2 containers
    Note over Pod: init step writes iptables rules:<br/>all in/out traffic → Envoy
```

- **Injection** is done by a mutating admission webhook
  ([10-admission-controllers.md](../kubernetes-intermediate/10-admission-controllers.md)).
  It only acts when a Pod is created, so existing Pods need a restart.
- **Interception** uses iptables rules inside the Pod's network namespace.
  The app connects to `backend:80`, and the kernel quietly sends that
  connection to Envoy on `localhost:15001` instead.
- That's why `kubectl get pods` shows `2/2`: the app plus `istio-proxy`.

---

## Where a mesh sits next to things you already know

```mermaid
flowchart LR
    User["browser"] -->|"north-south\n(into the cluster)"| Ing["Ingress / Gateway API"]
    Ing --> FE["frontend"]
    FE -->|"east-west\n(service to service)"| Mesh["service mesh"]
    Mesh --> BE["backend"]
```

| Tool | Answers | Layer |
| --- | --- | --- |
| [Service](../kubernetes-intro/05-services.md) | *Which* Pod gets this connection? | L4 (IP:port) |
| [Ingress / Gateway API](../kubernetes-intro/09-ingress.md) | How does outside traffic get *in*? | L7, at the edge |
| [NetworkPolicy](../kubernetes-intermediate/04-networking-policy.md) | Which IPs/ports may connect at all? | L3/L4 |
| Service mesh | What *happens* to each request between services: encryption, identity, retries, % splits, metrics | L7, everywhere |

A mesh **doesn't replace** Services. Envoy reads the same Endpoints
Kubernetes already tracks. It adds an HTTP-aware layer on top.

---

## Istio's building blocks used in this series

| Kind | What it does | Part |
| --- | --- | --- |
| `DestinationRule` | Defines *subsets* of a service (e.g. v1/v2 by label), plus connection policies | 3 |
| `VirtualService` | Routing rules: weights, header matches, faults, timeouts, retries | 3 |
| `PeerAuthentication` | mTLS mode for receiving workloads: `PERMISSIVE` or `STRICT` | 4 |
| `AuthorizationPolicy` | Who (identity) may call what (method/path) | 4 |
| `Gateway` | Edge traffic into the mesh (not used here; see the Gateway API notes) | — |

They're all CRDs
([kubernetes-crds/](../kubernetes-crds/README.md)), so `kubectl get`,
`apply`, and `describe` work on them like on any other object.

---

## Sidecar mode vs ambient mode

Istio also has a newer **ambient mode** with no per-Pod sidecar:

```mermaid
flowchart LR
    subgraph Sidecar["sidecar mode (this series)"]
        A1["app + Envoy"] --> B1["app + Envoy"]
    end
    subgraph Ambient["ambient mode"]
        A2["app"] --> Z["ztunnel\n(one per node, L4 mTLS)"]
        Z --> W["waypoint proxy\n(optional, L7 rules)"]
        W --> B2["app"]
    end
```

- **sidecar**: the most mature mode and the one most docs and examples
  show. It costs one proxy (CPU/memory) per Pod.
- **ambient**: cheaper and needs no Pod restarts, but L7 features need a
  waypoint deployed as well.

The YAML in Parts 3 and 4 is the same in both modes. Only the install
and onboarding steps differ. This series uses sidecar mode because the
proxy is visible in every Pod, which makes it easier to learn.

---

## Takeaway

A service mesh is an Envoy proxy next to every Pod (the data plane),
configured centrally by `istiod` (the control plane). Your apps keep
making plain HTTP calls. Encryption, routing, resilience, and metrics
all happen in the proxies.

Next: [Part 2 — install Istio and the demo app](02-install-istio-and-demo-app.md)
