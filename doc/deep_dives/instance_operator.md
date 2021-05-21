# Instance Operator

What we call the *instance operator* is the Java class that instantiates all of 
the other Java classes that implement the CRDs. It lives at:

> [`com.ibm.streams.controller.instance.Main`](https://github.com/IBMStreams/OSStreams/blob/main/src/java/platform/com.ibm.streams.controller/src/com/ibm/streams/controller/instance/Main.java)

## Responsibilities

The instance operator has six main tasks:

1. Create the *stores* for each CRD. Since CRDs represent Streams concepts such as 
   jobs, PEs or consistent regions, this means that these stores are how
   Kubernetes tracks all objects relavent to Streams.

2. Create the *factories* for each CRD. These are objects that follow the
   standard factory pattern: they know how to create new objects of the CRD
   kind. Most of the factories just need access to the Kubernetes client, but
   some require more information.  For example, the [PE factory][pe_factory] also requires
   access to the job store, since PE creation requires knowing about the job
   itself.

3. Create the *controllers* for each CRD. The controllers are the things that
   react to new events on a CRD (specifically, creation, deletion and
   modification). All controllers need access to their corresponding store and
   factory. But most controllers also need access to some other stores and
   factories. For example, the [PE controller][pe_controller] also needs access to the job store,
   the pod factory and the configmap factory.

4. Start the controllers.

5. Wait for the shutdown signal.

6. Close the controllers.

Steps 1 through 4 are the startup phase, and then step 5 is normal operation:
all of the controllers are ready to react to Streams-related events as delivered
by Kubernetes.

## Reliability

Because the instance operator is a [Kubernetes Deployment][kube_deployment], Kubernetes will
restart a new pod with the same image if it crashes or a user deletes it. For
example, the standard way that we refresh code in the instance operator after
compiling, installing and creating new images is to delete it:
```
[scoschne@c0321] kubectl get pods
NAME                                         READY   STATUS    RESTARTS   AGE
streams-instance-operator-7f9b7cf98f-5mzcp   1/1     Running   0          16d
streams-repository-6ff88f698-4t678           1/1     Running   0          30d
[scoschne@c0321] kubectl delete pod -l svc=instance-operator
pod "streams-instance-operator-7f9b7cf98f-5mzcp" deleted
[scoschne@c0321] kubectl get pods
NAME                                         READY   STATUS    RESTARTS   AGE
streams-instance-operator-7f9b7cf98f-vnklr   1/1     Running   0          14s
streams-repository-6ff88f698-4t678           1/1     Running   0          30d
```

Note that this operation is safe: whatever Streams objects (such as jobs, PEs,
imports and exports, consistent regions, etc.) that were created prior to the
deletion are re-created and restored when the instance operator restarts.
Kubernetes manages storing CRDs for us. This leads to an important principle:
whatever we want to remember across failures must be stored in a CRD.

[pe_factory]: https://github.com/IBMStreams/OSStreams/blob/main/src/java/platform/com.ibm.streams.controller/src/com/ibm/streams/controller/instance/Main.java#L121
[pe_controller]: https://github.com/IBMStreams/OSStreams/blob/main/src/java/platform/com.ibm.streams.controller/src/com/ibm/streams/controller/instance/Main.java#L142
[kube_deployment]: https://kubernetes.io/docs/concepts/workloads/controllers/deployment/
