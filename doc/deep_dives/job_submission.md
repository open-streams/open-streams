# Job Submission

This document traces through what happens when a user submits a job.

## Job YAML

Since jobs are Kubernetes CRDs, users submit jobs by asking Kubernetes to create 
new job CRDs. We will use the following sample application:

> [`streams.spl.apps/apps.parallel`](https://github.ibm.com/SPL/streams.spl.apps/tree/master/apps.parallel)

Note that this is in a separate repo from the Knative Streams code; it's a small
repo with sample applications to demo various capabilities as we develop them.
What's particularly relevant for us is the YAML file in that directory. We
submit this job through the command:

## Job Spec

The format of the YAML that users write to deploy a job is determined by the
`JobSpec`:

> [`com.ibm.streams.controller.crds.jobs.JobSpec`](https://github.com/IBMStreams/OSStreams/blob/main/src/java/platform/com.ibm.streams.controller/src/com/ibm/streams/controller/crds/jobs/JobSpec.java)

The actual CRD is defined as the `Job` class:

> [`com.ibm.streams.controller.crds.jobs.Job`](https://github.com/IBMStreams/OSStreams/blob/main/src/java/platform/com.ibm.streams.controller/src/com/ibm/streams/controller/crds/jobs/Job.java)

As a rule, all CRDs have a corresponding spec, which means that all other CRDs
in our implementation follow the same pattern. (This pattern is not actually
part of the [`CustomResoure` interface][kube_custom_resource], but it is a widely adopted pattern
throughout the Kubernetes ecosystem.)

## Job Submission

Users submit the job by *applying* the particular job YAML to their Kubernetes
cluster:
```
kubectl apply -f apps.parallel/parallel.yaml
```
This command asks Kubernetes to create a new job CRD with the listed
specification. Kubernetes does so, places that new job CRD in the job store, and
then hands off control to the job controller.

## Job Creation

The part of the job controller that responds to newly created jobs is the method
`JobController.onAddition()`:

> [`com.ibm.streams.controller.crds.jobs.JobController`](https://github.com/IBMStreams/OSStreams/blob/main/src/java/platform/com.ibm.streams.controller/src/com/ibm/streams/controller/crds/jobs/JobController.java#L370)

Some notable steps during this process:

1. [Compare the timestamp of the event](https://github.com/IBMStreams/OSStreams/blob/main/src/java/platform/com.ibm.streams.controller/src/com/ibm/streams/controller/crds/jobs/JobController.java#L389) to the timestamp for when this
  `JobController` was created. After recovering from a catestrophic failure,
  Kubernetes replays all events. How to handle this depends on the controller.
  In the case of jobs, we need to catch up with the current job ID, but we don't
  need to recreate any jobs; they will be restored in the job store.

2. [Retrieve the job's ADL from the SAB](https://github.com/IBMStreams/OSStreams/blob/main/src/java/platform/com.ibm.streams.controller/src/com/ibm/streams/controller/crds/jobs/JobController.java#L406). The user must have stored the SAB in the
   external repository first. At the moment, we are using Redis for this SAB
   store.

3. [Create the logical model and model version of the job](https://github.com/IBMStreams/OSStreams/blob/main/src/java/platform/com.ibm.streams.controller/src/com/ibm/streams/controller/crds/jobs/JobController.java#L416). We will dive into this
   process in the next section.

4. [Retrieve meta-information about the job](https://github.com/IBMStreams/OSStreams/blob/main/src/java/platform/com.ibm.streams.controller/src/com/ibm/streams/controller/crds/jobs/JobController.java#L437), such as whether it has any
   consistent regions, imports of exports and find all of the PE ports. Each one
   of these Streams concepts maps to either a Streams CRD or an existing
   Kubernetes construct.

5. [Create the Kubernetes resources](https://github.com/IBMStreams/OSStreams/blob/main/src/java/platform/com.ibm.streams.controller/src/com/ibm/streams/controller/crds/jobs/JobController.java#L451) for the job.

Each Streams concepts retrieved in step 4 and created in step 5 could be its own
deep-dive. But since this deep-dive is focused on job submission, we will go
into PE creation, as all jobs have at least one PE.

## Logical Model, Topology Model, Model Job

The classes in the `com.ibm.streams.controller.instance.sam` package are the
glue between the Kubernetes-aware code and the SAM code:

> [`com.ibm.streams.controller/src/com/ibm/streams/controller/instance/sam`](https://github.com/IBMStreams/OSStreams/blob/main/src/java/platform/com.ibm.streams.controller/src/com/ibm/streams/controller/instance/sam)

As most of the code that we need is related to the job-submission pipeline, the
primary class is called `Pipeline`:

> [`com.ibm.streams.controller.instance.sam.Pipeline`](https://github.com/IBMStreams/OSStreams/blob/main/src/java/platform/com.ibm.streams.controller/src/com/ibm/streams/controller/instance/sam/Pipeline.java)

This class is where the Kubernetes controllers ask the SAM code to create new
Streams entities (such as `LogicalModel`s), or to make queries about those
entities (such as if the job has any exports). At the moment, we're concerned
with two processes:

1. [Create the `LogicalModel` given an ADL](https://github.com/IBMStreams/OSStreams/blob/main/src/java/platform/com.ibm.streams.controller/src/com/ibm/streams/controller/instance/sam/Pipeline.java#L68). Note that we create a new `ObjectTracker`
  on every call. Since we no longer rely on the `ObjectTracker` to store objects 
  in ZooKeeper, this is safe for us to do.

2. [Create the model `Job` given a `LogicalModel`](https://github.com/IBMStreams/OSStreams/blob/main/src/java/platform/com.ibm.streams.controller/src/com/ibm/streams/controller/instance/sam/Pipeline.java#L144). This method generates a 
   `TopologyApplication` as a part of the process, and initiates fusion.

## Creating all of the PEs

The `JobController` is responsible for creating all of the PEs. It must first
create a [`ProcessingElementSpec`](https://github.com/IBMStreams/OSStreams/blob/main/src/java/platform/com.ibm.streams.controller/src/com/ibm/streams/controller/crds/pes/ProcessingElementSpec.java) for each PE, and then ask the PE factory to
actually create each individual PE:

1. [Create the PE spec](https://github.com/IBMStreams/OSStreams/blob/main/src/java/platform/com.ibm.streams.controller/src/com/ibm/streams/controller/crds/jobs/JobController.java#L304).

2. [Sort the PEs](https://github.com/IBMStreams/OSStreams/blob/main/src/java/platform/com.ibm.streams.controller/src/com/ibm/streams/controller/crds/jobs/JobController.java#L281) by most-restrictive placement spec first. When we eventually ask
   Kubernetes to schedule the pods behind these PEs, we want it to first handle 
   the pods with the most restrictive requirements.

3. [Create the PE CRD](https://github.com/IBMStreams/OSStreams/blob/main/src/java/platform/com.ibm.streams.controller/src/com/ibm/streams/controller/crds/jobs/JobController.java#L340) through the PE factory.

## Creating a single PE

The `ProcessingElementFactory` is responsible for creating an individual PE:

> [`com.ibm.streams.controllerl.crds.pes.ProcessingElementFactory`](https://github.com/IBMStreams/OSStreams/blob/main/src/java/platform/com.ibm.streams.controller/src/com/ibm/streams/controller/crds/pes/ProcessingElementFactory.java#L74)

There are three main steps:

1. [Establish an *owner reference*](https://github.com/IBMStreams/OSStreams/blob/main/src/java/platform/com.ibm.streams.controller/src/com/ibm/streams/controller/crds/pes/ProcessingElementFactory.java#L75). Because we establish that the `Job` CRD is the
   owner of this `ProcessingElement` CRD, when a user removes the job,
   Kubernetes automatically removes the PE as well.

2. [Create the *labels*](https://github.com/IBMStreams/OSStreams/blob/main/src/java/platform/com.ibm.streams.controller/src/com/ibm/streams/controller/crds/pes/ProcessingElementFactory.java#L93). These are the labels that will go on the PE's pod, visible
   to users.

3. [Instantiate the `ProcessingElement` object](https://github.com/IBMStreams/OSStreams/blob/main/src/java/platform/com.ibm.streams.controller/src/com/ibm/streams/controller/crds/pes/ProcessingElementFactory.java#L114) and hand it off to Kubernetes through
   the `createOrReplace()` method. The key difference between object factories 
   in the Kubernetes ecosystem and in the standard pattern is that here
   factories do not actually return the newly created CRD. Instead, they pass it
   to Kubernetes through the `createOrReplace()` method. That makes sure that
   the CRD is placed in the correct store, and the corresponding controller
   receives a notification. There is a parallel between `createOrReplace()` and
   `kubectl apply`: they are both ways to create new Kubernetes objects.

Soon after this point, the PE CRD will exist in the PE store, and Kubernetes
will notify the `ProcessingElementController` that there is a new PE.

## Creating a pod for a single PE

The method `ProcessingElementController.onAddition()` receives the event that a
new PE CRD was created:

> [`com.ibm.streams.controller.crds.pes.instance.ProcessingElementController`](https://github.com/IBMStreams/OSStreams/blob/main/src/java/platform/com.ibm.streams.controller/src/com/ibm/streams/controller/crds/pes/instance/ProcessingElementController.java#L91)

It is the PE controller that enforces that one PE becomes one pod. It creates a
spec for that pod, and then creates it through `PodFactory.addPod()`. Creating
the pod spec is involved enough that we created a separate class for it:

> [`com.ibm.streams.controller.crds.pes.instance.ProcessingElementPodSpecBuilder`](https://github.com/IBMStreams/OSStreams/blob/main/src/java/platform/com.ibm.streams.controller/src/com/ibm/streams/controller/crds/pes/instance/ProcessingElementPodSpecBuilder.java)

The `ProcessingElementPodSpecBuilder` class is responsible for mapping PE
concepts into pod concepts.

After this point, the pod is deployed to Kubernetes. However, we still have a
`PodController` which reacts to a PE's pod events.

## Pod deployment

When a PE's pod is first deployed, the PE `PodController` does not have much
work to do:

> [`com.ibm.streams.controller.k8s.pods.pes.PodController`](https://github.com/IBMStreams/OSStreams/blob/main/src/java/platform/com.ibm.streams.controller/src/com/ibm/streams/controller/k8s/pods/pes/PodController.java#L98)

The PE `PodController` has more work to do in the case of modification and deletion.

[kube_custom_resource]: https://static.javadoc.io/io.fabric8/kubernetes-client/4.2.2/io/fabric8/kubernetes/client/CustomResource.html
