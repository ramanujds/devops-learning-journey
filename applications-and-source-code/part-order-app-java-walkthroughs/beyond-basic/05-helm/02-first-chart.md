# Step 2 — Your First Chart: nginx-app

> [Back to index](README.md) · Previous: [Step 1](01-concepts.md) · Next: [Step 3 — Releases](03-releases.md)

## Goal

Build a minimal chart with a Deployment and a Service, driven by four values, and render it without installing anything.

## Why this matters

A chart is only a folder with a required shape. Seeing that, with nothing
extra, removes the mystery: `Chart.yaml` names it, `values.yaml` holds defaults,
and every file in `templates/` is rendered with those values. The two template
objects you will use constantly are `.Values` (what the user supplied, merged
over the defaults) and `.Release` (facts about this install, such as its name).

Using `{{ .Release.Name }}` in resource names is the first design decision. Two
installs of the same chart into one namespace would otherwise create two
objects called `nginx` and collide. Prefixing with the release name makes each
install self-contained, which is also why the real chart in Step 4 builds
every name from helpers.

Everything here is Helm templating: `{{ ... }}` is replaced by a value, and
anything outside the braces is copied through unchanged.

## 1. The chart metadata

In `~/helm-lab/`, create the folder structure:

```bash
mkdir -p ~/helm-lab/nginx-app/templates && cd ~/helm-lab/nginx-app
```

Create `Chart.yaml`. Only the first three keys are required; the comments are what `helm create` writes, kept so the file matches the repo.

```yaml
apiVersion: v2
name: nginx-app
description: A Helm chart for Kubernetes

# A chart can be either an 'application' or a 'library' chart.
#
# Application charts are a collection of templates that can be packaged into versioned archives
# to be deployed.
#
# Library charts provide useful utilities or functions for the chart developer. They're included as
# a dependency of application charts to inject those utilities and functions into the rendering
# pipeline. Library charts do not define any templates and therefore cannot be deployed.
type: application

# This is the chart version. This version number should be incremented each time you make changes
# to the chart and its templates, including the app version.
# Versions are expected to follow Semantic Versioning (https://semver.org/)
version: 0.1.0

# This is the version number of the application being deployed. This version number should be
# incremented each time you make changes to the application. Versions are not expected to
# follow Semantic Versioning. They should reflect the version the application is using.
# It is recommended to use it with quotes.
appVersion: "1.16.0"
```

- `apiVersion: v2` is the Helm 3+ chart format.
- `version` is the chart's own version; bump it when templates change. `appVersion` describes the software inside (nginx here) and is informational.

## 2. The default values

Create `values.yaml`:

```yaml
replicaCount: 1
image:
  repository: nginx
  tag: "1.25"
service:
  type: NodePort
  port: 80
```

Every key here becomes `.Values.<key>`: `replicaCount` is `.Values.replicaCount`, and `image.tag` is `.Values.image.tag`. Quote `"1.25"` so YAML does not read it as a number and drop a trailing zero.

## 3. The Deployment template

Create `templates/deployment.yaml`:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ .Release.Name }}-nginx
spec:
  replicas: {{ .Values.replicaCount }}
  selector:
    matchLabels:
      app: {{ .Release.Name }}
  template:
    metadata:
      labels:
        app: {{ .Release.Name }}
    spec:
      containers:
        - name: nginx
          image: "{{ .Values.image.repository }}:{{ .Values.image.tag }}"
          ports:
            - containerPort: 80
```

Notice `image: "{{ ... }}:{{ ... }}"`: two values joined by a literal colon, in quotes so the result is always a string.

## 4. The Service template

Create `templates/service.yaml`:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: {{ .Release.Name }}-nginx
spec:
  type: {{ .Values.service.type }}
  selector:
    app: {{ .Release.Name }}
  ports:
    - port: {{ .Values.service.port }}
      targetPort: 80
```

`selector.app` matches the Pod label from the Deployment because both use `{{ .Release.Name }}`: the same label contract as walkthrough 03, now generated.

## Try it

Rendering needs a release name but no cluster:

```bash
cd ~/helm-lab
helm lint nginx-app
helm template web nginx-app
```

Expected: `1 chart(s) linted, 0 chart(s) failed` (with an informational note that an icon is recommended), then two manifests named `web-nginx`, the Service first and the Deployment second. Helm sorts objects by kind so that, for example, Services and Namespaces are created before the things that use them.

Change a value two ways and watch the output change:

