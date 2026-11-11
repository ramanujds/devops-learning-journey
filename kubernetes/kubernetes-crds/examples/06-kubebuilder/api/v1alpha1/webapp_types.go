package v1alpha1

import (
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

// WebAppSpec is what the user asks for (same fields as the part-5 WebApp).
type WebAppSpec struct {
	// Container image to run (required: no +optional marker).
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

// WebAppStatus is what the controller observed.
type WebAppStatus struct {
	ReadyReplicas int32 `json:"readyReplicas,omitempty"`
}

// The markers below turn into CRD YAML when you run `make manifests`.

// +kubebuilder:object:root=true
// +kubebuilder:subresource:status
// +kubebuilder:resource:shortName=wa
// +kubebuilder:printcolumn:name="Image",type=string,JSONPath=`.spec.image`
// +kubebuilder:printcolumn:name="Desired",type=integer,JSONPath=`.spec.replicas`
// +kubebuilder:printcolumn:name="Ready",type=integer,JSONPath=`.status.readyReplicas`

// WebApp is the Schema for the webapps API.
type WebApp struct {
	metav1.TypeMeta   `json:",inline"`
	metav1.ObjectMeta `json:"metadata,omitempty"`

	Spec   WebAppSpec   `json:"spec"`
	Status WebAppStatus `json:"status,omitempty"`
}

// +kubebuilder:object:root=true

// WebAppList contains a list of WebApp.
type WebAppList struct {
	metav1.TypeMeta `json:",inline"`
	metav1.ListMeta `json:"metadata,omitempty"`
	Items           []WebApp `json:"items"`
}

func init() {
	SchemeBuilder.Register(&WebApp{}, &WebAppList{})
}
