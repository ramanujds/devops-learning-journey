# Step 9 — Operating the Stack: Logs, Restarts, Rollback, Teardown

> [Back to index](README.md) · Previous: [Step 8](08-hpa-and-shared-state.md) · Next: [Step 10 — Recap and Exercises](10-recap-and-exercises.md)

## Goal

Learn the day-two commands: read logs and events, restart after a config or image change, break a rollout on purpose and roll it back, and tear everything down.

## Why this matters

Writing manifests is the easy half. Most of the time spent on a cluster is
answering "why is it not working" and "how do I change it safely". The most
important idea is that a Deployment **protects you from your own bad change**:
it starts new Pods, waits for them to pass readiness, and only then removes the
old ones. If the new ones never become Ready, the rollout stalls and the old
Pods keep serving. That is the real job of the readiness probe from Step 5, and
you will watch it happen. Together with `rollout undo`, it turns a broken deploy into a non-event.

## 1. Look at what is happening

```bash
kubectl get pods,svc,deploy,hpa -n part-order-app
kubectl logs -n part-order-app deploy/part-order-service --tail=20
kubectl logs -n part-order-app deploy/part-order-service -f         # Ctrl+C to stop
kubectl describe pod -n part-order-app -l app=part-order-service     # read the Events at the bottom
kubectl get events -n part-order-app --sort-by=.lastTimestamp | tail -10
```

- `logs` shows the application; `describe` and `events` show what **Kubernetes** did (scheduling, pulling, probe failures, restarts).
- After a crash loop, `kubectl logs --previous` shows the container that died.
- The logs are noisy with SQL statements because the `dev` profile turns on `org.hibernate.SQL: DEBUG`. That is expected in `dev`, not an error.

Run a command inside the Pod, here the order service calling inventory by its Service name (the image has `curl` but no `wget`):

```bash
kubectl exec -n part-order-app deploy/part-order-service -- curl -s http://part-inventory-service:8080/actuator/health
```

Expected: `{"groups":["liveness","readiness"],"status":"UP"}`. This is the exact path the Feign client takes.

## 2. Restart after a config or image change

Both images are `:latest`, so a new push changes nothing in the manifest and no rollout happens. Likewise, ConfigMap edits are only read at start. Either way, force it:

```bash
kubectl rollout restart deploy/part-inventory-service -n part-order-app
kubectl rollout restart deploy/part-order-service -n part-order-app
kubectl rollout status deploy/part-order-service -n part-order-app
```

The repo's iteration loop is: rebuild and push the images (`build-commands-mac.sh`, see [walkthrough 02, Step 6](../02-build-docker-images/06-publish-multi-arch.md)), then restart the Deployments as above. `imagePullPolicy: Always` makes the restarted Pods pull the new image.

## 3. Break a rollout on purpose

Point the readiness probe at a path that does not exist:

```bash
kubectl patch deploy part-order-service -n part-order-app --type=json \
  -p='[{"op":"replace","path":"/spec/template/spec/containers/0/readinessProbe/httpGet/path","value":"/actuator/health/nope"}]'
sleep 70
kubectl get pods -n part-order-app
kubectl rollout status deploy/part-order-service -n part-order-app --timeout=5s
```

Expected: two order Pods, the old one `1/1` and the new one stuck at `0/1`, and `error: timed out waiting for the condition`. Meanwhile the site still works:

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:30080/
```

Expected: `200`, served by the old Pod. Find out why the new one is not Ready:

```bash
kubectl describe pod -n part-order-app -l app=part-order-service | grep -i "readiness probe failed"
```

Expected: `Readiness probe failed: HTTP probe failed with statuscode: 404`. (You may also see an earlier `connection refused` line from the first seconds of start-up; that is normal.)

## 4. Roll back

```bash
kubectl rollout history deploy/part-order-service -n part-order-app
kubectl rollout undo deploy/part-order-service -n part-order-app
kubectl rollout status deploy/part-order-service -n part-order-app
kubectl get pods -n part-order-app
```

Expected: history shows revisions 1 and 2, the undo succeeds, and a single healthy order Pod remains. `kubectl` may print a warning that the resource was "previously managed with `kubectl apply`"; the safest way to get fully back in sync is to re-apply the file you trust:

```bash
kubectl apply -f part-order-service/deployment.yaml
```

## 5. Teardown

```bash
kubectl delete namespace part-order-app
kubectl get all -n part-order-app
```

Deleting the Namespace removes every object in it: Deployments, Pods, Services, ConfigMaps and the HPA. To bring the stack back from your files:

```bash
kubectl apply -f namespace.yaml
kubectl apply -f part-inventory-service/
kubectl apply -f part-order-service/
```

Applying the inventory folder includes `hpa.yaml`. Delete it again if you do not want the Step 8 behaviour (or fix its target in the exercises).

## Try it

The sections above are the exercise; run them in order. After the rollback, confirm the stack is whole:

```bash
kubectl get pods,svc -n part-order-app
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:30080/
```

## Checkpoint

This step creates no file. The expected end state of the cluster, with the HPA removed and everything healthy:

<details>
<summary>Expected <code>kubectl get pods,svc -n part-order-app</code></summary>

```text
NAME                                          READY   STATUS    RESTARTS   AGE
pod/part-inventory-service-<hash>-<suffix>    1/1     Running   0          ...
pod/part-order-service-<hash>-<suffix>        1/1     Running   0          ...

NAME                             TYPE       CLUSTER-IP   EXTERNAL-IP   PORT(S)          AGE
service/part-inventory-service   NodePort   10.x.x.x     <none>        8080:3xxxx/TCP   ...
service/part-order-service       NodePort   10.x.x.x     <none>        8080:30080/TCP   ...
```

</details>

## Common mistakes

| Symptom | Cause | Fix |
| --- | --- | --- |
| Pushed a new `:latest`, cluster runs old code | no manifest change, so no rollout | `kubectl rollout restart deploy/<name> -n part-order-app` |
| Rollout hangs and nothing seems wrong | new Pods fail readiness; old ones keep serving | `kubectl describe pod` for the probe event, then `rollout undo` |
| `rollout undo` goes to an unexpected version | each Pod-template change is a revision, including accidental ones | `kubectl rollout history`, then `rollout undo --to-revision=N` |
| `kubectl logs` says the container is not ready or has no logs yet | Pod is still starting | wait; use `kubectl get pods -w` |
| Namespace delete hangs | Pods are still terminating | wait, then `kubectl get all -n part-order-app` |

Next: **[Step 10 — Recap and Exercises](10-recap-and-exercises.md)**.
