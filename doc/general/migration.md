# Migrating to OpenStreams

OpenStreams shares the same runtime, compiler, and standard libraries as IBM
Streams v4.3. Both products simply run on different platforms. This guide
highlights the common concepts between both platforms and proposes a cheat sheet
to ease the migration process.

## Concepts

### Platform

| IBM Streams v4.3          | OpenStreams           |
|---------------------------|-----------------------|
| Domain                    | Cluster               |
| Instance                  | Namespace             |
| Host                      | Node                  |
| Host controller           | Kubelet               |
| Resource Manager (SRM)    | API controller        |
| Application Manager (SAM) | Streams operator      |
| Console (SWS)             | Dashboard and Grafana |

### Business logic

IBM Streams v4.3 and OpenStreams both share the same business logic concepts:
Job, Processing Element, Streams, Operator, Port, Tuple, etc. are common to both
products.

## Cheat sheet

| Operation | IBM Streams v4.3 | OpenStreams |
|-----------|------------------|-------------|
| Create a "domain" | [IBM documentation](https://www.ibm.com/docs/en/streams/4.2.1?topic=resource-creating-basic-domain-instance) | [Create a Kubernetes cluster]() |
| Create an instance | `streamtool mkinstance` | `kubectl create ns` |
| Delete an instance | `streamtool rminstance` | `kubectl delete ns` |
| Create a job | `streamtool submitjob` | `kubectl apply -f job.yaml` |
| List jobs | `streamtool lsjobs` | `kubectl get streamsjobs` |
| Delete a job | `streamtool canceljob` | `kubectl delete -f job.yaml` |
| List PEs | `streamtool lspes` | `kubectl get streamspes` |
| Add a new host | `streamtool addhost` | `kubeadm join` |
| List hosts | `streamstool lshosts` | `kubectl get nodes` |
| Delete a host | `streamtool rmhost` | `kubectl delete node` |
