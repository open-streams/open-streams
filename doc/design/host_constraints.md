# Host Constraints

Streams applications can have *host constraints* which specify which hosts an
operator can execute on. Host constraints are considered during fusion, so we
are guaranteed that a PE will have sensical host constraints (that is, operators
with mutually exclusive host constraints are not fused together). For our
purposes, we will talk about a PE's host constraints, which are really the union
of all of the host constraints of the operators it contains. When we refer to "a
PE's host constraints," we really mean "the union of the host constraints of all
of the operators inside of the PE."

We will take advantage of two beta features in Kubernetes to implement Streams
host constraints. The [`nodeAffinity`][kube_affinity] feature introduce in
Kubernetes 1.2 allows pods to specify which nodes they can run on based on node
labels. It will replace the `nodeSelector` option. We will use `nodeAffinity` to
implement the Streams `host()` placement config. The [`podAffinity` and `antiAffinity`][kube_affinity]
feature introduced in Kubernetes 1.4 allows pods to specify which nodes a pod
should run on based on pod labels on those nodes. We will use `podAffinity` to
implement the Streams `hostColocation()` config, and `podAntiAffinity` to
implement `hostExlocation()`.

One common feature of all Streams host placement semantics is that *host
resolution failure results in job submission failure*. That is, the semantics of
Streams has always been that if the current instance cannot meet the host
requirements of a submitted job, that job submission fails with an appropriate
error message. For now, we are making the design decision to reproduce that
behavior.

And important design decision made elsewhere is that we ensure *one PE is
deployed as one pod*. That design decision simplifies how we handle host
constraints.

Note that [`partitionColocation()`][streams_partcoloc], [`partitionExlocation()`][streams_partexloc] and [`partitionIsolation`][streams_partiso]
are handled exactly as before as they are fundamental to the fusion process,
which we are using unchanged.

## Specific Host Placement: `host()` 

The [`host()`][streams_host] config specifies a particular host that the PE must run on. There are
four variants of it, each of which we will handle differently.

### Host name: `host("foo.watson.ibm.com")`

Specifying a host name maps exactly to the [`nodeName`][kube_nodename] node selection constraint.
Note that this option does not use labels, and requires that the Kubernetes node
has exactly the specified name.

### IP address: `host("10.4.40.83")`

Kubernetes does not support assigning pods to nodes based on IP address. But, it
does publish the IP address of each node in its description. That means that we
can do the mapping of IP address to a node name, and then assign the the PE's
pod using `nodeName` just as above.

> *Resolved Questions:*
>
> 1. Given that IP addresses are strings, and names are strings, how will we determine 
>    when an IP address is used?
>
>    *Resolution*: If an operator has a `host()` config, and that config is a valid 
>    IPv4 or IPv6 address according to the Apache Commons 
>    [`InetAddressValidator`][inet_validator], then we will treat it as an IP address. 
>    If no node in the Kubernetes cluster has that IP address, the job submission will 
>    not succeed.

### Detour: Hostpools

SPL has the notion of a [`hostPool`][streams_hostpool], which can be created on the main composite of
an application. They have no runtime mechanism; there is no part of a running
system that *is* the hostpool. Rather, they are an abstraction for describing
where PEs can execute. 

We will define a Kubernetes Custom Resource Description (CRD), `HostPool`, to
represent them. The corresponding controller will be responsible for taking
hostpool-related actions, and other components (such as as the PE controller)
will look up hostpools in the store. Upon job submission, we retrieve all of the
hostpool information from the job's model. We use this information to create new
`HostPool` entries which go into the store and are handled by the controller.
PE's have references to these hostpools, and we look them up in the store when
we create the pod spec for those PEs.

Initially, we thought we would not need to define a CRD for hostpools. But, once
it became clear that we needed a persistent notion of a hostpool that could be
referenced by both jobs and PEs, it became clear that a CRD is the natural
solution.

