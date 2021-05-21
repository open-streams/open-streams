# Installation YAMLs

This deep dive covers the files one needs to apply to a bare Kubernetes cluster
to install Streams. We focus on just the YAMLs to help provide an overview of
all of the components that are involved in making Knative Streams work.

## com.ibm.streams.controller

Frst we need to establish where most of the new code lives:

> [`com.ibm.streams.controller`](https://github.com/IBMStreams/OSStreams/blob/main/src/java/platform/com.ibm.streams.controller)

## CRDs: Custom Resource Descriptions

The CRDs are the core of Knative Streams: they are how we "explain" core Streams
concepts to Kubernetes. This starts with a YAML which specifies the basics. All
of the CRD YAMLs are in:

> [`com.ibm.streams.controller/crds`](https://github.com/IBMStreams/OSStreams/blob/main/src/java/platform/com.ibm.streams.controller/crds)

Users apply these files through a command such as:
```
kubectl apply -f crds/streams-job.yaml
```
After having done so, such resources are now things Kubernetes knows about, and
can be queried like any other pre-existing concept such as pods or deployments.

## The Instance Operator

The YAML for the Instance Operator is at:

> [`com.ibm.streams.controller/templates/operators/streams-instance-operator.yaml`](https://github.com/IBMStreams/OSStreams/blob/main/src/java/platform/com.ibm.streams.controller/templates/operators/streams-instance-operator.yaml)

[getting_started]: ../general/Getting_Started.md
