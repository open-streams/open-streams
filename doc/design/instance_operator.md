# Instance Operator

## Architecture

### Frontend

#### Overview

The frontend of the controller responds to Kubernetes `add`, `modify`, `delete`
operations. There is very good support for this in Go's kubernetes client
interface, but other languages can also be used. If the controller is restarted,
the event cache synchronizes itself with Kubernetes and throws
_synchronization_ `ADDITION` events.

#### Implementation

To ease the integration of the backend, the frontend is written in Java. It uses
the `fabric8` Kubernetes client. It also uses the `microbean-controller-cache`
library to provide the implementation details of the Kubernetes controller
interface.

It is strongly recommended to
read [Understanding controllers](https://lairdnelson.wordpress.com/2018/01/07/understanding-kubernetes-tools-cache-package-part-0/)
. It is fairly technical but clearly explains the various pieces involved in
building and running a controller.

### Backend

The backend of the controller is a lightweight version of SAM that covers the
ADL -> Logical Model -> Topology Model -> Fuser -> AADL transformations. The
initial PE count can be either arbitrary or passed to the backend as an argument
through the Job description.

![v5_pipeline](https://user-images.githubusercontent.com/73404/108544288-8be4bc00-72e6-11eb-9cc0-94f45cc8dcb1.png)

The backend role is double:

1. Generate a set of AADL from an initial ADL within Job submission request.
2. Regenerate a set of AADL using an existing ADL from a PE modification
   request.

The first role is expected to only happen once at Job submission time and is not
necessarily time sensitive (although it should happen in a short enough time).
The second role is expected to happen more often and reactively (eg. at the
request of an external load balancer) and is therefore time sensitive.

### Job

A `Job` custom resource represents a Streams job. For each job `job`, multiple
processing elements `job-0`, `job-1`, ... are created. A job can be created as
follows:

```yaml
apiVersion: streams.ibm.com/v1
kind: Job
metadata:
  name: apps-parallel
spec:
  bundle:
    name: apps.parallel.Parallel.sab
  fusion:
    manual: 3
```

A job `spec` accepts the following configuration:

| Name | Type | Description | Default |
|:-----|:-----|:------------|:--------|
| `bundle` | `dictionary` | [Bundle](#bundle) options | |
| `fusion` | `dictionary` | [Fusion](#fusion) options | |
| `imagePullPolicy` | `string` | `Always` or `IfNotPresent` | `Always` |
| `imagePullSecret` | `string` | Name of the Streams runtime image pull secret| `Always` |
| `processingElement` | `dictionary` | [Processing element](#processing-element) options | |
| `submissionTimeOutInSeconds` | `integer` | Submission time-out in seconds | `300` |
| `submissionTimeValues` | `dictionary` | Submission-time values | `null` |
| `threadingModel` | `dictionary` | [Threading model](#threading-model) options | |
| `transportCertificateValidityInDays` | `integer` | Validity period for the TLS certificate | 30 |
| `resources` | `dictionary` | [Compute resource](https://kubernetes.io/docs/concepts/configuration/manage-compute-resources-container/) options | |

The `resource` option can be used to define a job-wide resource request. It is
empty by default. The table below summarizes how this request is handled:

| Job has `resource` | PE has `resource` | Behavior | |:--:|:--:|:-| | 𐄂 | 𐄂 |
PEs use their defaults **regardless of their count** | | 𐄂 | ✓ | PEs use the
specified PE requests **regardless of their count** | | ✓ | 𐄂 | PEs use the
specified Job requests **divided by their count** | | ✓ | ✓ | PEs use the
specified Job requests **divided by their count** |

#### Bundle

Application bundles are fetched remotely from an URL and cached locally. A
bundle `spec`
of a job defines the properties of bundle to be used. The options are:

| Name | Type | Description |
|:-----|:-----|:------------|
| `name` | `string` | The bundle name |
| `pullPolicy` | `enum` | Either `IfNotPresent` or `Always`, default is `IfNotPresent` |
| `file` | `dictionary` |  A `FileSource` object |
| `github` | `dictionary` |  A `GithubSource` object |

The `name` option is mandatory. The `file` and `github` options are mutually
exclusive.

##### FileSource

| Name | Type | Description |
|:-----|:-----|:------------|
| `path` | `string` | A local path pointing to the bundle to be fetched |

##### GithubSource

| Name | Type | Description |
|:-----|:-----|:------------|
| `url` | `string` | An external URL pointing to the bundle to be fetched |
| `secret` | `integer` | A secret containing the GitHub personal token required to access `url` |

The `url` option is mandatory. If `secret` is specified, the `url` field must be
that of a GitHub repository compliant with the GitHub API v3 format:

```
https://api.${GITHUB_URL}/repos/${USER}/${REPOSITORY}/contents/${PATH_TO_BUNDLE}.sab
```

The `pullPolicy` option, if specified, determines whether or not the bundle is
actually fetched. The `pullPolicy` and `secret` options are only meaningful if
the `url` option is specified.

#### Fusion

A fusion `spec` of a job controls how the controller fuses operators into PEs.
The options are:

| Name | Type | Description |
|:-----|:-----|:------------|
| `type` | `enum` | Type of fusion within parallel regions |
| `automatic` | `boolean` | `true` enables automatic fusion, `false` disables it |
| `legacy` | `boolean` | `true` enables legacy fusion, `false` disables it |
| `manual` | `integer` | Manually specifies number of PEs |

The `type` option specifies the behavior of the fusion within parallel regions.
It can be left unset or set to one of these values:

| Name | Description |
|:-----|:------------|
| `noChannelInfluence` | Operators in parallel regions are treated like any other |
| `channelIsolation` | Operators in parallel regions are fused together per-channel |
| `channelExlocation` | Operators in parallel regions from different channels are not fused together|

The `automatic`, `legacy`, and `manual` options are mutually exclusive. It is a
logical error to request more than one kind of fusion.

#### Threading model

A threading model `spec` accepts the following configuration:

| Name | Type | Description |
|:-----|:-----|:------------|
| `automatic` | `boolean` | Use automatic fusion |
| `dedicated` | `boolean` | Use dedicated fusion |
| `dynamic` | `dictionary` | [Dynamic threading model](#dynamic-threading-model) |
| `manual` | `boolean` | Use manual fusion |

These options are mutually exclusive. It is a logical error to request more than
one threading model.

#### Dynamic threading model

A dynamic threading model `spec` accepts the following configuration:

| Name | Type | Description |
|:-----|:-----|:------------|
| `elastic` | `boolean` | Use elastic threading |
| `threadCount` | `integer` | Number of threads to use |

### Processing element

A `ProcessingElement` custom resource represents a Streams processing element.
For each processing element `job-N`, a pod with prefix `job-N-` is created.
Processing element resources are used to maintain Streams-specific states and
concepts that cannot be represented by pods, such as connectivity and launch
count.

#### Top-level layout

##### Image and runtime

| Name | Type | Description | Default |
|:-----|:-----|:------------|:--------|
| `buildType` | `string` | Build type of the runtime, either `debug` or `release` | `debug` |
| `imagePullPolicy` | `string` | `Always` or `IfNotPresent` | Inherited |
| `imagePullSecret` | `string` | Name of the Streams runtime image pull secret| Inherited |
| `sabName` | `string` | Name of the SAB to use | Inherited |
| `transportSecurityType` | `string` | `none` or `TLSv1.2` | `none` |
| `resources` | `dictionary` | [Compute resource](https://kubernetes.io/docs/concepts/configuration/manage-compute-resources-container/) options | No `limits`, `requests` is `100m` for `cpu` and `128Mi` for `memory` |

##### Debugging

The _debug_ version of the runtime ships with `valgrind`. It is possible to run
the PE runtime process in a `valgrind` context with the following options:

| Name | Type | Description | Default |
|:-----|:-----|:------------|:--------|
| `allowPtrace` | `boolean` | Enable the `SYS_PTRACE` capability in Docker | `false `|
| `runWithValgrind` | `boolean` | Implies `buildType = debug` and  `allowPtrace = true` | `false` |

The _debug_ image also contains `gdb`. The `allowPtrace` option is required to
attach the PE process inside `gdb`.

##### Logging and tracing

Streams runtime supports two domains of logging/tracing: the _application_
domain and the _runtime_ domain. The application domain covers log and trace
messages coming from the SPL application (`appLog` and `appTrc`) or native
operators (with the `SPLAPPTRC` macro). The runtime domain covers log and trace
messages coming from the runtime (with the `SPCDBG` macro).

| Name | Type | Description | Default |
|:-----|:-----|:------------|:--------------|
| `appTraceLevel` | `string` | Level of tracing for the application | `null` |
| `runtimeTraceLevel` | `string` | Level of tracing for the runtime | `INFO` |

The `appTraceLevel` option sets the level for the application domain. Unset by
default, the level defined in the AADL is used. When set, the specified value
override that of the AADL. The `runtimeTraceLevel` option sets the level for the
runtime domain.

##### Restart policies

Restart policies impact the way PE shutdown is handled by the processing element
controller. In Streams 4.3, PE shutdown is categorized as follows:

<img width="1116" alt="PE_Shutdown" src="https://user-images.githubusercontent.com/73404/108544381-a74fc700-72e6-11eb-8f8a-fa150dfd05ea.png">

External shutdowns triggered by the `streamtool` commands `stoppe` and
`restartpe`, and internal shutdowns triggered by crashes are are sensitive to
the `restartable` property of the affected operators. Other type of shutdowns do
not trigger a restart of the PE.

In Knative Streams, some of the notions used above are not directly mappable.
For instance, the lack of `streamtool` interface makes the behavior of `stoppe`
and `restartpe` irrelevant. However, users can voluntarily delete a pod if they
so wish.

The table below summarizes the various scenarios where a pod can be restarted in
Knative Streams:

| Name | Type | Description | Default |
|:-----|:-----|:------------|:--------------|
| `deleteFailedPod` | `boolean` | Delete failed pods | `true` |
| `restartCompletedPod` | `boolean` | Restart completed pods | `false` |
| `restartDeletedPod` | `boolean` | Restart manually deleted pods | `false` |
| `restartFailedPod` | `Boolean` | Restart failed pods | `null `|

The `restartCompletedPod` option covers the internal, explicit and implicit
shutdown situations. When `false`, pods that terminate with a
`Completed` status (i.e. with an exit status code of 0) are not restarted. When
`true`, these pods are restarted.

The `restartDeletedPod` covers the voluntary deletion of pods. When `false`,
voluntarily deleted pod are not restarted. When `true`, these pods are
restarted. As it is not trivial to determine whether or not a pod has been
voluntarily deleted, this option acts as a fall-through for terminating pods
with both `restartCompletedPod` and `restartFailedPod` set to `false`.

The `restartFailedPod` covers the error scenarios where a pod fails because of
an internal exception. When `null`, the instance controller uses
the `restartable`
configuration value derived from the owning processing element's configuration.
When `false`, pods that terminate with a `Failed` status (i.e. with an exit code
≠ 0) are not restarted. When `true`, these pods are restarted.

The `deleteFailedPod` is used to automatically delete failed pods. Although it
is `true` by default, this option is useful to diagnose situations where pods
would continuously fail, by inspecting their logs for instance.

##### Liveness probe

| Name | Type | Description | Default |
|:-----|:-----|:------------|:--------------|
| `initialProbeDelayInSeconds` | `integer` | Initial delay for the PE liveness probe | `30` |
| `probeFailureThreshold` | `integer` | Number of retries before a PE is killed by its liveness probe | `1` |
| `probeIntervalInSeconds` | `integer` | Probing interval for the PE liveness probe | `10` |

##### Data directory configuration

| Name | Type | Description | Default |
|:-----|:-----|:------------|:--------------|
| `dataPath` | `string` | Override `STREAMS_DATA_PATH` setup in the controller YAML | `null` |
| `dataVolumeClaim` | `dictionary` | [Data volume claim](#data-volume-claim) | `null` |

##### Checkpoint configuration

| Name | Type | Description | Default |
|:-----|:-----|:------------|:--------------|
| `checkpointRepository` | `dictionary` | [Checkpoint data store](#checkpoint-data-store) | `null` |

##### External properties

| Name | Type | Description | Default |
|:-----|:-----|:------------|:--------|
| `appPropertiesSecret` | `string` | The name of the secret to use as application properties | `null` |
| `restrictedPropertiesSecret` | `string` | The name of the secret to use as restricted properties | `null` |

#### Data volume claim

A data volume claim `spec` accepts the following configuration:

| Name | Type | Description |
|:-----|:-----|:------------|
| `name` | `string` | Name of the persistent volume claim |
| `subPath` | `string` | A path in the claim |

#### Checkpoint data store

A checkpoint data store `spec` accepts the following configuration:

| Name | Type | Description |
|:-----|:-----|:------------|
| `fileSystem` | `dictionary` | [Filesystem data store](#filesystem-data-store) |
| `redis` | `dictionary` | [Redis data store](#redis-data-store) |

##### Filesystem data store

| Name | Type | Description |
|:-----|:-----|:------------|
| `path` | `string` | The mount path of the checkpoints in PEs |
| `volumeClaim` | `dictionary` | [Data volume claim](#data-volume-claim) |

##### Redis data store

| Name | Type | Description |
|:-----|:-----|:------------|
| `replicaCount` | `integer` | The number of replicas to use |
| `restrictedProperty` | `string` | The restricted property to use as a password for redis |
| `service` | `string` | The name of the redis service associated to the redis `ReplicaSet` |
| `shardCount` | `integer` | The number of shards per replica to use |
| `shuffle` | `boolean` | Shuffle the redis server selection |

### Dependencies

Kubernetes keeps track of dependences between CRDs (eg. when a CRD is created as
a result of a CRD creation). When a CRD is deleted, the deletion can be
propagated 3-way:

* `Orphan`: the dependents are not deleted
* `Background`: the owner and dependents are deleted asynchronously
* `Foreground`: the owner is deleted _after_ all of its dependents have been
  deleted

The `kubectl` tool as well as the instance controller use the `Background`
policy by default. When job submission or UDP fail, the instance controller uses
the
`Foreground` deletion policy to restart the operation from a clean slate.

### Resource creation

#### Pods

The creation of a pod is controlled by the pod state machine. The pod state
machine checks the fulfillment of the following criterias before creating pods:

* Creation of the configmap that stores the AADL for the pod
* Modification event for the processing element either due to the increment of
  the launch count or changes in the content id
* Creation of all the hostpool CRDs for the job

During the job lifecycle, if there are modifications to processing elements
either due to the increment of the launch count or changes in the content id,
new pods are created and old pods are cleaned up.

### Resource deletion

#### Jobs

A job can only be deleted voluntarily by the user through `kubectl delete`. When
a job is deleted, all resources attached to the job are garbage collected by
Kubernetes.

#### Processing elements

A processing element can be deleted either voluntarily by the user, as a result
of a UDP resizing, or garbage collected by Kubernetes. The current policy when a
processing element is voluntarily deleted and a job still exists is to recreate
it automatically as long as its `restartable` flag permits it.

#### Pods

A pod can be deleted either voluntarily by the user or garbage collected by
Kubernetes. When a pod is deleted by the user and the `restartDeletedPod` option
of its processing element is set to `true`, the pod is automatically restarted.

A pod can fail as a result of an error or a liveness probe timeout. If a pod
fails and the `restartFailedPod` option of its processing element is set to
`true`, the pod is automatically restarted. That option can be either manually
adjusted or automatically defined depending on the PE's `restartable` status.

A pod can complete its operation, for instance as a result of calling the
`shutdownPe()` function. If a pod completes and the `restartCompletedPod` option
of its processing element is set to `true`, the pod is automatically restarted.

### App and restricted properties

Streams has the ability to store instance-related secrets as _restricted
properties_. This facility seem to be only used for the redis checkpoint
configuration. Support for these properties is now implemented using secrets.

## Challenges

### Type validation

Minimum Kubernetes version for support: 1.11.

### Type instance status

Minimum Kubernetes version for support: 1.11.

The logic associated with a CRD will have associated states. These states must
be reflected in the CRD statuses now available in Kubernetes version 1.11.

Statuses are fetched using the `/status` sub-resource of the CRD and are
displayed by defining an additional `STATUS` printer column.

### Cross-type dependencies

Processing elements can only be defined for existing jobs. If an invalid job is
specified then an error must be reported. It is unclear where that check should
be happening and how the error should be reported. An obvious solution is
throught the `/status` sub-resource, but `Event` resources can also be used.

## Open questions

### Replication

Scale in Kubernetes is handled through replication. The concept of replication
implies that replicated pods have identical functions or roles (i.e. multiple
instances of the same application). Replication for CRDs is handled by the
`/scale` sub-resource. This sub-resource is used as an interface with standard
replication controllers.

It is conceivable to use the concept of replica as a mean to control the arity
of heterogeneous resources. For instance, the replica count can be used to
control the number of running PEs even though PEs are not idempotent.

The advantage of this approach is to expose some sort of Streams elasticity to
the user using a familiar concept. Its downside is that it hijacks the concept
of replica and breaks compatibility with generic replica controllers. It could
also confuse users by falsely claiming idempotence for the processing elements.

The ideal solution is to have generic processing elements that can execute
arbitrary sets of operators and can switch between these sets over the course of
its execution. Processing elements would then behave like virtual machines
specialized in the execution of operators.

The current PE runtime is not flexible enough to fit in that solution. A PE is
given a set of operators and is defined by that set. It does not support
swapping this set for another set while running and no two PEs are identical.

The only exception to that statement are parallel regions configured with
channel isolation. In that setup, multiple identical channels are executed by
different PEs with identical sets of operators. Changing the arity of the
parallel region is done by adding or removing identical PEs.

It is then conceivable that Streams replicas compatible with Kubernetes' concept
of replica can be implemented by exposing to the user the parallel count of a
parallel region through the `replicaCount` property of the `/scale`
sub-resource.

To that end, the proper type capable of accurately modeling that replicated
resource must be defined. Jobs are too coarse-grained, while PEs are too
fine-grained (and almost irrelevant in the current state of the runtime).

### Application sizing and resource control

The number of PEs to fuse an application into is a parameter we must provide to
our fusion algorithms.

In the automatic fusion feature introduced in Streams 4.2, if users do not
specify the number of PEs, the default is to use the number of hosts in the
instance. Each PE would then be scheduled on one host. The assumption underlying
this heuristic is that Streams is the primary consumer and controller of all of
the hosts in an instance.

In the Kubernetes context, we can no longer assume that Streams is the primary
consumer of all of the available hosts. It's also more likely that the number of
available hosts will be greater than the number of hosts an application should
ideally use. As a consequence, we no longer have an obvious default number of
PEs to fuse a given application into. But we should be able to devise a
heuristic based on both inherent characteristics of the application, and from
Kubernetes itself:

1. Minimum number of necessary threads. We can inspect an application and count
   the number of necessary threads, including source operators, threaded ports,
   and input ports that must execute under the dynamic threading model.
2. Maximum number of threads. The total number of operators that may execute
   under the dynamic threading model, plus the minimum number of necessary
   threads, should yield the maximum number of threads an application can use.
3. Pod CPU limits. We should be able to determine the CPU limits of the pod the
   PEs will execute in.

The minimum CPU usage of any application is trivial to estimate: zero. That is
because the number of threads or operators present does not necessitate actual
CPU usage. (Imagine an application with one source operator that sends one tuple
and then goes to sleep.) But with the minimum number of necessary threads, we
can estimate a minimum-heavy-usage. That is, it's the CPU usage if we assume
each thread of the application is fully occupied with work, but the application
uses no more threads. The other side is the maximum number of CPUs the
application can use if it uses the maximum number of threads allowed, and all
threads are fully occupied with work. (Note that this discussion does not
consider multithreaded operators.)

While the above estimates are rough, they at least allow us to clearly recognize
the difference between an application with three operators and one thread, and
an application with 5,000 operators and dozens of threads. When we combine this
rough CPU usage projections with the pod minimums and limits, we can come up
with a range of number of PEs.

Ideally, we would also know the current CPU usage (not just the allocations) of
the entire cluster. Based on our current understanding, that information is not
currently available. If it were, we could use that information, along with the
above range of fusions, to make a final judgement of how many PEs to use.

Without actual CPU usage, we will need to make some judgement of where in the
spectrum of possible number of PEs we should use. We can get a better idea of
what to do as we experiment with it. We should also investigate how we could get
actual CPU usage metrics from Kubernetes.

## Resources

### Operator framework

* [Introduction](https://coreos.com/blog/introducing-operator-framework)
* [Getting started](https://github.com/operator-framework/getting-started)
* [Understanding controllers](https://lairdnelson.wordpress.com/2018/01/07/understanding-kubernetes-tools-cache-package-part-0/)
* [Study of various frameworks](https://admiralty.io/kubernetes-custom-resource-controller-and-operator-development-tools.html)

### Kubernetes client libraries

* [Go client](https://github.com/kubernetes/client-go)
* [Java client (generic)](https://github.com/kubernetes-client/java)
* [Java client (fabric8)](https://github.com/fabric8io/kubernetes-client/blob/main/README.md)
* [Microbean's controller cache](https://github.com/microbean/microbean-kubernetes-controller)
