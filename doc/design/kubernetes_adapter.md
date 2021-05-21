# Kubernetes Adapter (K8S)

## Purpose

K8S has five main responsibilities:

1. Instantiate, initialize and start the processing for the PE
2. Maintain a liveness probe which indicates to Kubernetes the health status of the PE
3. Collect and report PE metrics
4. Handle subscription changes for any `Export` operators inside of its PE
5. Handle consistency changes for any consistent region inside of its PE

Liveness, metrics, subscriptions and consistent regions are handled in separate
threads that are created before PE processing starts.

### Initialize and start the PE

This is the most basic task of the K8S component. It goes as follows:

1. Load the AADL for the PE
2. Instantiate the `PEImpl` object
3. Call `pe_->process()`
4. Wait for completion

K8S also catches standard exceptions that could arise withing `PEImpl::process`.

### Liveness probe

The liveness probe communicates health status by maintaining a file
`/tmp/healthy`. When that file is present, the PE is healthy. When the PE is not
healthy, it removes the file, and its absence indicates to Kubernetes that the
pod is no longer alive, and should be terminated.

The liveness thread asynchronously receives notifications on PE static
connection connection and disconnection events. The main thread continuously
monitors overall state changes and considers the PE to be healthy when all
static connections are connected, and unhealthy when any static connection is
lost.

We are still in the process of determining what the best design for liveness
should be.  The difficulty with the current approach is that connection state is
currently tied to the PE being restarted. We want to separate out PE connection
status from liveness.  The fundamental issue we need to resolve is that in the
Kubernetes way of thinking, the way to recover from an error condition is to
restart the component. This approach assumes a stateless view of computation.
Statefulness is assumed in the Streams approach, where components are only
restarted if they have failed catastrophically or the user has explicitly
requested it. The underlying assumption in the Streams world is that restarts
cause data loss, which we go to great lenghts to avoid.

Because of this difference in fundamental approaches, we have to be careful in
how Knative Streams takes advantage of Kubernetes' services. Both Kubernetes and
Streams defined states for their execution atoms. Both definitions are given
below with an attempt at a reconciliation.

#### Pod

##### Phases