In SPL, users can create hostpool literals:
```spl
config hostPool: NamesPool = ["foo", "bar"];
```
Similar to the `host("name")` config, we can support the above by ensuring that
the current cluster has nodes with the contained names. One wrinkle is that I do
not believe the `nodeName` pod spec allows specifying a set of node names, but
allows only a single node name. If so, then we would have to create pod specs
which arbitrarily choose one of the allowable names.

> *Open Questions:*
>
> 1. Should we not care about a node's name at all, and just consider names the 
>    same as labels?

SPL users can also create hostpool literals using IP addresses:
```spl
config hostPool: IPsPool = ["10.8.5.6", "10.8.5.7", "10.8.5.8"];
```
Similar to how we handle `host("10.8.5.6")`, we will map the IP addresses to the
names for those nodes, and then the hostpool will be the same as if it was
specified with names.

Hostpool literals are not the common case. More prevalents are hostpools created
through the `createPool()` intrinsic which allows users to specify a number of
hosts, tags and exclusivity. From the documentation:
```spl
config hostPool: 
  P1=createPool({size=10u, tags=["ib","blue"]}, Sys.Shared), //sized, tagged, shared
  P2=createPool({size=10u, tags=["ib","blue"]}, Sys.Exclusive), //sized, tagged, exclusive
  P4=createPool({size=10u}, Sys.Shared), //sized, untagged, shared
  P5=createPool({size=10u}, Sys.Exclusive), //sized, untagged, exclusive
  P3=createPool({tags=["ib","blue"]}, Sys.Shared), //unsized, tagged, shared
  P6=createPool(Sys.Shared); //unsized, untagged, shared
```
All of the above should be legal constructs in the Kubernetes realization of
hostpools:

* `size`: If provided, we need to ensure that we have access to enough nodes
that meet the `tags` and exclusivity requirements.
* `tags`: Each tag maps directly to a Kubernetes label.
* exclusivity: No action needed for `Sys.Shared`. For `Sys.Exclusive`, we need to
ensure that no other Streams PEs are started on the hosts we give to the PEs in
this job. We should be able to implement this with Kubernetes [Taints][kube_taints]. We will
apply taints to the nodes chosen to be exclusive, and then make sure the PEs
assigned to those nodes have the appropriate tolerations.

All of the above means that in the Kubernetes world, hostpools exist as labels
and taints applied to nodes, and labels and tolerations passed down to PEs.
Inability to create any of the hostpools that a job contains will result in job
submission failure.

> *Open Questions:*
> 
> 1. Need to do more investigation to ensure taints can work for our purposes.
> 2. Should exclusivity apply to *all* pods, not just pods that contain Streams PEs? This 
     may actually be easier to implement given the semantics of taints and tolerations.
> 3. Should we just ignore size and exclusivity completely? Tags may be the only valuable 
>    concept in a Kubernetes environment.
> 4. Applying tags and taints to nodes is a new kind of action. We have not yet made node 
>    changes. I am not sure who the appropriate actor is (do we need a node controller?). 

### Any host from a hostpool: `host(MyPool)`

PEs that belong to a particular hostpool will inherit that hostpool's tags as
Kubernetes labels. The PE controller will add those labels to the pod's spec,
and we will use `nodeAffinity` to ensure that the pod for those PEs are only
created on nodes with those labels. Kubernetes labels are key-value pairs, so
for hostpools, we will always use the key `streams.ibm.com/hostpool`.

The `nodeAffinity` feature provides two options:
`requiredDuringSchedulingIgnoredDuringExecution` and
`preferredDuringSchedulingIgnoredDuringExecution`. As implied by the names, the
first is a requirement and the second is only a preference. The names also imply
that if the node labels change at runtime, then Kubernetes will not move the
pods elsewhere. We established earlier that we want to replicate the current
Streams semantics, which means that we will use the required option. However, we
can easily imagine providing a Kubernetes-specific toggle that lets users map
`hostColocation()` requests to a preference instead of a requirement. Not moving
pods based on node changes during execution is also consistent with Streams
semantics. (Future releases are supposed to support an additional option,
`requiredDuringSchedulingRequiredDuringExecution`. Again, we could provide a 
Kubernetes-specifc toggle to allow it.)

