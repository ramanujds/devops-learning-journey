# RBAC — who can do what, to which resources

Every object so far has been created as whoever your kubeconfig says you
are, with no limits. RBAC (Role-Based Access Control) is the API server's
authorization layer that makes "no limits" the exception rather than the
rule — and, once you leave a local cluster for
[EKS/GKE](07-kubernetes-on-cloud.md), the thing that decides whether your
cloud identity can touch the cluster at all.

---

## Where RBAC sits in a request's lifecycle

```mermaid
flowchart LR
    Req["kubectl / Pod\nAPI request"] --> Authn["Authentication\nwho are you?"]
    Authn --> Authz["Authorization (RBAC)\nare you allowed to do THIS?"]
    Authz --> Adm["Admission control\n(quotas, policies)"]
    Adm --> Etcd[(etcd)]
```

Authentication answers "who" (a cert, a token, a ServiceAccount);
authorization — RBAC — answers "is this identity allowed to `verb` this
`resource`." Every API request passes through both, in that order, before
it ever touches storage — and, if RBAC allows it, still has to clear
admission control (quotas, policies, mutating/validating webhooks) before
the object is actually persisted; see
[10-admission-controllers.md](10-admission-controllers.md).

---

## Authentication in detail: certificates and kubeconfig

RBAC's "who" comes from authentication, and the API server has **no user
database** — there's no `kubectl create user`, no `User` API object at
all. Every request arrives already carrying proof of identity, and the
API server just checks that proof and reads a name and group(s) off it.
The most common proof, and the one every `kubectl` command you've run so
far has used without you setting it up explicitly, is a **client TLS
certificate**.

```mermaid
sequenceDiagram
    participant You as kubectl
    participant KC as ~/.kube/config
    participant API as API Server (kube-apiserver)
    You->>KC: read current-context
    KC-->>You: cluster (server URL + CA cert)\nuser (client cert + key, or token)
    You->>API: HTTPS request, TLS handshake\npresents client certificate
    API->>API: verify cert signed by trusted CA\n(--client-ca-file)
    API->>API: extract identity: CN → username, O → group(s)
    API->>API: RBAC check (see sections above)
    API-->>You: 200 OK / 403 Forbidden
```

This happens on **every single `kubectl` command** — there's no login
step, no session, nothing cached server-side. Each request re-proves who
you are from scratch, which is why a leaked kubeconfig is as sensitive as
a leaked password: possessing the file *is* the authentication.

### What a client certificate actually encodes

```bash
# generate a private key and a certificate signing request for a new user "alice"
# in groups "dev-editors" and "qa"
openssl genrsa -out alice.key 2048
openssl req -new -key alice.key -out alice.csr \
  -subj "/CN=alice/O=dev-editors/O=qa"
```

| x509 field | Maps to |
| --- | --- |
| `CN` (Common Name) | Kubernetes `User` name |
| `O` (Organization) — repeatable | Kubernetes `Group`(s) — bind a `RoleBinding`/`ClusterRoleBinding` to the group once, every member of it inherits the grant |
| Signed by the cluster's CA | trust anchor — the API server only accepts certs chaining to `--client-ca-file` |

This is exactly the mechanism the `RoleBinding`/`ClusterRoleBinding`
`subjects` you saw earlier bind against — `kind: User, name: alice` or
`kind: Group, name: dev-editors` refers to whatever `CN`/`O` shows up in
the presented certificate, nothing more; Kubernetes never stores or
looks the name up anywhere.

### Anatomy of a kubeconfig

```yaml
apiVersion: v1
kind: Config
clusters:
  - name: my-cluster
    cluster:
      server: https://10.0.0.1:6443
      certificate-authority-data: <base64 CA cert>   # verifies the SERVER's identity
users:
  - name: alice
    user:
      client-certificate-data: <base64 alice.crt>    # proves the CLIENT's identity
      client-key-data: <base64 alice.key>             # never leaves alice's machine
contexts:
  - name: alice@my-cluster
    context:
      cluster: my-cluster
      user: alice
      namespace: dev
current-context: alice@my-cluster
```

Three independent blocks, and mutual TLS uses both directions of trust at
once: `clusters[].certificate-authority-data` is how `alice`'s `kubectl`
verifies it's really talking to the real API server; `users[].client-*`
is how the API server verifies it's really `alice` on the other end.
`kubectl config set-cluster` / `set-credentials` / `set-context` (used
earlier in this note for `dev-user`) are just YAML-generators for exactly
this file — nothing about a kubeconfig is magic beyond these three lists.

### Real-world flow: provisioning a teammate as cluster admin

