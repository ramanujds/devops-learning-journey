#!/bin/bash
# Builds + pushes both Kafka-version images for linux/amd64 and linux/arm64.
# Run from part-order-app-kafka/. Separate image names from the REST version
# (ram1uj/part-*-service) so both apps can be deployed side by side.
set -e

docker buildx build --platform linux/amd64,linux/arm64 \
  -t ram1uj/part-inventory-service-kafka:latest \
  ./part-inventory-service \
  --push

docker buildx build --platform linux/amd64,linux/arm64 \
  -t ram1uj/part-order-service-kafka:latest \
  ./part-order-service \
  --push