Note that of the available ways of specifying `host()`, specifying a particular
hostpool is the most Kubernetes-like. And of the ways of creating a hostpool,
the most Kubernetes-like is creating one that is unsized, tagged and shared.
These uses mesh with Kubernetes easily because then the PEs adopt certain
labels, and we specify those labels in the pods we deploy. Assuming that the
nodes already have the appropriate labels, Kubernetes handles the rest.

We propose that the following SPL:
```spl
composite Main {
graph
    stream<uint64 num> Source = Beacon() {
        config placement: host(MyPool);
    }
    config hostPool: MyPool = createPool({tags=["source"]}, Sys.Shared);
}
```
Will map to the following pod spec (with many values elided):

```yaml
apiVersion: v1
kind: Pod
metadata:
  labels:
    app: streams
    streams.ibm.com/job: parallel
    streams.ibm.com/pe: parallel-0
  name: parallel-0-ioyms
  namespace: default
spec:
  affinity:
    nodeAffinity:
      requiredDuringSchedulingIgnoredDuringExecution:
        nodeSelectorTerms:
        - matchExpressions:
          - key: streams.ibm.com/hostpool
            operator: In
            values:
              - source
  containers:
  - args:
    - /opt/ibm/streams/system/impl/bin/streams-k8s-bootstrap
    image: us.icr.io/spl_team/streams-runtime:6.release
```
The above assumes that someone already labeled some node in the cluster with the
label `streams.ibm.com/hostpool=source`.

If the hostpool is exclusive, then the PE controller must apply the appropriate
tolerations to the pod to ensure they can run on the nodes. *(No example yet.)*

### Particular Host from a Hostpool: `host(MyPool[5])`

Specifying a particular host in a hostpool is not very Kubernetes-like, but it
is better than specifying a name because we still have the freedom to decide
which actual Kubernetes node should be that host. That users can index hostpools
implies that we also need to maintain some ordering on the nodes that implement
the hostpool.

> *Open Questions:*
> 
> 1. How wo we implement the mapping? We have two options:
>     1. Make and assign node names. (Probably not the best option.)
>     2. Create a special label just for the chosen node, give that label
>        to the PE. Probably better than option i, but it has the wrinkle that 
>        we need to synthesize unique node labels that don't clash with user-created
>        node labels.

## PEs Must Run Together: `hostColocation("token")`

The SPL construct [`hostColocation`][streams_coloc] is not about assigning a PE
to a particular host, but making sure that a PE is co-located on the same host
as some other PE. That is, the host itself is not important but what else is
running on that host is. The semantics are straight-forward: all PEs with exactly 
matching values in their call to `hostColocation()` must execute on the same 
host.

This concept neatly maps to the Kubernetes [`podAffinity`][kube_affinity] feature. Its options
are the same as with `nodeAffinity`, and again we choose the required options.
The pod affinity options introduce another concept, that of a `topologyKey`. It
is meant to ditinguish "topology domains", where a domain could be something
like a node, rack or zone. See the documentation for more.  When using
`podAffinity`, `topologyKey` must have some value; it cannot be empty.

Similar to `nodeAffinity`, hosts are specified using a key-value pair, which is
unlike `hostColocation()`. Because a pod may have multiple colocation
requirements, we must use a unique key for each requirement. The key will have
the prefix `streams.ibm.com/hostcolocation.` and the rest will be the SHA-1 hash
of the string provided to `hostColocation()`. We use a hash of the value because
we need to ensure that the key name is 63 characters or less (see the
[Kubernetes documentation on labels][kube_labels] for more discussion). The
value must also be 63 characters or less. We considered using the SHA-1 hash of
the original stringe as the value as well, but we wanted the label to retain
identity with what appears in the applicaiton. This does introduce an
incompatability with Streams 4.3: `hostColocation()` can accept *any* SPL
string, including strings with spaces. Such values are not valid Kubernetes
label values. Developers will have to modify such applications.

