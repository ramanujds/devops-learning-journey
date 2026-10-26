# Step 8 — Recap and Exercises

> [Back to index](README.md) · Previous: [Step 7](07-images-in-kubernetes.md)

## Quick reference

| Concept | Where it lives |
| --- | --- |
| Multi-stage build (Maven to Corretto) | [part-inventory-service/Dockerfile](../../../part-order-app-java/part-inventory-service/Dockerfile) |
| Order service build | [part-order-service/Dockerfile](../../../part-order-app-java/part-order-service/Dockerfile) |
| Cache-friendly copy order (`pom.xml` then `src`) | Step 3 |
| Service-to-service address | `INVENTORY_SERVICE_URL` in [InventoryServiceClient.java](../../../part-order-app-java/part-order-service/src/main/java/com/forvia/partorderservice/client/InventoryServiceClient.java) |
| Running both locally | [run-local.sh](run-local.sh) |
| Multi-arch publish | [build-commands-mac.sh](../../../part-order-app-java/build-commands-mac.sh) |
| Which image Kubernetes runs | `image:` and `imagePullPolicy:` in each Deployment |

## Gotchas

| Gotcha | Where it comes from |
| --- | --- |
| Order service reports `UP` but orders return 500 | `INVENTORY_SERVICE_URL` defaults to `localhost:8080`, which is the container itself (Step 5) |
| State vanishes on restart | default profile is `dev` with in-memory H2 re-seeded from `data.sql` (Step 5) |
| Containers run as root | `#USER nonroot` is commented out in the inventory Dockerfile and absent in the order one; the base image has no `nonroot` user |
| Dockerfiles build with JDK 25 while the `pom.xml` sets `java.version` to 21 | repo state; it compiles for 21 on a newer JDK, but the versions do not match |
| Every source change re-downloads Maven dependencies | no dependency-resolution layer between `COPY pom.xml` and `COPY src` |
| Rebuilding `:latest` does not update a running cluster | the manifest is unchanged; use `kubectl rollout restart` |
| The shipped manifests ignore your local build | `imagePullPolicy: Always` (Step 7) |

## Discussion questions

1. Why does `COPY pom.xml .` come before `COPY src ./src`, and what would change if you wrote `COPY . .` first?
2. A multi-stage build throws the build stage away. Where can the source code still be found after the build?
3. The order service passes its health check with no inventory service running. Is that the right behaviour for a readiness probe? What would you change?
4. Why does naming the container `part-inventory-service` matter, and what is the Kubernetes equivalent?
5. Why is `:latest` convenient for a tutorial and risky for production?

## Exercises

1. Run `docker history` on both `:single` and `:local` and list which layers exist only in the single-stage image.
2. Add a `.dockerignore` next to each Dockerfile that excludes `target/` and `logs/`. Does the build change, and how big is the build context before and after?
3. Add a dependency-download layer between the two `COPY` lines (`RUN mvn dependency:go-offline`). Edit a source file and rebuild; compare timings with Step 3.
4. Make the container run as a non-root user: create the user in the runtime stage, enable `USER`, and confirm with `docker exec part-order-service id -un`. Fix anything that breaks (hint: the service writes `logs/application.log`).
5. Build with `--platform linux/amd64` on an Apple Silicon Mac and time it against the native build; explain the difference.
6. Rebuild `part-inventory-service/Dockerfile` from memory in an empty folder, then diff it against the reference:

   ```bash
   diff Dockerfile /path/to/part-order-app-java/part-inventory-service/Dockerfile
   ```

## What's next

With images built, deploy them. Next: walkthrough 03, the `k8s/` H2 stack (Namespace, ConfigMaps, Deployments, Services and an HPA). It is not written yet; the series index is at [../../README.md](../../README.md).