Hand-signing certs directly with the cluster's root CA key works on a
`kubeadm` cluster where you hold `/etc/kubernetes/pki/ca.key`, but it's
worth doing it the way that also works when you *don't* hold the CA key
(GKE/EKS don't give you one at all — see the sections below) — via
Kubernetes' own `CertificateSigningRequest` API, so the private key never
has to leave the teammate's laptop:

```mermaid
sequenceDiagram
    participant Alice
    participant You as You (cluster-admin)
    participant API as API Server
    Alice->>Alice: openssl genrsa -out alice.key<br/>openssl req -new ... -out alice.csr
    Alice->>You: send alice.csr (public, safe over Slack/email)
    You->>API: kubectl apply -f alice-csr.yaml (CertificateSigningRequest)
    You->>API: kubectl certificate approve alice-csr
    API-->>You: status.certificate (signed cert, base64)
    You->>You: build alice-kubeconfig using alice.crt + cluster CA
    You->>API: kubectl create rolebinding ... --user=alice
    You->>Alice: share alice-kubeconfig over a secure channel
```

```yaml
# alice-csr.yaml
apiVersion: certificates.k8s.io/v1
kind: CertificateSigningRequest
metadata:
  name: alice-csr
spec:
  request: <base64 of alice.csr>          # cat alice.csr | base64 | tr -d '\n'
  signerName: kubernetes.io/kube-apiserver-client
  expirationSeconds: 86400                 # 24h — force periodic renewal, don't default to 1yr
  usages:
    - client auth
```

```bash
kubectl apply -f alice-csr.yaml
kubectl get csr                                    # shows Pending
kubectl certificate approve alice-csr               # you, the admin, approving it
kubectl get csr alice-csr -o jsonpath='{.status.certificate}' | base64 -d > alice.crt

# wire alice.crt (not her private key — she already has that) into a kubeconfig
kubectl config set-cluster my-cluster --server=https://10.0.0.1:6443 \
  --certificate-authority=ca.crt --embed-certs \
  --kubeconfig=alice-kubeconfig
kubectl config set-credentials alice \
  --client-certificate=alice.crt --client-key=alice.key --embed-certs \
  --kubeconfig=alice-kubeconfig
kubectl config set-context alice@my-cluster \
  --cluster=my-cluster --user=alice --namespace=dev \
  --kubeconfig=alice-kubeconfig
kubectl config use-context alice@my-cluster --kubeconfig=alice-kubeconfig

# grant access using the exact RBAC objects from earlier in this note —
# subject kind is User now, not ServiceAccount
kubectl create rolebinding alice-edit \
  --clusterrole=edit --user=alice --namespace=dev
```

`alice-kubeconfig` is the one file you hand back to her — over a secure
channel (a password manager's secure-note sharing, not plaintext
Slack/email, same handling as a password) since anyone holding it can
authenticate as `alice` for as long as the cert is valid.

### Caveats worth knowing before doing this for real

- **No revocation list.** Kubernetes doesn't check a CRL by default —
  once a cert is issued it's valid until it expires, full stop. Setting
  `expirationSeconds` short (hours-to-days, not the ~1yr default some
  tools use) is the practical mitigation; if you must cut someone off
  immediately, remove every `RoleBinding`/`ClusterRoleBinding` naming
  them (access becomes empty even though the cert still "authenticates")
  — that's why this note keeps saying RBAC access is a *union of
  bindings*, it's also the fastest revocation lever you have.
- **This whole cert-signing flow requires a CA you control**, which
  self-managed clusters (`kubeadm`, `kind`) give you and managed clusters
  generally don't — see [How this maps onto GKE](#how-this-maps-onto-gke)
  and [EKS](#how-this-maps-onto-eks) below for how team access is granted
  there instead (cloud IAM identity, no client cert to hand-issue at
  all).
- **A `ServiceAccount` token (already covered in
  [Acting as that ServiceAccount from kubectl](#acting-as-that-serviceaccount-from-kubectl)
  above) is the easier path** when what you need is "give a human
  short-lived cluster access for testing," since `kubectl create token`
  needs no CSR ceremony — reach for a real user cert/OIDC identity
  instead when the access is long-lived and needs to be individually
  attributable to that person in audit logs, not shared through a
  ServiceAccount every teammate could equally use.

---

## The four RBAC objects, and how they pair up

```mermaid
flowchart LR
    subgraph "the what"
        Role["Role\n(namespaced)"]
        CRole["ClusterRole\n(cluster-wide)"]
    end
    subgraph "the who gets it"
        RB["RoleBinding\n(namespaced)"]
        CRB["ClusterRoleBinding\n(cluster-wide)"]
    end
    Subj["Subject: User / Group / ServiceAccount"]

    Role --> RB
    CRole --> RB
    CRole --> CRB
    Subj --> RB
    Subj --> CRB
```

| | `Role` | `ClusterRole` |
| --- | --- | --- |
| Scope | one namespace | cluster-wide |
| Can grant access to | namespaced resources only (Pods, Services) | namespaced **and** cluster-scoped resources (Nodes, Namespaces, PVs — see [namespaces.md](../kubernetes-intro/10.namespaces.md)) |
| Bound with | `RoleBinding` | `ClusterRoleBinding` **or** `RoleBinding` (scopes a cluster-wide Role down to one namespace) |

| | `RoleBinding` | `ClusterRoleBinding` |
| --- | --- | --- |
| Scope | one namespace | cluster-wide |
| Can bind | `Role` or `ClusterRole` | `ClusterRole` only |

The "`ClusterRole` + `RoleBinding`" row is the pattern worth remembering:
define a role once (`view`, `edit`, a custom `cluster-viewer`), then grant
it namespace-by-namespace without duplicating the rule set.

Permissions are **additive only** — there is no `deny` rule. Access is the
union of every `Role`/`ClusterRole` bound to an identity; if nothing
grants a verb, it's implicitly denied.

---

## Anatomy of a rule: apiGroup + resource + verb

```yaml
rules:
  - apiGroups: [""]                # "" = the core API group
    resources: ["pods"]
    verbs: ["get", "list", "watch"]
  - apiGroups: ["apps"]
    resources: ["deployments"]
    verbs: ["get", "list", "create", "update", "delete"]
```

| apiGroup | Resources |
| --- | --- |
| `""` (core) | pods, services, configmaps, secrets, namespaces, nodes |
| `apps` | deployments, statefulsets, daemonsets, replicasets |
| `batch` | jobs, cronjobs |
| `rbac.authorization.k8s.io` | roles, rolebindings, clusterroles, clusterrolebindings |
| `networking.k8s.io` | ingresses, [networkpolicies](04-networking-policy.md) |

| Verb | HTTP method | Effect |
| --- | --- | --- |
| `get` / `list` / `watch` | GET | read one / read all / stream changes |
| `create` | POST | create a resource |
| `update` / `patch` | PUT / PATCH | replace / partially modify |
| `delete` / `deletecollection` | DELETE | delete one / delete all matching |
| `*` | — | wildcard, every verb — avoid outside break-glass roles |

`kubectl api-resources` lists every valid `resources` value; `kubectl
explain <resource>` shows which `apiGroup` it belongs to if the table
above doesn't cover it.

---

## Worked example: scoping a `dev-user` to one namespace

Five objects, applied together, that let a ServiceAccount manage Pods and
Deployments in `dev` and nothing else:

```yaml
# namespace-dev.yaml
apiVersion: v1
kind: Namespace
metadata:
  name: dev
  labels:
    env: dev
```

```yaml
# service-account.yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: dev-user
  namespace: dev
```

```yaml
# dev-role.yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: dev-role
  namespace: dev
rules:
  - apiGroups: [""]
    resources: ["pods", "services", "configmaps", "secrets"]
    verbs: ["get", "list", "watch", "create", "update", "delete"]
  - apiGroups: ["apps"]
    resources: ["deployments"]
    verbs: ["get", "list", "watch", "create", "update", "delete"]
```

```yaml
# rolebinding.yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: dev-role-binding
  namespace: dev
subjects:
  - kind: ServiceAccount
    name: dev-user
    namespace: dev
roleRef:
  kind: Role
  name: dev-role
  apiGroup: rbac.authorization.k8s.io
```

```bash
kubectl apply -f namespace-dev.yaml -f service-account.yaml \
  -f dev-role.yaml -f rolebinding.yaml
```

```mermaid
flowchart LR
    NS["Namespace: dev"] -.scopes.-> Role["Role: dev-role"]
    SA["ServiceAccount: dev-user"] --> RB["RoleBinding: dev-role-binding"]
    Role --> RB
    RB -.grants dev-role to dev-user,\nonly inside ns dev.-> SA
```

---

## Layering in a cluster-wide read-only grant

Bind a `ClusterRole` to the same `ServiceAccount` with a
`ClusterRoleBinding`, and its access extends beyond `dev` — additive to
everything already granted by the `RoleBinding` above:

```yaml
# cluster-role.yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: cluster-viewer
rules:
  - apiGroups: ["", "apps", "batch"]
    resources: ["pods", "deployments", "services", "nodes", "namespaces", "configmaps"]
    verbs: ["get", "list", "watch"]
```

```yaml
# cluster-role-binding.yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: cluster-viewer-binding
subjects:
  - kind: ServiceAccount
    name: dev-user
    namespace: dev
roleRef:
  kind: ClusterRole
  name: cluster-viewer
  apiGroup: rbac.authorization.k8s.io
```

`dev-user` now has full read/write on Pods/Deployments **inside `dev`**
(from the `Role`), plus read-only visibility of Pods/Deployments/Nodes
**cluster-wide** (from the `ClusterRole`) — two different grants, unioned.

---

## The built-in ClusterRoles

Every cluster ships four pre-made ones — reach for these before writing a
custom `Role`:

| ClusterRole | Access |
| --- | --- |
| `view` | read-only on most namespaced resources |
| `edit` | read/write most namespaced resources, cannot touch RBAC objects |
| `admin` | `edit`, plus can manage Roles/RoleBindings within the namespace |
| `cluster-admin` | full control, cluster-wide — never bind this to an application workload |

```bash
# grant "edit", but scoped down to one namespace via a RoleBinding
kubectl create rolebinding alice-edit \
  --clusterrole=edit --user=alice --namespace=dev
```

---

## Testing what an identity can actually do

```bash
kubectl auth can-i delete pods

kubectl auth can-i list deployments \
  --as=system:serviceaccount:dev:dev-user --namespace=dev

# the full picture for one identity, one command
kubectl auth can-i --list \
  --as=system:serviceaccount:dev:dev-user --namespace=dev
```

`kubectl auth can-i` asks the API server to run the same authorization
check it would run for a real request — no need to actually attempt the
action (and risk the `delete`) just to find out if it's allowed.

---

## Acting as that ServiceAccount from kubectl

```bash
kubectl create token dev-user -n dev
```

```bash
kubectl config set-credentials dev-user \
  --token=$(kubectl create token dev-user -n dev)

kubectl config set-context dev-user-context \
  --cluster=$(kubectl config view --minify -o jsonpath='{.clusters[0].name}') \
  --user=dev-user --namespace=dev

kubectl config use-context dev-user-context
```

```mermaid
sequenceDiagram
    participant You
    participant API as API Server
    You->>API: kubectl create token dev-user -n dev
    API-->>You: short-lived JWT (1h default)
    You->>You: config set-credentials + set-context dev-user-context
    You->>API: kubectl get pods (now AS dev-user)
    API->>API: RBAC check against dev-user's bindings
    API-->>You: allowed (Role) / denied (no binding covers this)
```

```bash
kubectl config use-context dev-user-context
kubectl get pods            # works — covered by dev-role
kubectl get nodes           # works — covered by cluster-viewer (read-only)
kubectl delete deployment anything -n other-namespace   # denied
```

`kubectl create token` mints a token scoped to that `ServiceAccount`'s
existing bindings — it doesn't grant anything new, it just lets you
authenticate *as* that identity to verify the RBAC objects actually
behave the way you wrote them.

---

## How this maps onto GKE

GKE layers Kubernetes RBAC **under** Google Cloud IAM — two separate
checks, both must pass:

```mermaid
flowchart LR
    User["Google identity\n(user@company.com or a GSA)"] --> IAM["Cloud IAM\nroles/container.*"]
    IAM -->|"coarse: can you reach\nthe cluster's API at all?"| API["GKE API server"]
    API --> RBAC["Kubernetes RBAC\n(Role/ClusterRoleBinding\nsubject: User = your Google email)"]
    RBAC -->|"fine: can THIS identity\nrun THIS verb on THIS resource?"| Result["allowed / denied"]
```

```bash
# IAM layer: can this principal reach the cluster at all
gcloud projects add-iam-policy-binding my-project \
  --member="user:alice@company.com" \
  --role="roles/container.developer"

# wires kubectl using your gcloud identity
gcloud container clusters get-credentials demo-cluster --zone us-central1-a

# RBAC layer: what can that identity do once inside — same objects as above,
# just pointed at a real Google identity instead of a ServiceAccount
kubectl create rolebinding alice-edit \
  --clusterrole=edit --user=alice@company.com --namespace=dev
```

`roles/container.admin`/`.developer`/`.viewer` at the IAM level control
whether an identity can call the GKE/API-server endpoint at all;
Kubernetes RBAC bindings (same `Role`/`RoleBinding` objects from earlier
in this note) then control what it can do once it's in — losing either
one blocks access, having both is required. Workload Identity is the
Pod-side equivalent of this same idea: it lets a `ServiceAccount` assume a
Google service account's IAM permissions to call **other** GCP APIs
(Cloud Storage, Pub/Sub), which is a separate concern from the in-cluster
RBAC covered here.

---

## How this maps onto EKS

EKS has no separate "IAM role for the cluster" layer inside Kubernetes
itself — instead, it maps an AWS IAM principal (a user or role ARN)
directly onto a Kubernetes `User`/`Group`, which ordinary RBAC objects
then bind against.

```mermaid
flowchart LR
    IAMUser["IAM User/Role\narn:aws:iam::123:role/dev-role"] --> Map["aws-auth ConfigMap\n(or EKS Access Entry)"]
    Map -->|"maps ARN to\nk8s username + groups"| K8sIdentity["Kubernetes identity\nUser/Group"]
    K8sIdentity --> RBAC["RoleBinding/ClusterRoleBinding\n(same objects as earlier)"]
```

The older, still-common mechanism — a ConfigMap the API server reads on
every authentication:

```yaml
# kube-system/aws-auth ConfigMap (excerpt)
apiVersion: v1
kind: ConfigMap
metadata:
  name: aws-auth
  namespace: kube-system
data:
  mapRoles: |
    - rolearn: arn:aws:iam::123456789012:role/dev-team-role
      username: dev-user
      groups:
        - dev-editors
```

```yaml
# then an ordinary RBAC object binds the mapped group
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: dev-editors-binding
  namespace: dev
subjects:
  - kind: Group
    name: dev-editors            # matches the aws-auth "groups" entry, not an IAM group
    apiGroup: rbac.authorization.k8s.io
roleRef:
  kind: ClusterRole
  name: edit
  apiGroup: rbac.authorization.k8s.io
```

The newer, recommended replacement — EKS **access entries**, managed via
API/CLI instead of hand-editing a ConfigMap:

```bash
aws eks create-access-entry \
  --cluster-name my-cluster \
  --principal-arn arn:aws:iam::123456789012:role/dev-team-role \
  --kubernetes-groups dev-editors

aws eks associate-access-policy \
  --cluster-name my-cluster \
  --principal-arn arn:aws:iam::123456789012:role/dev-team-role \
  --access-scope type=namespace,namespaces=dev \
  --policy-arn arn:aws:eks::aws:cluster-access-policy/AmazonEKSEditPolicy
```

Access entries can either map straight to an AWS-managed access policy
(`AmazonEKSEditPolicy`/`AmazonEKSViewPolicy`, skipping RBAC objects
entirely for common cases) or, as above, map to a plain Kubernetes group
that your own `RoleBinding` still governs — same underlying RBAC model as
everywhere else in this note, just a different front door for wiring
identities in.

---

## GKE vs EKS, side by side

| | GKE | EKS |
| --- | --- | --- |
| Layer above RBAC | Cloud IAM (`roles/container.*`) | IAM principal → k8s identity mapping |
| Where the mapping lives | implicit — your gcloud identity *is* the k8s `User` | `aws-auth` ConfigMap, or `aws eks create-access-entry` |
| In-cluster objects | same `Role`/`RoleBinding`/`ClusterRole`/`ClusterRoleBinding` | same `Role`/`RoleBinding`/`ClusterRole`/`ClusterRoleBinding` |
| Fully-managed shortcut | predefined IAM roles like `roles/container.developer` | AWS-managed access policies like `AmazonEKSEditPolicy` |

Neither cloud replaces Kubernetes RBAC — both just add a layer on top
that decides which cloud identity gets to show up as which Kubernetes
`User`/`Group` in the first place. Everything from
[07-kubernetes-on-cloud.md](07-kubernetes-on-cloud.md) about wiring
`kubectl` still applies unchanged; this is what happens *after* that
wiring, on every single request.

---

## Cleanup

```bash
kubectl config use-context <your-original-context>
kubectl delete rolebinding dev-role-binding -n dev
kubectl delete clusterrolebinding cluster-viewer-binding
kubectl delete role dev-role -n dev
kubectl delete clusterrole cluster-viewer
kubectl delete serviceaccount dev-user -n dev
kubectl delete namespace dev
kubectl config delete-context dev-user-context
```

---

## Takeaway

RBAC is additive-only authorization checked on every API request: a
`Role`/`ClusterRole` defines the "what," a `RoleBinding`/
`ClusterRoleBinding` grants it to a "who" (`User`, `Group`, or
`ServiceAccount`), and `kubectl auth can-i` lets you verify the result
without risking the real action. On a managed cloud cluster this is only
half the picture — GKE and EKS both add an identity-mapping layer (Cloud
IAM roles, or `aws-auth`/access entries) that decides which cloud
principal gets to appear as which Kubernetes subject before RBAC ever
gets a chance to evaluate a rule.
