# Step 2 — A First Dockerfile: Single Stage

> [Back to index](README.md) · Previous: [Step 1](01-concepts.md) · Next: [Step 3 — Multi-Stage Dockerfile](03-multi-stage-dockerfile.md)

## Goal

Get `part-inventory-service` building and running in a container with the simplest possible Dockerfile, and see what is wrong with it.

## Why this matters

You cannot appreciate multi-stage builds until you have shipped the other
kind. The simplest way to containerise a Maven project is to start from a Maven
image, build there, and run from there. It works the first time, which is why
people stop at it. The cost only shows up later: a larger image to pull on every
node, Maven and the whole source tree inside a production image, and a larger attack surface for no benefit.

## 1. Start from a Maven + JDK image and copy the project in

In `~/docker-lab/part-inventory-service/`, create `Dockerfile`:

```dockerfile
FROM maven:3.9.11-eclipse-temurin-25
WORKDIR /app
COPY pom.xml .
COPY src ./src
```

- `FROM` picks the base. This one contains Maven and a JDK.
- `WORKDIR /app` creates the folder and makes it the default for later instructions.
- The two `COPY` lines bring in only what the build needs: the build file first, then the source.

## 2. Build the jar

Append:

```dockerfile
RUN mvn clean package -DskipTests
RUN cp target/*.jar app.jar
```

`-DskipTests` keeps image builds fast; run tests separately with `./mvnw test`
in the service folder. The `cp` gives the jar a fixed name, because the next line uses exec form and exec form does not expand `*`.

## 3. Say how to start it

Append:

```dockerfile
ENTRYPOINT ["java","-jar", "app.jar"]
```

Exec form (a JSON array) runs `java` as the container's main process, so it
receives `SIGTERM` directly and shuts down cleanly. Shell form would wrap it in `sh -c`.

## Try it

```bash
cd ~/docker-lab/part-inventory-service
docker build -t part-inventory-service:single .
docker images part-inventory-service
```

Expected: one image of roughly **1.2-1.3 GB**.

Run it and check it is alive:

```bash
docker run -d --name inv-single -p 8081:8080 part-inventory-service:single
sleep 20
curl http://localhost:8081/actuator/health
curl http://localhost:8081/api/parts
```

Expected: `{"groups":["liveness","readiness"],"status":"UP"}` and a JSON list of parts.

Now look inside, which is the part to remember:

```bash
docker exec inv-single ls /app
docker exec inv-single mvn -v
```

You will see `pom.xml`, `src`, `target` and a working Maven. All of that is shipped to wherever this image runs.

Clean up:

```bash
docker rm -f inv-single
```

## Checkpoint

<details>
<summary>Full <code>Dockerfile</code> (single stage)</summary>

```dockerfile
FROM maven:3.9.11-eclipse-temurin-25
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests
RUN cp target/*.jar app.jar
ENTRYPOINT ["java","-jar", "app.jar"]
```

</details>

This is a teaching version. It is replaced in the next step and does not match the repo.

## Common mistakes

| Symptom | Cause | Fix |
| --- | --- | --- |
| `COPY failed: file not found in build context` | ran `docker build` from the wrong folder, or the Step 1 copy was skipped | `cd ~/docker-lab/part-inventory-service` and check `ls` shows `pom.xml src` |
| `cp: cannot stat 'target/*.jar'` | the Maven build failed earlier in the log | scroll up in the build output for the `ERROR` from `mvn` |
| Container exits immediately | the jar name in `ENTRYPOINT` does not exist | `docker logs inv-single`; keep the fixed name `app.jar` |
| `port is already allocated` | something else uses 8081 | `lsof -i :8081`, or change the left side of `-p` |

Next: **[Step 3 — Multi-Stage Dockerfile](03-multi-stage-dockerfile.md)**.
