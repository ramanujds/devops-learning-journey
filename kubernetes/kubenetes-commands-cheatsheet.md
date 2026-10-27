# **Important Kubernetes Commands for Various Components**



## **1 Cluster & Node Management**
### **Get Cluster & Node Information**
```bash
kubectl cluster-info                 # Show cluster details  
kubectl get nodes                    # List all nodes in the cluster  
kubectl describe node <node-name>     # Detailed info about a node  
kubectl top node                      # Show CPU & memory usage of nodes  
kubectl cordon <node-name>            # Mark a node as unschedulable  
kubectl uncordon <node-name>          # Mark a node as schedulable  
kubectl drain <node-name>             # Evict all pods from a node  
kubectl delete node <node-name>       # Remove a node from the cluster  
```

---

## **2 Pod Management**
### **Create, Get & Describe Pods**
```bash
kubectl run my-pod --image=nginx                   # Create a new pod  
kubectl get pods                                   # List all pods  
kubectl get pods -o wide                           # Show pods with node & IP details  
kubectl describe pod <pod-name>                    # Show detailed info about a pod  
kubectl top pod                                    # Show resource usage of pods  
```

### **Pod Debugging & Logs**
```bash
kubectl logs <pod-name>                            # Get logs of a pod  
kubectl logs -f <pod-name>                         # Follow live logs  
kubectl exec -it <pod-name> -- /bin/sh             # Open a shell inside a pod  
kubectl port-forward <pod-name> 8080:80            # Forward pod port to local machine  
kubectl delete pod <pod-name>                      # Delete a pod  
```

---

## **3 Deployment Management**
### **Create, Get & Describe Deployments**
```bash
kubectl create deployment my-deployment --image=nginx   # Create a deployment  
kubectl get deployments                                 # List all deployments  
kubectl describe deployment <deployment-name>           # Show details of a deployment  
kubectl scale deployment my-deployment --replicas=3     # Scale a deployment  
kubectl delete deployment <deployment-name>             # Delete a deployment  
```

### **Updating & Rolling Back Deployments**
```bash
kubectl set image deployment/my-deployment nginx=nginx:1.19  # Update the image  
kubectl rollout status deployment/my-deployment             # Check rollout status  
kubectl rollout history deployment/my-deployment           # View rollout history  
kubectl rollout undo deployment/my-deployment             # Rollback last deployment  
```

---

## **4 Service & Networking**
### **Get & Create Services**
```bash
kubectl expose pod my-pod --type=ClusterIP --port=80        # Create a service for a pod  
kubectl expose deployment my-deployment --type=LoadBalancer --port=80  # Expose a deployment  
kubectl get services                                        # List all services  
kubectl describe service <service-name>                     # Show details of a service  
kubectl delete service <service-name>                       # Delete a service  
```

### **Access Services**
```bash
kubectl get svc my-service                                 # Get service details  
minikube service my-service                               # Open service in browser (Minikube)  
kubectl port-forward service/my-service 8080:80          # Forward service port to local machine  
```

---

## **5 ConfigMaps & Secrets**
### **ConfigMaps**
```bash
kubectl create configmap my-config --from-literal=key=value  # Create a configmap  
kubectl get configmaps                                       # List all configmaps  
kubectl describe configmap my-config                         # Show details of a configmap  
kubectl delete configmap my-config                           # Delete a configmap  
```

### **Secrets**
```bash
kubectl create secret generic my-secret --from-literal=password=supersecret  # Create a secret  
kubectl get secrets                                                          # List secrets  
kubectl describe secret my-secret                                            # Show details of a secret  
kubectl delete secret my-secret                                              # Delete a secret  
```

---

## **6 Ingress Management (For External Access)**
```bash
kubectl get ingress                   # List all ingress rules  
kubectl describe ingress my-ingress    # Show details of an ingress  
kubectl delete ingress my-ingress      # Delete an ingress  
```

---

## **7 Namespace Management**
```bash
kubectl get namespaces                 # List all namespaces  
kubectl create namespace my-namespace   # Create a new namespace  
kubectl delete namespace my-namespace   # Delete a namespace  
kubectl get pods --namespace=my-namespace  # Get pods in a specific namespace  
```

---

## **8 Role-Based Access Control (RBAC)**
```bash
kubectl create role my-role --verb=get,list --resource=pods  # Create a role  
kubectl get roles                                             # List roles  
kubectl describe role my-role                                 # Show details of a role  
kubectl delete role my-role                                   # Delete a role  
```

---

## **9 Persistent Volumes & Storage**
### **Persistent Volumes (PV) & Persistent Volume Claims (PVC)**
```bash
kubectl get pv                                              # List all persistent volumes  
kubectl get pvc                                             # List all persistent volume claims  
kubectl describe pvc my-pvc                                 # Show details of a PVC  
kubectl delete pvc my-pvc                                   # Delete a PVC  
```

---

## **10 Monitoring & Debugging**
### **Events & Logs**
```bash
kubectl get events                                          # Get cluster events  
kubectl describe pod my-pod                                 # Get detailed pod info & errors  
kubectl logs -f my-pod                                      # Follow live logs  
```

### **Debugging Failures**
```bash
kubectl get pods --all-namespaces                           # Check for failing pods  
kubectl describe pod my-pod                                 # Look for issues in pod events  
kubectl exec -it my-pod -- /bin/sh                          # Open shell inside the pod  
kubectl delete pod my-pod                                   # Restart pod (if needed)  
```

---

## **Summary Table of Key Commands**

| **Component**  | **Command Example** |
|---------------|--------------------|
| **Cluster Info** | `kubectl cluster-info` |
| **List Nodes** | `kubectl get nodes` |
| **List Pods** | `kubectl get pods -o wide` |
| **Describe Pod** | `kubectl describe pod my-pod` |
| **Get Logs** | `kubectl logs my-pod` |
| **Exec into Pod** | `kubectl exec -it my-pod -- /bin/sh` |
| **Create Deployment** | `kubectl create deployment my-deployment --image=nginx` |
| **Scale Deployment** | `kubectl scale deployment my-deployment --replicas=3` |
| **Rollback Deployment** | `kubectl rollout undo deployment/my-deployment` |
| **Expose Deployment** | `kubectl expose deployment my-deployment --type=NodePort --port=80` |
| **Port Forwarding** | `kubectl port-forward pod/my-pod 8080:80` |
| **Create Namespace** | `kubectl create namespace my-namespace` |
| **View Persistent Volumes** | `kubectl get pv` |


