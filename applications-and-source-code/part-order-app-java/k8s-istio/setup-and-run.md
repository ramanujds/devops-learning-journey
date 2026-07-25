# Setting This Up and Testing It, Step by Step

This is a record of exactly how `k8s-istio/` was stood up and verified on
a real (throwaway) cluster — every command, and what it's actually doing.
Follow it in order to reproduce the same test yourself.

For *why* any of this exists, see [README.md](README.md). This file is
just the "how," in plain language.

---

## What you need installed first

| Tool | What it's for |
| --- | --- |
| `docker` | runs the actual containers underneath everything |
| `kind` | creates a real (if small) Kubernetes cluster inside Docker |
| `kubectl` | talks to that cluster |
| `helm` | installs Istio itself |

Check you have them:

```bash
docker version --format '{{.Server.Version}}'
kind version
kubectl version --client
helm version --short
```

---

## Step 1: Create a throwaway cluster

```bash
kind create cluster --name istio-demo
```

This builds a one-node Kubernetes cluster as a Docker container and
points `kubectl` at it automatically. Nothing about this step is
Istio-specific yet — it's just "give me somewhere to run Kubernetes
stuff."

```bash
kubectl get nodes
# istio-demo-control-plane   Ready
```

---

## Step 2: Install Istio itself

Istio isn't part of Kubernetes — it has to be installed like any other
piece of software, and it comes in two Helm charts:

```bash
helm repo add istio https://istio-release.storage.googleapis.com/charts
helm repo update
```

```bash
kubectl create namespace istio-system

# chart 1: the CRDs — teaches the API server new kinds like
# PeerAuthentication, the object this whole demo revolves around
helm install istio-base istio/base -n istio-system --wait

# chart 2: istiod — the actual control plane process
helm install istiod istio/istiod -n istio-system --wait
```

Why two charts: `istio-base` only registers the new object types
(CRDs — see `04-crds.md` in the notes if that's unfamiliar). `istiod` is
the running program that actually watches those objects and configures
every sidecar. Installing base first means the CRDs already exist by the
time istiod needs them.

```bash
kubectl get pods -n istio-system
# istiod-xxxx   1/1   Running
```

One more thing worth checking — this is what makes sidecar injection
possible at all:

```bash
kubectl get mutatingwebhookconfigurations | grep istio
# istio-sidecar-injector
```

That webhook is what watches for new Pods in a labeled namespace and
quietly adds the sidecar container before the Pod even starts.

---

## Step 3: Apply this app's manifests

```bash
kubectl apply -f namespace.yaml
kubectl apply -f istio/
kubectl apply -f part-inventory-service/
kubectl apply -f part-order-service/
```

Order matters a little: the namespace has to exist before anything can
be created inside it, and it needs the `istio-injection: enabled` label
*before* the app Pods are created — the webhook only acts at Pod
creation time, so labeling a namespace after Pods already exist in it
does nothing to Pods already running.

---

## Step 4: Confirm the sidecar actually got injected

```bash
kubectl get pods
```

```text
NAME                                      READY   STATUS    RESTARTS
part-inventory-service-xxxx-xxxxx         2/2     Running   0
part-order-service-xxxx-xxxxx              2/2     Running   0
```

`2/2`, not `1/2`, is the whole point — it means each Pod is running the
app container **and** the `istio-proxy` sidecar. If you see `1/2`, the
Pod was likely created before the namespace label was applied; delete it
(`kubectl delete pod <name>`) and let the Deployment recreate it.

A small, genuinely interesting detail found while testing this: on a
recent Istio version, `istio-proxy` doesn't show up as a normal
container — it shows up as an **init container with `restartPolicy:
Always`**:

```bash
kubectl get pod -l app=part-order-service \
  -o jsonpath='{.spec.initContainers[?(@.name=="istio-proxy")].restartPolicy}'
# Always
```

That's the "native sidecar" feature covered in
`kubernetes-intermediate/05-sidecars.md` — Kubernetes starts it first,
keeps it running for the Pod's whole life, and only then starts the real
app container. `kubectl get pods` still reports it as a normal `2/2`,
so nothing about using this app changes either way.

---

## Step 5: Prove mTLS is actually happening

It's not enough that the sidecar exists — check that traffic between
the two services is really encrypted:

```bash
kubectl exec deploy/part-order-service -c istio-proxy -- \
  openssl s_client -connect part-inventory-service:8080 -brief
```

Look for:

```text
CONNECTION ESTABLISHED
Protocol version: TLSv1.3
Peer certificate:
depth=1 O = cluster.local
```

`O = cluster.local` is the giveaway — that's Istio's default trust
domain, meaning the certificate came from `istiod`, not from either
Spring Boot app (which never configured TLS at all).

---

## Step 6: Prove `STRICT` mode actually blocks outsiders

Having mTLS *available* and having it *enforced* are different claims.
Test the second one by trying to reach the inventory service from a Pod
that has **no sidecar at all** — a plain pod in the default namespace:

```bash
kubectl run plaintext-test --image=curlimages/curl -n default --rm -i --restart=Never --command -- \
  curl -s -o /dev/null -w "status: %{http_code}\n" --max-time 5 \
  http://part-inventory-service.part-order-app-istio.svc.cluster.local:8080/api/parts
```

Expected result: it fails — `status: 000` and a connection error, not a
`200`. That failure is `PeerAuthentication`'s `STRICT` mode doing its
job: a client with no mesh certificate simply cannot get in, no matter
how correct the URL is.

---

## Step 7: Test the app for real — place an order

Port-forward both services so you can reach them from your own machine:

```bash
kubectl port-forward svc/part-order-service 18080:8080 &
kubectl port-forward svc/part-inventory-service 18081:8080 &
```

Check the app is alive:

```bash
curl http://localhost:18080/actuator/health
# {"status":"UP", ...}
```

See what's in stock before ordering:

```bash
curl http://localhost:18081/api/parts
# look for "sku":"SEAT-SAFE45-001","stock":100
```

Place an order. This is the one step where a mistake actually happened
during testing, worth calling out: the request body field is `sku`, not
`partSku` — check `OrderRequestDto.java` if you're ever unsure of a
field name, rather than guessing:

```bash
curl -X POST http://localhost:18080/api/part-orders/place-order \
  -H "Content-Type: application/json" \
  -d '{"sku":"SEAT-SAFE45-001","quantity":3}'
# {"status":"Order placed successfully","totalPrice":90.0, ...}
```

Confirm stock actually went down — this is the real end-to-end proof
that `part-order-service` → (through the mTLS mesh) → `part-inventory-service`
→ (H2 database) all worked together:

```bash
curl http://localhost:18081/api/parts
# stock is now 97, not 100
```

---

## Step 8: Clean up

```bash
kill %1 %2                          # stop the two port-forwards
kind delete cluster --name istio-demo
```

Deleting the `kind` cluster removes everything at once — Istio, both
app Deployments, all of it. Nothing here was applied to a real/shared
cluster, so there's nothing else to undo.

---

## Quick command reference

```bash
# create + install
kind create cluster --name istio-demo
helm install istio-base istio/base -n istio-system --wait
helm install istiod istio/istiod -n istio-system --wait

# apply this app
kubectl apply -f namespace.yaml
kubectl apply -f istio/
kubectl apply -f part-inventory-service/
kubectl apply -f part-order-service/

# check it
kubectl get pods                      # expect 2/2 on both
kubectl exec deploy/part-order-service -c istio-proxy -- \
  openssl s_client -connect part-inventory-service:8080 -brief

# tear down
kind delete cluster --name istio-demo
```