```bash
helm template web nginx-app --set replicaCount=3 | grep replicas
helm template shop nginx-app | grep -E "name:|app:"
```

Expected: `replicas: 3`, then every name and label switching from `web` to `shop`.

Look at one file at a time:

```bash
helm template web nginx-app --show-only templates/service.yaml
```

Now make the mistake everyone makes once. Misspell a value in a copy of the template:

```bash
cp -R nginx-app /tmp/nginx-typo
sed -i.bak 's/Values.replicaCount/Values.replicaCont/' /tmp/nginx-typo/templates/deployment.yaml
helm template web /tmp/nginx-typo | grep replicas
helm lint /tmp/nginx-typo | tail -2
rm -rf /tmp/nginx-typo
```

Expected: `replicas: ` with nothing after it, and `helm lint` still reports `0 chart(s) failed`. A missing value renders as empty, not as an error, and Kubernetes would later reject or mis-handle the result. That is why you read the rendered output, and why production charts use `required` for values that must be set (exercise 2).

## Checkpoint

<details>
<summary>Full <code>nginx-app/Chart.yaml</code></summary>

```yaml
apiVersion: v2
name: nginx-app
description: A Helm chart for Kubernetes

# A chart can be either an 'application' or a 'library' chart.
#
# Application charts are a collection of templates that can be packaged into versioned archives
# to be deployed.
#
# Library charts provide useful utilities or functions for the chart developer. They're included as
# a dependency of application charts to inject those utilities and functions into the rendering
# pipeline. Library charts do not define any templates and therefore cannot be deployed.
type: application

# This is the chart version. This version number should be incremented each time you make changes
# to the chart and its templates, including the app version.
# Versions are expected to follow Semantic Versioning (https://semver.org/)
version: 0.1.0

# This is the version number of the application being deployed. This version number should be
# incremented each time you make changes to the application. Versions are not expected to
# follow Semantic Versioning. They should reflect the version the application is using.
# It is recommended to use it with quotes.
appVersion: "1.16.0"
```

</details>


This matches [nginx-app/Chart.yaml](../../../part-order-app-java/helm/nginx-app/Chart.yaml) exactly.

<details>
<summary>Full <code>nginx-app/values.yaml</code></summary>

```yaml
replicaCount: 1
image:
  repository: nginx
  tag: "1.25"
service:
  type: NodePort
  port: 80
```

</details>

This matches [nginx-app/values.yaml](../../../part-order-app-java/helm/nginx-app/values.yaml) exactly (the repo file has no trailing newline).

<details>
<summary>Full <code>templates/deployment.yaml</code></summary>

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ .Release.Name }}-nginx
spec:
  replicas: {{ .Values.replicaCount }}
  selector:
    matchLabels:
      app: {{ .Release.Name }}
  template:
    metadata:
      labels:
        app: {{ .Release.Name }}
    spec:
      containers:
        - name: nginx
          image: "{{ .Values.image.repository }}:{{ .Values.image.tag }}"
          ports:
            - containerPort: 80
```

</details>


This matches [nginx-app/templates/deployment.yaml](../../../part-order-app-java/helm/nginx-app/templates/deployment.yaml) exactly.

<details>
<summary>Full <code>templates/service.yaml</code></summary>

```yaml
apiVersion: v1
kind: Service
metadata:
  name: {{ .Release.Name }}-nginx
spec:
  type: {{ .Values.service.type }}
  selector:
    app: {{ .Release.Name }}
  ports:
    - port: {{ .Values.service.port }}
      targetPort: 80
```

</details>


This matches [nginx-app/templates/service.yaml](../../../part-order-app-java/helm/nginx-app/templates/service.yaml) exactly.

## Common mistakes

| Symptom | Cause | Fix |
| --- | --- | --- |
| `Error: INSTALLATION FAILED: ... chart metadata (name or version) missing` | `Chart.yaml` missing or misnamed | file must be exactly `Chart.yaml` |
| `parse error ... unexpected "}"` | unbalanced `{{` `}}` | count braces; `helm template` names the file and line |
| `replicas:` blank in the output | misspelled value name | compare `.Values.x` with `values.yaml` keys |
| Image tag `1.2` instead of `1.20` | unquoted tag parsed as a number | quote version-like values in `values.yaml` |
| Names collide between installs | names not prefixed with `.Release.Name` | build every name from the release name |

Next: **[Step 3 — Releases](03-releases.md)**.
