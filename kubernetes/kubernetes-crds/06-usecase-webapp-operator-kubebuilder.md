# Part 6 — Use case 3: the `WebApp` operator in Go (Kubebuilder)

Same `WebApp`, same three steps as part 5 — but in Go, the way real
operators are built. **Kubebuilder** generates the project; you edit 2 files.

Files to copy in: [examples/06-kubebuilder/](examples/06-kubebuilder/)

| Part 5 (bash) | Part 6 (Go) |
| --- | --- |
| you write `crd.yaml` | generated from a Go struct |
| loop every 5 s | runs only when something changes |
| `kubectl apply` | `CreateOrUpdate(...)` |
| `ownerReferences:` block | `SetControllerReference(...)` |

---

## Step 0: Install tools

```bash
go version                 # 1.24+
brew install kubebuilder   # or see https://book.kubebuilder.io/quick-start
kubebuilder version
```

Remove the part-5 CRD (same name):

```bash
kubectl delete crd webapps.platform.example.com --ignore-not-found
```

---

## Step 1: Create the project

Outside this repo:

```bash
mkdir -p ~/code/webapp-operator && cd ~/code/webapp-operator

kubebuilder init --domain example.com --repo github.com/example/webapp-operator
kubebuilder create api --group platform --version v1alpha1 --kind WebApp --resource --controller
```

The two files you'll edit:

- `api/v1alpha1/webapp_types.go` — the fields (becomes the CRD)
- `internal/controller/webapp_controller.go` — the logic

---

## Step 2: Define the fields

In `api/v1alpha1/webapp_types.go`, replace `WebAppSpec` and `WebAppStatus`
(full file: [webapp_types.go](examples/06-kubebuilder/api/v1alpha1/webapp_types.go)):

```go
type WebAppSpec struct {
	Image string `json:"image"`

	// +kubebuilder:default=1
	// +optional
	Replicas *int32 `json:"replicas,omitempty"`

	// +kubebuilder:default=8080
	// +optional
	Port int32 `json:"port,omitempty"`

	// +optional
	Env []corev1.EnvVar `json:"env,omitempty"`
}

type WebAppStatus struct {
	ReadyReplicas int32 `json:"readyReplicas,omitempty"`
}
```

`// +kubebuilder:...` comments are **markers** — they become CRD YAML
(`default: 1`, etc.). Generate the CRD:

```bash
make manifests
cat config/crd/bases/platform.example.com_webapps.yaml
```

---

## Step 3: Write the logic

Replace `internal/controller/webapp_controller.go` with
[webapp_controller.go](examples/06-kubebuilder/internal/controller/webapp_controller.go).
The core:

```go
func (r *WebAppReconciler) Reconcile(ctx context.Context, req ctrl.Request) (ctrl.Result, error) {
	// 1. READ the WebApp
	var app platformv1alpha1.WebApp
	if err := r.Get(ctx, req.NamespacedName, &app); err != nil {
		return ctrl.Result{}, client.IgnoreNotFound(err)
	}
	labels := map[string]string{"app": app.Name}

	// 2. APPLY the Deployment (the Service is done the same way)
	dep := &appsv1.Deployment{ObjectMeta: metav1.ObjectMeta{Name: app.Name, Namespace: app.Namespace}}
	_, err := controllerutil.CreateOrUpdate(ctx, r.Client, dep, func() error {
		dep.Spec.Replicas = app.Spec.Replicas
		dep.Spec.Selector = &metav1.LabelSelector{MatchLabels: labels}
		dep.Spec.Template.Labels = labels
		dep.Spec.Template.Spec.Containers = []corev1.Container{{
			Name:  "app",
			Image: app.Spec.Image,
			Ports: []corev1.ContainerPort{{ContainerPort: app.Spec.Port}},
			Env:   app.Spec.Env,
		}}
		return controllerutil.SetControllerReference(&app, dep, r.Scheme)
	})
	if err != nil {
		return ctrl.Result{}, err
	}

	// 3. WRITE STATUS
	app.Status.ReadyReplicas = dep.Status.ReadyReplicas
	return ctrl.Result{}, r.Status().Update(ctx, &app)
}
```

And *when* to run it:

```go
ctrl.NewControllerManagedBy(mgr).
	For(&platformv1alpha1.WebApp{}).   // when a WebApp changes
	Owns(&appsv1.Deployment{}).        // when one of its Deployments changes
	Owns(&corev1.Service{}).
	Complete(r)
```

```bash
make manifests      # also generates RBAC from the // +kubebuilder:rbac markers
```

---

## Step 4: Run it

```bash
kubectl config current-context      # kind-crd-lab
make install                        # install the CRD
make run                            # run the operator in this terminal
```

In another terminal:

```bash
kubectl apply -f config/samples/platform_v1alpha1_webapp.yaml   # or examples/06-kubebuilder/config/samples/...
kubectl get wa

kubectl scale deploy/hello --replicas=1
kubectl get deploy hello            # fixed straight away - no 5 s wait

kubectl delete wa hello
kubectl get deploy,svc              # cleaned up
```

The part-5 [part-order-app.yaml](examples/05-webapp-controller/part-order-app.yaml) works unchanged.

---

## Clean up

```bash
# Ctrl+C make run, then:
make uninstall
```

**Takeaway:** same *read → apply → write status* as part 5 — Kubebuilder
adds the watching, retries and code generation for you.

Next: [07-cheatsheet.md](07-cheatsheet.md)
