# Failure Handling

Failures can happen to the instance operator at any time and we need to handle
them, especially when the job controller is in the middle of processing job
submission.  During the job submission pipeline, various types of Kubernetes
resources are created asynchronously, such as Import CRD, Export CRD, Hostpool
CRD, ProcessingElement CRD, Pod, etc. Because the instance operator is a
Kubernetes deployment, its life cycle is managed by Kubernetes: a new pod will
be recreated if it fails.  Whatever Kubernetes resources that were created
before will be notified to the restarted instance operator. An important
responsibility of the instance operator is to identify the jobs that have not
been successfully submitted before due to failures, and complete the submission
for those jobs (complete the creation of all its dependent Kubernetes
resources).

In order to identify those *aborted* job submissions, a Job FSM is run inside
the instance operator, which reacts to the notifications regarding the creations
of all the Job dependent resources. In the Job CRD spec, a field named "phase"
is used to indicate the job submission state. When all the dependent resources
of a Job are created, the Job phase is transited from "Submission" to
"Submitted". Hence, when the job controller reacts to the addition event of a
Job CRD that was created before, the job controller first checks its phase. If
the job phase has not been transited to "Submitted", the job controller will
retry to complete the submission.

The challenge becomes how to clean up the Job dependent resources created in the
previous submission: do we only create the remaining resources that were left
from the previous submission? Or do we delete all the old resources and start a
fresh job submission? Think of the scenario that a job needs to create 5
ProcessingElements (PEs), but only three Pods were created in the previous
submission just before the failure happened. When the instance operator comes
back again, those three Pods can be in any nondeterministic state, and as a
result the corresponding PE CRD maybe modifided to reflect the changes in
connectivity or restart count. To complete the job submission, those three stale
Pods need to be deleted.

The first approach is to delete the Pods created in the previous submission and
re-create all other resources. However, it requires complex bookkeeping to
manipulate states:

1. Delete pods that were created prior to the restart. However, in order to
handle voluntary deletion of pods from users, the Pod controller will re-create
the deleted pod if the corresponding job still exists. Hence, special label
needs to be given to those pods to bypass the re-creation. A natural follow-up
question is that given we need to re-create the Pods anyway, why bypassing the
re-creation here? The reason is that creation of Pod requires the configmap that
stores the aadl to be updated. Pod created at this stage may just grab the stale
aadl from the configmap.

2. Create Pod using the right version of ProcessingElement CRD. The
ProcessingElement controller takes the responsibility to create Pod when
reacting to the addition or modification event of the ProcessingElement (PE)
CRDs. For failure handling, we need to differentiate if the PE CRDs are created
before the restart or not.  PE CRD created before the restart may contain stale
states which should be discarded from creating Pods. Hence a special field in PE
CRD is needed to differentiate those scenarios.

The second approach is to delete the Job CRD whose submission has been
interrupted and re-create a new one to complete its submission. Given that the
resources that dependent on Job CRD have [ownerReferences](https://kubernetes.io/docs/concepts/workloads/controllers/garbage-collection/) correctly set up, the
deletion of the Job CRD triggers the deletion of all its dependents. 

There are two cascading [deletion options](https://kubernetes.io/docs/concepts/workloads/controllers/garbage-collection/) provided in Kubernetes: Foreground and
Background. In the background deletion, Kubernetes deletes the Job CRD
immediately and the garbage collector then deletes the dependents in the
background. Hence, the deletion of the old resources happens concurrently with
the addition of new resources, which may easily lead to risk condition if not
handled carefully. Background deletion is the only deletion option exposed
through kubectl and the Kubernetes java client.

In the foreground deletion, Kubernetes first sets the “deletion in progress”
state for Job CRD, the garbage collector deletes the dependents of Job CRD.
Once the garbage collector has deleted all “blocking” dependents, it deletes the
owner object. Foreground deletion option fits well with our needs. When the Job
controller receives the deletion event of Job CRD, all its dependents are
guaranteed to be deleted. At this point, all stale states have been cleaned up
and a new Job CRD is created to recover the submission from failure.

Through the trial and error, we realize foregound deletion of Job CRD followed
by recreation is the solution to handle failures during job submission. We also
realize what is the missing from the Kubernetes client (exposing foreground
deletion option) to contribute due to our extensive use of customresources and
the operator model.
