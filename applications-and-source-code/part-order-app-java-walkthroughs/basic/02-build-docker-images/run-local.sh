#!/bin/bash
# Run both services as containers that can reach each other by name.
set -euo pipefail

docker network inspect part-net >/dev/null 2>&1 || docker network create part-net

docker run -d --name part-inventory-service --network part-net \
  -p 8081:8080 part-inventory-service:local

docker run -d --name part-order-service --network part-net \
  -p 8080:8080 \
  -e INVENTORY_SERVICE_URL=http://part-inventory-service:8080 \
  part-order-service:local

echo "waiting for both services to report UP..."
for port in 8081 8080; do
  until curl -fs "http://localhost:$port/actuator/health" | grep -q '"status":"UP"'; do
    sleep 3
  done
  echo "OK   :$port is UP"
done
