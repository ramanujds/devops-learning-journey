# Step 6 — Recap and Exercises

> [Back to index](README.md) · Previous: [Step 5](05-metrics-server.md)

## Quick reference

| Concept | Where it lives |
| --- | --- |
| Enable the cluster | Docker Desktop, Settings, Kubernetes |
| Which cluster am I talking to | `kubectl config current-context`; guarded by `check-cluster.sh` |
| Is the cluster healthy | `./check-cluster.sh`; `kubectl get nodes`; `kubectl get pods -A` |
| Deploy-expose-reach loop | [hello.yaml](hello.yaml) (Namespace, Deployment, NodePort Service) |
| Resource metrics for `kubectl top` and the HPA | metrics-server in `kube-system`, patched with `--kubelet-insecure-tls` |
| Wipe everything | Docker Desktop, Settings, Kubernetes, **Reset Kubernetes Cluster** |
| Turn it off | untick **Enable Kubernetes** |

## Gotchas

| Gotcha | Where it comes from |
| --- | --- |
| `connection refused` on `127.0.0.1:6443` means Kubernetes is not running, not that your manifest is wrong | seen when starting this series; the first symptom of a stopped Docker Desktop cluster |
| `kubectl apply` goes to the *current* context, whatever it is | kubeconfig behaviour; a cloud context often sits beside `docker-desktop` |
| `node-exporter` can fail to start on Docker Desktop because the VM does not support the mount propagation it asks for | documented in [k8s-cluster-monitoring-logging/setup-and-run.md](../../../part-order-app-java/k8s-cluster-monitoring-logging/setup-and-run.md) |
| Docker Desktop is one node, so anything needing several nodes (anti-affinity, node failure drills) cannot be shown here | single-node design; use `kind` with a config file for multi-node |
| HPA shows `<unknown>` until metrics-server is healthy | Step 5 |
| Once metrics-server works, the `k8s/` inventory HPA scales from 1 to its max of 5 even when idle | observed when testing this series: an idle JVM uses about 250 Mi against a 256 Mi memory request, so memory reads about 98% against the HPA's 80% target. Raise the memory request or the target to stop it |

## Discussion questions

1. The API server listens on `127.0.0.1:6443`. What does that tell you about how `kubectl` and the cluster are connected, and what would change for a GKE cluster?
2. Why does `check-cluster.sh` fail on a wrong context but only warn when metrics are missing?
3. You deleted a `hello` Pod and a new one appeared. Which component noticed, and what was it comparing?
4. The Service selects Pods by label. What happens to traffic if you relabel one Pod?
5. Docker Desktop shares the Docker daemon with the cluster. What does that make convenient, and what could surprise you?

## Exercises

1. Change `replicas` in `hello.yaml` to 4, re-apply, and watch with `kubectl get pods -n smoke-test -w`. Then set it back to 1.
2. Run `kubectl scale deployment hello -n smoke-test --replicas=3` without editing the file, then `kubectl apply -f hello.yaml`. Which number wins, and why?
3. Make `check-cluster.sh` accept a `--no-metrics` flag that skips the last block.
4. Add a check that fails if fewer than 4 GB of memory are allocatable on the node (`kubectl get node -o jsonpath='{.items[0].status.allocatable.memory}'`).
5. Create a second namespace, put a copy of the Deployment in it, and show that the same Service name works in both without clashing.
6. Rebuild `check-cluster.sh` from memory in an empty folder, then diff it against the reference:

   ```bash
   diff check-cluster.sh /path/to/part-order-app-java-walkthroughs/basic/01-docker-desktop-cluster/check-cluster.sh
   ```

## What's next

You have a verified cluster. Next, turn the two Spring Boot services into images:
**[Walkthrough 02 — Build the Docker Images](../02-build-docker-images/README.md)**.
