package controller

import (
	"context"

	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/util/intstr"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/controller/controllerutil"
	logf "sigs.k8s.io/controller-runtime/pkg/log"

	platformv1alpha1 "github.com/example/webapp-operator/api/v1alpha1"
)

// WebAppReconciler reconciles a WebApp object.
type WebAppReconciler struct {
	client.Client
	Scheme *runtime.Scheme
}

// RBAC markers -> config/rbac/role.yaml when you run `make manifests`.
// +kubebuilder:rbac:groups=platform.example.com,resources=webapps,verbs=get;list;watch;create;update;patch;delete
// +kubebuilder:rbac:groups=platform.example.com,resources=webapps/status,verbs=get;update;patch
// +kubebuilder:rbac:groups=platform.example.com,resources=webapps/finalizers,verbs=update
// +kubebuilder:rbac:groups=apps,resources=deployments,verbs=get;list;watch;create;update;patch;delete
// +kubebuilder:rbac:groups="",resources=services,verbs=get;list;watch;create;update;patch;delete

// Reconcile does the same 3 steps as controller.sh in part 5: READ -> APPLY -> WRITE STATUS.
func (r *WebAppReconciler) Reconcile(ctx context.Context, req ctrl.Request) (ctrl.Result, error) {
	log := logf.FromContext(ctx)

	// 1. READ the WebApp
	var app platformv1alpha1.WebApp
	if err := r.Get(ctx, req.NamespacedName, &app); err != nil {
		// NotFound = it was deleted; owner references clean up the children for us
		return ctrl.Result{}, client.IgnoreNotFound(err)
	}
	labels := map[string]string{"app": app.Name}

	// 2. APPLY the Deployment (create it, or update it to match the WebApp)
	dep := &appsv1.Deployment{ObjectMeta: metav1.ObjectMeta{Name: app.Name, Namespace: app.Namespace}}
	op, err := controllerutil.CreateOrUpdate(ctx, r.Client, dep, func() error {
		dep.Spec.Replicas = app.Spec.Replicas
		dep.Spec.Selector = &metav1.LabelSelector{MatchLabels: labels}
		dep.Spec.Template.Labels = labels
		dep.Spec.Template.Spec.Containers = []corev1.Container{{
			Name:  "app",
			Image: app.Spec.Image,
			Ports: []corev1.ContainerPort{{ContainerPort: app.Spec.Port}},
			Env:   app.Spec.Env,
		}}
		return controllerutil.SetControllerReference(&app, dep, r.Scheme) // ownerReference
	})
	if err != nil {
		return ctrl.Result{}, err // returning an error = retry later with backoff
	}
	log.Info("deployment", "name", dep.Name, "result", op)

	//    ...and the Service
	svc := &corev1.Service{ObjectMeta: metav1.ObjectMeta{Name: app.Name, Namespace: app.Namespace}}
	if _, err := controllerutil.CreateOrUpdate(ctx, r.Client, svc, func() error {
		svc.Spec.Selector = labels
		svc.Spec.Ports = []corev1.ServicePort{{Port: app.Spec.Port, TargetPort: intstr.FromInt32(app.Spec.Port)}}
		return controllerutil.SetControllerReference(&app, svc, r.Scheme)
	}); err != nil {
		return ctrl.Result{}, err
	}

	// 3. REPORT what we observed into .status
	app.Status.ReadyReplicas = dep.Status.ReadyReplicas
	if err := r.Status().Update(ctx, &app); err != nil {
		return ctrl.Result{}, err
	}
	return ctrl.Result{}, nil
}

// SetupWithManager tells controller-runtime WHEN to call Reconcile.
func (r *WebAppReconciler) SetupWithManager(mgr ctrl.Manager) error {
	return ctrl.NewControllerManagedBy(mgr).
		For(&platformv1alpha1.WebApp{}). // any WebApp change
		Owns(&appsv1.Deployment{}).      // any change to a Deployment we own (drift, pods becoming ready)
		Owns(&corev1.Service{}).         // any change to a Service we own
		Named("webapp").
		Complete(r)
}
