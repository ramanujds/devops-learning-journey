# Step 4 — The Second Service

> [Back to index](README.md) · Previous: [Step 3](03-multi-stage-dockerfile.md) · Next: [Step 5 — Run Together](05-run-together.md)

## Goal

Build `part-order-service` from the same pattern, and understand the two small differences between its Dockerfile and the inventory one.

## Why this matters

The two services are separate Maven projects with separate `pom.xml` files, so
each gets its own Dockerfile and its own build context. There is no shared
parent Dockerfile and no way to build one from the other's folder. The
Dockerfiles are almost identical, and that is the point: once the pattern is
right, a new service costs one copy-paste. The differences are the places where the repo drifted, and noticing drift between two files that should match is a real maintenance skill.

| | inventory | order |
| --- | --- | --- |
| Build stage | identical | identical |
| Runtime stage base and copy | identical | identical |
| `#USER nonroot` (commented) | present | absent |
| `EXPOSE 8080` | present | absent |
| Blank lines before `ENTRYPOINT` | one | two |

Neither difference changes behaviour. `EXPOSE` is documentation, and the
commented `USER` line does nothing.

## 1. Copy the Dockerfile across

```bash
cp ~/docker-lab/part-inventory-service/Dockerfile ~/docker-lab/part-order-service/Dockerfile
```

## 2. Remove the two leftover lines

Edit `~/docker-lab/part-order-service/Dockerfile` so the end of the runtime stage reads:

```dockerfile
COPY --from=build /app/target/*.jar app.jar


ENTRYPOINT ["java","-jar", "app.jar"]
```

That means deleting `#USER nonroot` and `EXPOSE 8080`. Keeping them would also work; they are removed here only so your file matches the repo.

## 3. Build it

```bash
docker build -t part-order-service:local ~/docker-lab/part-order-service
```

## Try it

```bash
docker images | grep part-
docker run -d --name order-alone -p 8090:8080 part-order-service:local
sleep 20
curl http://localhost:8090/actuator/health
docker rm -f order-alone
```

Expected: both images listed at about 1.0 GB, and `{"groups":["liveness","readiness"],"status":"UP"}`.

The service is healthy by itself, but it cannot do its real job yet: its
inventory calls go to the default `http://localhost:8080`, which inside this
container means itself. Step 5 fixes that.

## Checkpoint

<details>
<summary>Full <code>Dockerfile</code> (part-order-service)</summary>

```dockerfile
# -------- Build stage --------
FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /app

COPY pom.xml .

COPY src ./src
RUN mvn clean package -DskipTests


# -------- Runtime stage --------
FROM amazoncorretto:25-jdk
WORKDIR /app

COPY --from=build /app/target/*.jar app.jar


ENTRYPOINT ["java","-jar", "app.jar"]
```

</details>

This matches [part-order-service/Dockerfile](../../../part-order-app-java/part-order-service/Dockerfile) exactly (the repo file has no trailing newline).

## Common mistakes

| Symptom | Cause | Fix |
| --- | --- | --- |
| Both images show the same IMAGE ID | both were built from the same folder | image IDs are content hashes; rebuild `part-order-service:local` with the `part-order-service` folder as context |
| `docker build` says `unable to prepare context: path not found` | `~` was quoted, so the shell did not expand it | use `$HOME/docker-lab/...` inside quotes |
| `COPY failed: file not found` for `pom.xml` | the Step 1 copy into `part-order-service` was skipped | `ls ~/docker-lab/part-order-service` should show `pom.xml src` |

Next: **[Step 5 — Run Together](05-run-together.md)**.
