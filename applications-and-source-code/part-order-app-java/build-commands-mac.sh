#!/bin/bash

docker buildx build --platform linux/amd64,linux/arm64 \
  -t ram1uj/part-inventory-service:latest \
  ./part-inventory-service \
  --push


docker buildx build --platform linux/amd64,linux/arm64 \
  -t ram1uj/part-order-service:latest \
  ./part-order-service \
  --push