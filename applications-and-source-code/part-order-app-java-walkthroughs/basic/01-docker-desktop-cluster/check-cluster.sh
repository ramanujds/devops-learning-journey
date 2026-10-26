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

if kubectl top nodes >/dev/null 2>&1; then
  echo "OK   metrics-server answering (HPA will work)"
else
  echo "WARN metrics-server not answering - 'kubectl top' and the HPA need it"
fi