We propose that the following SPL:
```spl
stream<Type> Out = Functor(In) {
    config placement: hostColocation("together");
}
```
Will map to the following pod spec (assuming that the PE which contains the
above operator has no additional host constraints):
```yaml
apiVersion: v1
kind: Pod
metadata:
  labels:
    app: streams
    streams.ibm.com/job: parallel
    streams.ibm.com/pe: parallel-0
    streams.ibm.com/hostcolocation.9034FF9E2B8F00B47A44DFAF3C2A37176C101E2A: together
  name: parallel-0-ioyms
  namespace: default
spec:
  affinity:
    podAffinity:
      requiredDuringSchedulingIgnoredDuringExecution:
      - labelSelector:
          matchExpressions:
            key: streams.ibm.com/hostcolocation.9034FF9E2B8F00B47A44DFAF3C2A37176C101E2A
            operator: In
            values:
            - together
        topologyKey: kubernetes.io/hostname
  containers:
  - args:
    - /opt/ibm/streams/system/impl/bin/streams-k8s-bootstrap
    image: us.icr.io/spl_team/streams-runtime:6.release
```
Note that not only do we need to add the appropriate value (`together`) to our
`podAffinity` spec, we also need to add the appropriate label to this pod
(`streams.ibm.com/hostcolocation.9034FF9E2B8F00B47A44DFAF3C2A37176C101E2A=together`) to ensure that *other* pods with the
same `podAffinity` spec will match with us.

> *Open Questions*
>
> 1. The additional concept of a `topologyKey` is still unclear to me. I know the rough meaning,
>    but I can't describe it well.
> 2. Performance impact. The documentation also warns that these features can take a long time
>    to process, which gels with our own understanding of the problem. They warn against using them 
>    with clusters with more than several hundred nodes.

## PEs Must Not Run Together: `hostExlocation("token")`

The SPL construct [`hostExlocation`][streams_exloc] is the inverse of `hostColocation()`: it is
for ensuring that PEs with matching token values are placed on *different*
hosts. All PEs with `hostExlocation("apart")` must end up on different hosts.

Just as `hostExlocation()` is the inverse of `hostColocation()`, the Kubernetes
option `podAntiAffinity` is the inverse of `podAffinity`. Our use of
`hostExlocation()` maps to `podAntiAffinity` exactly. Once again, we will use
the `required` as opposed to `preffered` variant. The key we will use for the 
labels is `streams.ibm.com/hostexlocation`.

When using the combination of `podAntiAffinity` and
`requiredDuringSchedulingIgnoredDuringExecution`, we are restricted to the value
`kubernetes.io/hostname` for `topologyKey` by an admission controller. We can
disable this admission controller, but I don't see a need to.

We propose that the following SPL:
```spl
stream<Type> Out = Functor(In) {
    config placement: hostExlocation("apart");
}
```
Will map to the following pod spec (assuming that the PE which contains the
above operator has no additional host constraints):

```yaml
apiVersion: v1
kind: Pod
metadata:
  labels:
    app: streams
    streams.ibm.com/job: parallel
    streams.ibm.com/pe: parallel-0
    streams.ibm.com/hostexlocation.B76ADE163D874CC5BC0F408D70CFC165667EEC5F: apart
  name: parallel-0-ioyms
  namespace: default
spec:
  affinity:
    podAntiAffinity:
      requiredDuringSchedulingIgnoredDuringExecution:
      - labelSelector:
          matchExpressions:
            key: streams.ibm.com/hostexlocation.B76ADE163D874CC5BC0F408D70CFC165667EEC5F
            operator: In
            values:
            - apart
        topologyKey: kubernetes.io/hostname
  containers:
  - args:
    - /opt/ibm/streams/system/impl/bin/streams-k8s-bootstrap
    image: us.icr.io/spl_team/streams-runtime:6.release
```
Again note that we also need to add the label
`streams.ibm.com/hostexlocation.B76ADE163D874CC5BC0F408D70CFC165667EEC5F=apart` to this pod as well as use it in our
`podAntiAffinity` spec.

