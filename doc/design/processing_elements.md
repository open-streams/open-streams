# Processing Elements

## TCP receivers

TCP receivers statically allocate their port using the port ID and an arbitrary
base port number (`10000` at the moment).

## Name resolution

PE name resolution is done through either the `NAM` namespace interface. This
interface supports operation to register, update and query name translations.
Originally, Streams supports two driver: a filesystem-backed driver and a
ZooKeeper-backed driver.

### Architecture

Streams uses two levels of name translation when distributing an application.
An operator port level of translation and a PE port level of translation. The
operator port translation resolves an operator port into a PE port. The PE
port translation resolves a PE port into an `IP:PORT` combo.

Operator ports and PE ports are identified using labels. An operator port label
is made of three parts `X.Y.Z`, where `X` represents the job identifier, `Y` the
operator index and `Z` the operator port index. A PE port label is made of two
parts `X.Y`, where `X` represents the PE identifier and `Y` the port identifier.

This extra level of indirection is required to ensure maximum flexibility when
PEs are changed, either as a result of a voluntrary or unvoluntary disruption
action. In other words, operator port labels are **stable** across the lifespan
of an application while PE port labels **may change**.

### Adapting to Kubernetes

#### Operator ports

Resolving operator ports into PE ports is a purely Streams concept. There is not
immediately available name resolution systems in Kubernetes to support this.

We decided to store the operator port labels translation in a per-job
`ConfigMap` and mount it inside all of the PE containers as a file. This file is
parsed for each lookup request in the namespace driver.

When the `ConfigMap` is updated by the controller, changes are immediately
visible to all containers. The runtime is then able to grab up-to-date values
when made available.

#### PE ports

Resolving PE ports into `IP:PORT` leverages the Kubernetes service DNS subsystem.
To that end, the following steps are taken:

1. Since each pod only uses a single PE, `TCPReceiver` port assignement is
   monotonic and decidable based on the PE input port index
2. When allocating PEs, the Streams controller also allocate Kubernetes Services
   pointing to the appropriate set of PEs, thereby creating service name entries
   in the Kube DNS system of the form `JOBNAME-ID.NAMESPACE.svc.cluster.local`
3. Upon resolving a PE port of the form `PE-ID.PORT-ID`:
    1. `PE-ID` is expanded into `JOBNAME-ID.NAMESPACE.svc.cluster.local` and
       resolved using `getaddrinfo()`
    2. `PORT-ID` is used to compute the final port number

For instance, the PE port label `1.1` for a PE running in the `default`
namespace is first converted into `pe-1.default.svc.cluster.local`, then
translated into its IP address `AAA.BBB.CCC.DDD`, and finally the pair
`AAA.BBB.CCC.DDD:10001` is returned as a result.

## Embedded JVM

By default the JVM embedded in the runtime overwrite all signal preferences
[with its own handlers](https://www.ibm.com/support/knowledgecenter/en/SSYKE2_7.0.0/com.ibm.java.lnx.70.doc/user/signals.html).  As a consequence, any signal sent to the PE process
is caught by the JVM. This default behavior conflicts with the lifecycle
management system used by Kubernetes, which consist of sending `SIGTERM` and
`SIGKILL` signals to `PID 1` of pods' containers.

We never historically encountered this issue as signals were virtually never
used anywhere in our runtime. Now, to prevent any unwanted termination and
preserve the proper shutdown sequence of our PEs, we have isolated the embedded
JVM from external signals by using the `-Xrs` flag upon invocation.
