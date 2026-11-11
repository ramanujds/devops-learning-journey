#!/usr/bin/env bash
# A tiny WebApp controller, using only kubectl.
# Every 5 seconds, for each WebApp: read it -> apply a Deployment + Service -> write status.

while true; do
  for NAME in $(kubectl get webapps -o jsonpath='{.items[*].metadata.name}'); do

    # 1. READ what the user wants
    IMAGE=$(kubectl get webapp "$NAME" -o jsonpath='{.spec.image}')
    REPLICAS=$(kubectl get webapp "$NAME" -o jsonpath='{.spec.replicas}')
    PORT=$(kubectl get webapp "$NAME" -o jsonpath='{.spec.port}')
    ENV=$(kubectl get webapp "$NAME" -o jsonpath='{.spec.env}')
    OWNER_UID=$(kubectl get webapp "$NAME" -o jsonpath='{.metadata.uid}')

    # 2. APPLY the Deployment + Service ("unchanged" lines are hidden)
    kubectl apply -f - <<YAML | grep -v unchanged
apiVersion: apps/v1
kind: Deployment
metadata:
  name: $NAME
  ownerReferences:   # delete the WebApp -> Kubernetes deletes this Deployment too
    - {apiVersion: platform.example.com/v1alpha1, kind: WebApp, name: $NAME, uid: $OWNER_UID}
spec:
  replicas: $REPLICAS
  selector:
    matchLabels: {app: $NAME}
  template:
    metadata:
      labels: {app: $NAME}
    spec:
      containers:
        - name: app
          image: $IMAGE
          ports: [{containerPort: $PORT}]
          ${ENV:+env: $ENV}   # adds an env line only if the WebApp has env
---
apiVersion: v1
kind: Service
metadata:
  name: $NAME
  ownerReferences:
    - {apiVersion: platform.example.com/v1alpha1, kind: WebApp, name: $NAME, uid: $OWNER_UID}
spec:
  selector: {app: $NAME}
  ports: [{port: $PORT}]
YAML

    # 3. WRITE STATUS: how many pods are ready
    READY=$(kubectl get deployment "$NAME" -o jsonpath='{.status.readyReplicas}')
    kubectl patch webapp "$NAME" --subresource=status --type=merge \
      -p "{\"status\":{\"readyReplicas\":${READY:-0}}}" > /dev/null
  done
  sleep 5
done