> *Open Questions*
>
> 1. Once again, `topologyKey`. We need to more fully understand the implications of 
>    these restrictions.

## PE Must Run Alone: `hostIsolation`

The SPL construct [`hostIsolation`][streams_iso] assures that the PE runs on a host which
contains no other PEs from the same job. It is semantically equivalent to
creating a `hostExlocation("token-X")` config on an operator for every other
operator in the application, where `"token-X"` would become `"token-0"` for the
first operator pairing, `"token-1"` for the second operator pairing, and so on.

We will use a similar notion to implement `hostIsolation` in Kubernetes, but
luckily we don't need to be that extreme. The `hostExlocation()` constraint in
SPL is symmetric and transitive. It is symmetric because in order for operator
`A` to be exlocated from operator `B`, both must explicitly state they are
exlocated from each other using the same token. It is transitive since if
operator `A` is exlocated from operator `B` using token `foo`, and operator `A`
is exlocated from operator `C` also using token `foo`, operator `B` and `C` will
also be exlocated from each other. This is why in order to recreate
`hostIsolation` constraints with `hostExlocation()`, we must generate unique
tokens for each pairing. (Note that this is an illustrative exercise to
understand the logical consequences of these mechanisms, not reccomendations.)

The `podAntiAffinity` feature is not symmetric: pod `A` can specify that it is
anti-affinity to pod `B`, but pod `B` does not need to specify it is
anti-affinity to pod `A`. Because the relationship is not symmetric, we avoid
the transitivity. We can take advantage of this fact, and of the semantic
equivalence of `hostIsolation` and careful `hostExlocation()` pairings, to
ensure host isolation through creating just one new label.

Specifically, for each instance of `hostIsolation` in a job, we must create a
unique label with the key-base `streams.ibm.com/hostisolation`. The remainder of
the key will be the SHA-1 hash of the operator name with the `hostIsolation`
config. The value of the label will be a sanitized version of same operator name
(the sanitization must enforce the [Kubernetes label value restrictions][kube_labels]). Then we
must apply that label to each pod in the job, excluding the pod which we want to
isolate.  Then we specify a `podAntiAffinity` on the pod we want to isolate
using that unique label.

