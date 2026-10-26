# Step 2 — The Namespace and the Secret

> [Back to index](README.md) · Previous: [Step 1](01-concepts.md) · Next: [Step 3 — MySQL StatefulSet](03-mysql-statefulset.md)

## Goal

Create the `part-order-app-mysql` Namespace and a Secret holding three passwords: MySQL's root user and one for each service.

## Why this matters

Three different parties need credentials, and they should not share them. MySQL
itself needs a root password to initialise. Each service needs only the password
for *its own* database user. Putting all three in one Secret is convenient, but
the convenience is safe only because of how the consumers use it: MySQL loads the
whole Secret as environment variables, while each application Deployment (Steps 6
and 7) pulls a **single key**. A compromised app Pod then holds one scoped
password, never the root one.

Be clear about what a Secret is not. Its values are base64-encoded, not
encrypted, and anyone who can `get secret` in the Namespace can read them. The
manifest in this repo goes further and commits plaintext passwords, with a header
saying so. That is acceptable for a laptop cluster and wrong for anything else,
where you would use Sealed Secrets, External Secrets or Vault. Teach it as a
shape to learn, not a pattern to ship.

## 1. The Namespace

Create `namespace.yaml` in `~/k8s-lab/part-order-app-mysql/`:

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: part-order-app-mysql
  labels:
    env: dev-mysql
```

```bash
kubectl apply -f namespace.yaml
```

## 2. The Secret

Create `mysql/secret.yaml`:

```yaml
# Plaintext for local/dev-with-mysql use only. For a real environment, generate
# this with a secret manager (Sealed Secrets, External Secrets, Vault, etc.)
# instead of committing credentials to git.
apiVersion: v1
kind: Secret
metadata:
  name: mysql-secret
  namespace: part-order-app-mysql
type: Opaque
stringData:
  MYSQL_ROOT_PASSWORD: dev-root-pw
  INVENTORY_DB_PASSWORD: dev-inventory-pw
  ORDER_DB_PASSWORD: dev-order-pw
```

- `stringData` lets you write plain text; Kubernetes encodes it into `data` when storing. Use `data` only if you supply base64 yourself.
- `MYSQL_ROOT_PASSWORD` is a name the official `mysql` image understands: if it is present as an environment variable, the image sets root's password to it on first start.
- The other two names are ours. The init script in Step 4 reads them to create each user.

```bash
kubectl apply -f mysql/secret.yaml
```

## Try it

```bash
kubectl get ns part-order-app-mysql --show-labels
kubectl get secret mysql-secret -n part-order-app-mysql
kubectl describe secret mysql-secret -n part-order-app-mysql
```

Expected: the Namespace is `Active`, the Secret shows `DATA 3`, and `describe` lists the three keys with byte sizes but **not** their values.

Then show how little protection that is:

```bash
kubectl get secret mysql-secret -n part-order-app-mysql -o jsonpath='{.data.ORDER_DB_PASSWORD}' | base64 -d; echo
```

Expected: `dev-order-pw`. Anyone with read access gets it.

## Checkpoint

<details>
<summary>Full <code>namespace.yaml</code></summary>

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: part-order-app-mysql
  labels:
    env: dev-mysql
```

</details>

This matches [namespace.yaml](../../../part-order-app-java/k8s-with-mysql/namespace.yaml) exactly.

<details>
<summary>Full <code>mysql/secret.yaml</code></summary>

```yaml
# Plaintext for local/dev-with-mysql use only. For a real environment, generate
# this with a secret manager (Sealed Secrets, External Secrets, Vault, etc.)
# instead of committing credentials to git.
apiVersion: v1
kind: Secret
metadata:
  name: mysql-secret
  namespace: part-order-app-mysql
type: Opaque
stringData:
  MYSQL_ROOT_PASSWORD: dev-root-pw
  INVENTORY_DB_PASSWORD: dev-inventory-pw
  ORDER_DB_PASSWORD: dev-order-pw
```

</details>

This matches [mysql/secret.yaml](../../../part-order-app-java/k8s-with-mysql/mysql/secret.yaml) exactly.

## Common mistakes

| Symptom | Cause | Fix |
| --- | --- | --- |
| `namespaces "part-order-app-mysql" not found` on the Secret | Namespace not applied first | apply `namespace.yaml` first |
| MySQL later ignores your password | the data directory was already initialised with a different one | the password is only read on first initialisation (Step 4 explains) |
| `illegal base64 data` | value placed under `data:` without encoding | use `stringData:` for plain text |
| Passwords committed to a shared repo | plaintext Secret manifests | for anything real, use a secret manager; keep this file local |

Next: **[Step 3 — MySQL StatefulSet](03-mysql-statefulset.md)**.
