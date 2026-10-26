# Step 3 — Verify the Cluster and Guard the Context

> [Back to index](README.md) · Previous: [Step 2](02-enable-kubernetes.md) · Next: [Step 4 — Smoke Test](04-smoke-test.md)

## Goal

Make `check-cluster.sh` fail loudly when you are pointed at the wrong cluster, the API server is unreachable, or the node and system Pods are not healthy.

## Why this matters

"The cluster is up" is three separate claims, and each fails differently:

1. **Right target?** `kubectl` goes to the current context. The wrong one means your commands run against a different cluster, possibly one that is not yours to break.
2. **Reachable?** The context can be correct while the API server is down (the `connection refused` from Step 2).
3. **Healthy?** The API can answer while the node is `NotReady` or `coredns` is crash-looping. Then your first `apply` "works" and your Pods mysteriously never start, or Services cannot resolve each other's names.

Checking in that order also gives the most useful error first. A script that
only prints `kubectl get nodes` makes you read the output and notice the problem yourself.

## 1. Guard the context

Replace the `echo` line with a check that exits when the context is not the expected one. `EXPECTED_CONTEXT` can be overridden from the environment, which lets you test the guard without owning a second cluster.

```bash
EXPECTED_CONTEXT="${EXPECTED_CONTEXT:-docker-desktop}"

ctx="$(kubectl config current-context)"
if [ "$ctx" != "$EXPECTED_CONTEXT" ]; then
  echo "FAIL context is '$ctx', expected '$EXPECTED_CONTEXT'"
  echo "     fix with: kubectl config use-context $EXPECTED_CONTEXT"
  exit 1
fi
echo "OK   context: $ctx"
```

## 2. Check the API server answers

```bash
if ! kubectl get nodes >/dev/null 2>&1; then
  echo "FAIL API server not reachable - is Kubernetes enabled in Docker Desktop?"
  exit 1
fi
```

The `if !` form matters: under `set -e` a bare failing `kubectl get nodes` would end the script with kubectl's raw error. Wrapping it lets you print the hint instead.

## 3. Wait for the node, then inspect the system Pods

```bash
kubectl wait --for=condition=Ready node --all --timeout=60s >/dev/null
echo "OK   node(s) Ready: $(kubectl get nodes --no-headers | wc -l | tr -d ' ')"

not_ready="$(kubectl get pods -n kube-system --no-headers | grep -v -E 'Running|Completed' || true)"
if [ -n "$not_ready" ]; then
  echo "WARN kube-system pods not Running:"
  echo "$not_ready"
else
  echo "OK   kube-system pods Running"
fi
```

`|| true` is needed because `grep -v` exits 1 when there is nothing left to print, which is the *good* case, and `pipefail` would otherwise turn that into a script failure.

## Try it

```bash
./check-cluster.sh
```

Expected:

```text
OK   context: docker-desktop
OK   node(s) Ready: 1
OK   kube-system pods Running
```

Trigger each failure on purpose:

```bash
# wrong context (no second cluster needed)
EXPECTED_CONTEXT=nope ./check-cluster.sh
# FAIL context is 'docker-desktop', expected 'nope'

# API down: untick Enable Kubernetes in Docker Desktop, then
./check-cluster.sh
# FAIL API server not reachable - is Kubernetes enabled in Docker Desktop?
```

Re-enable Kubernetes afterwards.

Look at what the script is checking by hand too:

```bash
kubectl cluster-info
kubectl get nodes -o wide
kubectl get pods -A
```

In `kube-system` you should recognise the components from the concepts step: `kube-apiserver`, `etcd`, `kube-scheduler`, `kube-controller-manager`, `kube-proxy`, plus `coredns` (cluster DNS, which is what makes `http://part-inventory-service:8080` work later).

## Checkpoint

<details>
<summary>Full <code>check-cluster.sh</code> (after Step 3)</summary>

```bash
#!/bin/bash
# Sanity-check the Docker Desktop cluster before applying anything to it.
set -euo pipefail

EXPECTED_CONTEXT="${EXPECTED_CONTEXT:-docker-desktop}"

ctx="$(kubectl config current-context)"
if [ "$ctx" != "$EXPECTED_CONTEXT" ]; then
  echo "FAIL context is '$ctx', expected '$EXPECTED_CONTEXT'"
  echo "     fix with: kubectl config use-context $EXPECTED_CONTEXT"
  exit 1
fi
echo "OK   context: $ctx"

if ! kubectl get nodes >/dev/null 2>&1; then
  echo "FAIL API server not reachable - is Kubernetes enabled in Docker Desktop?"
  exit 1
fi
kubectl wait --for=condition=Ready node --all --timeout=60s >/dev/null
echo "OK   node(s) Ready: $(kubectl get nodes --no-headers | wc -l | tr -d ' ')"

not_ready="$(kubectl get pods -n kube-system --no-headers | grep -v -E 'Running|Completed' || true)"
if [ -n "$not_ready" ]; then
  echo "WARN kube-system pods not Running:"
  echo "$not_ready"
else
  echo "OK   kube-system pods Running"
fi
```

</details>

Step 5 adds the last block (metrics).

## Common mistakes

| Symptom | Cause | Fix |
| --- | --- | --- |
| Script exits silently after the context line | `kubectl get nodes` called bare under `set -e` | keep it inside `if ! ... ; then` as shown |
| Script fails at the `grep` line on a healthy cluster | missing `\|\| true` with `pipefail` | add `\|\| true` after the `grep -v` |
| `kubectl wait` times out | node is `NotReady`, usually Docker Desktop is short on resources | raise resources (Step 2), or Reset Kubernetes Cluster |
| `WARN kube-system pods not Running` right after enabling | system Pods still starting | wait a minute and re-run |

Next: **[Step 4 — Smoke Test](04-smoke-test.md)**.