Assume the following SPL application:
```spl
composite Main {
graph
    stream<uint64 num> Source = Beacon() {
        config placement: partitionIsolation;
    }

    stream<uint64 num> Work = Functor(Source) {
        config placement: partitionIsolation;
    }

    () as Sink = Custom(Double) {
        config placement: partitionIsolation,
                          hostIsolation;
    }
}
```
Because of the `partitionIsolation` configs on each operator, there will be three
PEs. Let's name these PEs after the only operators they contain: `Source`,
`Work` and `Sink`. Because `Sink` has `hostIsolation`, it must run on a node by itself
(although we don't care which).

The `Source` pod spec:
```yaml
apiVersion: v1
kind: Pod
metadata:
  labels:
    app: streams
    streams.ibm.com/job: ex
    streams.ibm.com/pe: parallel-0
    streams.ibm.com/hostisolation.E53E8D5300C878019A997D4CFB7201C7ED2EE003: Sink
  name: parallel-0-fjcq
  namespace: default
spec:
  containers:
  - args:
    - /opt/ibm/streams/system/impl/bin/streams-k8s-bootstrap
    image: us.icr.io/spl_team/streams-runtime:6.release
```
The `Work` pod spec:
```yaml
apiVersion: v1
kind: Pod
metadata:
  labels:
    app: streams
    streams.ibm.com/job: ex
    streams.ibm.com/pe: parallel-1
    streams.ibm.com/hostisolation.E53E8D5300C878019A997D4CFB7201C7ED2EE003: Sink
  name: parallel-1-fdpf
  namespace: default
spec:
  containers:
  - args:
    - /opt/ibm/streams/system/impl/bin/streams-k8s-bootstrap
    image: us.icr.io/spl_team/streams-runtime:6.release
```
Note that aside from their PE names, the pod specs are the same. Also note that
they have the label `streams.ibm.com/hostisolation.E53E8D5300C878019A997D4CFB7201C7ED2EE003=Sink`.

The `Sink` pod spec:
```yaml
apiVersion: v1
kind: Pod
metadata:
  labels:
    app: streams
    streams.ibm.com/job: ex
    streams.ibm.com/pe: parallel-2
  name: parallel-2-xnvy
  namespace: default
spec:
  affinity:
    podAntiAffinity:
      requiredDuringSchedulingIgnoredDuringExecution:
      - labelSelector:
          matchExpressions:
            key: streams.ibm.com/hostisolation.E53E8D5300C878019A997D4CFB7201C7ED2EE003
            operator: In
            values:
            - Sink
        topologyKey: kubernetes.io/hostname
  containers:
  - args:
    - /opt/ibm/streams/system/impl/bin/streams-k8s-bootstrap
    image: us.icr.io/spl_team/streams-runtime:6.el7.x86
```
The `podAntiAffinity` spec in `Sink` ensures that it will not end up on a node
that contains either `Source` or `Work` because the pods containing those PEs
have the label `streams.ibm.com/hostisolation.E53E8D5300C878019A997D4CFB7201C7ED2EE003=Sink`.

[streams_host]: https://www.ibm.com/support/knowledgecenter/SSCRJU_4.3.0/com.ibm.streams.ref.doc/doc/placementhost.html
[streams_hostpool]: https://www.ibm.com/support/knowledgecenter/SSCRJU_4.3.0/com.ibm.streams.ref.doc/doc/hostpool.html
[streams_coloc]: https://www.ibm.com/support/knowledgecenter/SSCRJU_4.3.0/com.ibm.streams.ref.doc/doc/placementhostcolocation.html
[streams_exloc]: https://www.ibm.com/support/knowledgecenter/SSCRJU_4.3.0/com.ibm.streams.ref.doc/doc/placementhostcolocation.html
[streams_iso]: https://www.ibm.com/support/knowledgecenter/SSCRJU_4.3.0/com.ibm.streams.ref.doc/doc/placementhostisolation.html
[streams_partcoloc]: https://www.ibm.com/support/knowledgecenter/SSCRJU_4.3.0/com.ibm.streams.ref.doc/doc/placementpartitioncolocation.html
[streams_partexloc]: https://www.ibm.com/support/knowledgecenter/SSCRJU_4.3.0/com.ibm.streams.ref.doc/doc/placementpartitionexlocation.html
[streams_partiso]: https://www.ibm.com/support/knowledgecenter/SSCRJU_4.3.0/com.ibm.streams.ref.doc/doc/placementpartitionisolation.html
[kube_affinity]: https://kubernetes.io/docs/concepts/configuration/assign-pod-node/#affinity-and-anti-affinity
[kube_labels]: https://kubernetes.io/docs/concepts/overview/working-with-objects/labels/#syntax-and-character-set
[kube_nodename]: https://kubernetes.io/docs/concepts/configuration/assign-pod-node/#nodename
[kube_taints]: https://kubernetes.io/docs/concepts/configuration/taint-and-toleration/
[inet_validator]: https://commons.apache.org/proper/commons-validator/apidocs/org/apache/commons/validator/routines/InetAddressValidator.html