The source of the information below is located [here](https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.13/#podstatus-v1-core).

| Name | Description |
|------|-------------|
| **Pending** | The Pod has been accepted by the Kubernetes system, but one or more of the Container images has not been created. This includes time before being scheduled as well as time spent downloading images over the network, which could take a while. |
| **Running** | The Pod has been bound to a node, and all of the Containers have been created. At least one Container is still running, or is in the process of starting or restarting. |
| **Succeeded** | All Containers in the Pod have terminated in success, and will not be restarted. |
| **Failed** | All Containers in the Pod have terminated, and at least one Container has terminated in failure. That is, the Container either exited with non-zero status or was terminated by the system. |
| **Unknown** | For some reason the state of the Pod could not be obtained, typically due to an error in communicating with the host of the Pod. |

#### Processing element

##### States

The source of the information below is located [here](https://www.ibm.com/support/knowledgecenter/SSCRJU_4.3.0/com.ibm.streams.admin.doc/doc/pe-status-values.html).

| Name | Description |
|------|-------------|
| **Constructed** | The PE is initializing. |
| **Restarting**  | The PE is restarting. |
| **Running**     | The PE is running. |
| **Starting**    | The PE is starting. |
| **Stopped**     | The PE is stopped. |
| **Stopping**    | The PE is stopping. |
| **Unknown**     | The domain controller service cannot be contacted to determine the current state of the PE. |

##### Transitions

The actual implementation of the state machine is [here](https://github.com/IBMStreams/OSStreams/blob/main/src/java/platform/com.ibm.streams.platform/src/com/ibm/streams/instance/sam/fsm/PeFsm.java#L43).

![pefsm](https://user-images.githubusercontent.com/73404/108543989-1ed12680-72e6-11eb-84a5-0b63b0c296a6.png)

#### Reconciliation

| Kubernetes        | Streams     |
|-------------------|-------------|
| Pending           | Constructed |
| Running           | Running     |
| Succeeded         | Stopped     |
| Failed            | ?           |
| Unknown           | Unknown     |
| Completed         | Stopped     |
| CrashLoopBackOff  | ?           |
| ?                 | Stopping    |
| ?                 | Starting    |

#### Health

Processing elements also carry the notion of health, as defined below (from the [documentation](https://www.ibm.com/support/knowledgecenter/SSCRJU_4.3.0/com.ibm.streams.studio.doc/doc/tusing-working-monitoring-spl-application.html)):

| Summary               | Description |
|-----------------------|-------------|
| **Healthy**               | Indicates that the PE is running and all of the required and optional connections are connected. | 
| **PartiallyHealthy**      | Indicates that the PE is running and all of the required connections are connected, but some of the optional connections are in the process of being connected. |
| **PartiallyUnhealthy**    | Indicates that the PE is not stopped or in the process of stopping, but either the state is not running or some required connections are not yet connected. |
| **Unhealthy**             | Indicates that the PE is stopped or is in the process of stopping. |

These health states conflate notions of connection status and PE running state.
We would like to replace them with states that indicate only connection status.

#### Connectivity

We define the notion of _connectivity_ to solely describe the status of a PE connections.
Connectivity has three states:

| Summary | Description |
|---------|-------------|
| **Full**    | All connections are established. |
| **Partial** | At least one connection is not established. |
| **None**    | No connection is established. |

The connectivity of a PE is monitored by the `K8SConnectivityThread`. It communicates the PE's
connectivity to the controller by mean of REST `PUT` operations on the
`/state/job/:name:/pe/:id:/connectivity` API endpoint.

#### Design goals

1. [X] Use as many native Kubernetes facilities as possible.
2. [X] Avoid restarting PEs unless they have crashed catastrophically or explicitly stopped or
       shutdown from user-code or outside request.
3. [X] Keep the concept of PE and pod running status independent of PE-to-PE connection status.

### Collecting and reporting metrics

Metrics management is handled by [Prometheus](https://prometheus.io) for
scraping and [Grafana](https://grafana.com) for graphing. The
[prometheus-cpp](https://github.com/jupp0r/prometheus-cpp) library is used in
the runtime to expose a Prometheus-compatible service in the pods.

#### Overview

##### Prometheus

Prometheus is the metrics scraper. Its role is to mostly collect metrics, but
it offers a crude web interface:

![prometheus](https://user-images.githubusercontent.com/73404/108544149-593ac380-72e6-11eb-9e2a-2e89b32d7367.jpg)

##### Grafana

Grafana is the grapher. It interfaces itself with Prometheus and can be
configured in many ways to display the collected metrics:

![grafana](https://user-images.githubusercontent.com/73404/108544147-57710000-72e6-11eb-8d0d-ad5b7db22e09.jpg)

#### Integration

##### Runtime

The Kubernetes PE start a metrics collector thread that periodically calls on
the `BasePEImpl::getMetrics()` API to collect the PE's metrics. It then converts
those metrics into a format that can be consumed by Prometheus using the
`prometheus-cpp` library.

K8S metrics reporting makes heavy use of the Promethesus concept of
[*labels*](https://prometheus.io/docs/practices/naming/). Each metric has a
name, but we also add appropriate labels. For example, the metric
`pe_input_tuples_processed` metric stored in Promethesus maps to
`nTuplesProcessed` for PE input ports as described in the
[Streams documentation](https://www.ibm.com/support/knowledgecenter/SSCRJU_4.3.0/com.ibm.streams.dev.doc/doc/metricaccess.html).
K8S registers a new `pe_input_tuples_processed` metric for each PE input port in
the application. Each of these metrics also has two additional labels which
identify it:

* `pe`: the PE ID that contains the input port
* `pe_port`: the port ID of that input port in the PE.

As a further example, the metric `op_input_tuples_submitted` stored in
Prometheus maps to `nTuplesSubmitted` for operator output ports. Each of these
metrics has four labels which identify it:

* `pe`: the PE ID that contains the operator
* `op`: the operator index within the PE
* `name`: the name of the operator 
* `logical_name`: the logical name of the operator 
* `op_port`: the index of the output port within the operator

Metrics with the same name are easily graphed by Grafana, and they are
distinguished by their labels.

Operator custom metrics create an edge-case, as they can be created by user-code
at runtime at any time. At first glance, the Prometheus model works well, as it
easily allows multiple operators to create the same metric name, which are then
distinguished by their labels. The problem arises in that custom metrics created
by different operators do not necessarily represent the same measured quantity.
They are not even required to be the same *type*. It is possible we could
encounter a scenario in which two different operators create custom metrics with
the same name but different types, and in that case, what will happen is (at the
moment) unknown.

The Prometheus-way of storing metrics does cause some awkwardness in K8S. If we
imagine the relationship of PEs, PE ports, operators and operator ports as a
tree with the appropriate metrics for each entity as a leaf node, K8S must
maintain just those leaf nodes. This requirement makes the data structures
storing each kind of metric nested in an unintuitive manner, as are the
algorithms iterating over these structures.

##### Services

Prometheus knows about which pod to scrap by using a `ServiceMonitor` custom
resource definition. That CRD uses a label selector to that end. The controller
has been modified to add specific labels used by the `ServiceMonitor` to collect
the PE pods.

##### Grafana

By defaults, Grafana starts up empty. We configure it by using `ConfigMap`
resources that are mounted in Grafana's provisioning directories. With this
method, both data source and dashboard definitions can be scripted and
automatically generated depending on the user's situation and application.

Grafana also has a plugin API that can be used to provide new types of graphs.
With this API, it would be possible to build a graph viewer interface that
displays an annotated version of the running graph the same way the Streams
console does.

### Monitoring subscription changes

The subscription thread implements the UDP notification and periodic polling for
`Export` operator subscripion changes mentioned in the
[Import/Export](Import_Export.md) section. Since K8S is the broker between the
PE and any controllers that need to communicate with the PE, K8S must implement
this policy.

The thread loops over a `poll()` on a UDP socket. When the thread receives a
packet, or when enough time has passed and the `poll()` times out, it uses
`curl` to fetch the job subscription board for this specific PE. The thread then
compares the newly fetched job board against the last seen job board, and calls
`PEImpl::routingInfoNotificationHandler()` as appropriate to add, delete or
update any entries.

### Monitoring consistency changes

The consistent region thread implements the UDP notification and periodic
polling for consistent region changes mentioned in the [Consistent
Region](Consistent_Region.md) section. Since K8S is the broker between the
PE and any controllers that need to communicate with the PE, K8S must implement
this policy.
