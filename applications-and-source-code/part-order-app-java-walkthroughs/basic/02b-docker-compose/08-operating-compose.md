# Step 8 — Operating Compose: Logs, One-Off Commands, Rebuilds, Cleanup

> [Back to index](README.md) · Previous: [Step 7](07-prod-stack.md) · Next: [Step 9 — Recap and Exercises](09-recap-and-exercises.md)

## Goal

Learn the commands for running a Compose project day to day: look at it, run things inside it, change one service without touching the others, and remove it completely.

## Why this matters

Once the file is right, nearly all the work is one of five questions: what is
running, what is it saying, can I run a command in there, how do I ship a change to
one service, and how do I get back to nothing. Compose scopes every command to the
**project** (the name from `compose.yaml`) so you never touch other containers on the machine, and
it recreates only what changed. The two habits worth building are naming the service when you
only mean one service, and choosing the right teardown, because `down`, `down -v` and
`down --rmi local` differ in what they destroy.

These commands use the dev stack from Step 4. Make sure you are in `~/docker-lab` with `COMPOSE_FILE` unset (`unset COMPOSE_FILE`), then start it:

```bash
cd ~/docker-lab
unset COMPOSE_FILE
docker compose up -d --build --wait
```

## 1. Look

```bash
docker compose ps
docker compose ls
docker compose top part-order-service
docker compose stats --no-stream
```

- `ps` shows this project's containers and health; add `-a` to include stopped ones.
- `ls` lists every Compose project on the machine, with its status and the files it was started from. Handy when you have forgotten what is still running.
- `top` shows the processes inside a service's container (`java -jar app.jar`).
- `stats --no-stream` prints CPU and memory once. Each Spring Boot container sits around 350-400 MiB here.

## 2. Read the logs

```bash
docker compose logs --tail 20 part-order-service
docker compose logs -f --since 1m
```

Without a service name, `logs` interleaves every service with a coloured prefix, which is the quickest way to see a request cross from the order service to inventory. `-f` follows (Ctrl+C to stop), `--since` limits the window.

## 3. Run something inside

```bash
docker compose exec part-order-service curl -s http://part-inventory-service:8080/actuator/health
docker compose run --rm --no-deps --entrypoint curl part-order-service -s http://part-inventory-service:8080/actuator/health
```

- `exec` runs in an **already running** container. Use it for inspection.
- `run` starts a **new, one-off** container from the service definition and removes it afterwards (`--rm`). `--no-deps` stops it starting the service's dependencies, and `--entrypoint curl` replaces the Java start command. Use it for jobs and for debugging with the exact same image and network.

Both print `{"groups":["liveness","readiness"],"status":"UP"}`.

## 4. Change one service

Change a source file, then rebuild only the service that owns it:

```bash
echo "# demo" >> ~/docker-lab/part-inventory-service/src/main/resources/application.yml
docker compose up -d --build part-inventory-service
docker compose ps
```

Expected: the inventory image is rebuilt and its container is **recreated** (the log lines say `Recreate`), while the order service keeps running, untouched and still `healthy`. Inventory shows `health: starting` for a few seconds. The dev profile means inventory's H2 data reset, since it is a new container.

Compose decides what to recreate by comparing the new configuration with the running containers. Editing an `environment` value, an image or a published port recreates that service. Two things do **not** trigger a recreate: a change to a bind-mounted file's contents (the container sees the new contents immediately, but nothing re-runs, for example MySQL's init script) and a change to a build context unless `--build` is given.

Remove the demo line from `application.yml` when you are done (it is only a YAML comment, but it is not part of the real file).

## 5. Restart, stop, start

```bash
docker compose restart part-order-service
docker compose stop part-order-service
docker compose ps -a
docker compose start part-order-service
```

`restart` bounces a container without recreating it. `stop` leaves it in the list as `Exited`, so `ps -a` still shows it, and `start` brings it back. Neither re-reads the Compose file; only `up` does.

## 6. Check what Compose believes

```bash
docker compose config --services
docker compose -f compose.yaml -f compose.mysql.yaml config --services
```

Expected: the first prints the two application services; the second also lists `mysql`. `config` with no extra arguments prints the full merged file, the one command that settles any "which setting won" argument.

## 7. Tear down, three ways

| Command | Removes | Keeps |
| --- | --- | --- |
| `docker compose down` | containers, network | images, named volumes |
| `docker compose down -v` | containers, network, **named volumes** | images |
| `docker compose down --rmi local -v` | all of the above plus the images Compose built (here `part-inventory-service:compose` and `part-order-service:compose`; observed with Compose v5.5.1) | pulled images such as `mysql:8.0` |

```bash
docker compose down --rmi local -v --remove-orphans
docker compose ls
docker images | grep ":compose"
```

Expected: the project is gone from `ls`, and no `:compose` images remain. `--remove-orphans` also removes containers left over from services you deleted from the file.

## Try it

Sections 1-7 are the exercise. A good end-to-end check: from nothing, can you run the full MySQL stack with one command and one `COMPOSE_FILE`, place an order, and tear it all down so `docker volume ls` and `docker ps -a` show no trace of it?

## Checkpoint

This step creates no file. The expected end state is an empty project:

<details>
<summary>Expected after the final teardown</summary>

```text
$ docker compose ls
NAME                STATUS              CONFIG FILES

$ docker ps -a --format '{{.Names}}' | grep part-order-app-
(no output)

$ docker volume ls | grep part-order
(no output)
```

</details>

## Common mistakes

| Symptom | Cause | Fix |
| --- | --- | --- |
| Command acts on the wrong stack | `COMPOSE_FILE` still set from Step 6 | `echo $COMPOSE_FILE`; `unset COMPOSE_FILE` |
| Code change not visible after `up -d` | `up` without `--build` reuses the existing image | `docker compose up -d --build <service>` |
| `down` did not delete the database | named volumes survive `down` | add `-v` |
| `No such service` | service name typo, or the override file is not loaded | `docker compose config --services` |
| `exec` fails: container is not running | the service stopped or is restarting | `docker compose ps -a`, then `logs` |
| Leftover containers with the project prefix | services removed from the file but not from Docker | `down --remove-orphans` |

Next: **[Step 9 — Recap and Exercises](09-recap-and-exercises.md)**.
