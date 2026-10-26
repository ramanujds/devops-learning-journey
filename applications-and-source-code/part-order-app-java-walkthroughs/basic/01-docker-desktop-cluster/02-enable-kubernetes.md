# Step 2 — Enable Kubernetes

> [Back to index](README.md) · Previous: [Step 1](01-concepts.md) · Next: [Step 3 — Verify and Contexts](03-verify-and-contexts.md)

## Goal

Turn on Docker Desktop's built-in cluster with enough resources for the app stacks, and start `check-cluster.sh` with its first two lines.

## Why this matters

The cluster is a set of containers inside Docker Desktop's VM, so it can only
use the CPU and memory you gave *Docker*, not your whole laptop. The failure is
quiet: nothing errors when resources are too small, Pods just sit in `Pending`
with `Insufficient cpu` or `Insufficient memory`, or get OOM-killed. The Spring
Boot services in this series ask for 256 Mi each, and MySQL, replicas and the
monitoring layers add up quickly. Set the resources before you start, not after
you are confused.

## 1. Give Docker Desktop enough resources

Docker Desktop, Settings, Resources:

| Stack you will run later | Suggested |
| --- | --- |
| `k8s/` (H2, 1 replica each) | 2 CPU / 4 GB |
| `k8s-with-mysql/` (MySQL, 2 replicas each) | 4 CPU / 6 GB |
| monitoring, tracing or Istio layers | 6 CPU / 8 GB |

Choose **Apply & Restart**.

## 2. Enable Kubernetes

Docker Desktop, Settings, Kubernetes, tick **Enable Kubernetes**, then **Apply & Restart**.

- The first start downloads the control-plane images and can take several minutes.
- The status in the bottom-left of the Docker Desktop window changes to "Kubernetes is running" when it is done.
- If your version asks for a cluster type, choose **Kubeadm** (a single node). Later steps assume one node. The **kind** type can create several nodes and names them differently.

## 3. Start the preflight script

In an empty working folder:

```bash
mkdir -p ~/k8s-lab && cd ~/k8s-lab
```

Create `check-cluster.sh`:

```bash
#!/bin/bash
# Sanity-check the Docker Desktop cluster before applying anything to it.
set -euo pipefail

echo "context: $(kubectl config current-context)"
kubectl get nodes
```

```bash
chmod +x check-cluster.sh
```

`set -euo pipefail` makes the script stop at the first failing command instead
of printing a misleading "all good" at the end. It is the line that makes Step
3's checks mean something.

## Try it

```bash
./check-cluster.sh
```

Expected:

```text
context: docker-desktop
NAME             STATUS   ROLES           AGE   VERSION
docker-desktop   Ready    control-plane   2m    v1.xx.x
```

If you see `connection refused` on `127.0.0.1:6443` instead, the cluster is not running yet. Wait for the status icon, or see the table below.

## Checkpoint

<details>
<summary>Full <code>check-cluster.sh</code> (after Step 2)</summary>

```bash
#!/bin/bash
# Sanity-check the Docker Desktop cluster before applying anything to it.
set -euo pipefail

echo "context: $(kubectl config current-context)"
kubectl get nodes
```

</details>

This is an early version; it is extended in Steps 3 and 5.

## Common mistakes

| Symptom | Cause | Fix |
| --- | --- | --- |
| `connection refused` on `127.0.0.1:6443` | Kubernetes not enabled, or still starting | Settings, Kubernetes; wait for "Kubernetes is running" |
| Stuck on "Starting..." for 10+ minutes | corrupt or half-pulled cluster state | Settings, Kubernetes, **Reset Kubernetes Cluster** |
| `context: gke_...` printed instead of `docker-desktop` | kubeconfig points at another cluster | `kubectl config use-context docker-desktop` (Step 3 automates the check) |
| `Permission denied: ./check-cluster.sh` | script not executable | `chmod +x check-cluster.sh` |

Next: **[Step 3 — Verify and Contexts](03-verify-and-contexts.md)**.
