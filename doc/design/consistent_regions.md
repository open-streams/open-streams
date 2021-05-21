# Consistent Regions

## Design

*Consistent Region Operator* is a Kubernetes Deployment, which is created on
demand by the Instance Operator. For the job that has consistent regions, a
dedicated consistent region operator deployment is created for it by the
instance operator. It is the Java class that instantiates all the relevant CRD
controllers and consistent region specific entities. All the CRD controllers in
a consistent region operator only listen to CRD events for the dedicated job.

### Consistent Region Operator

The consistent region operator has 8 main tasks:

1. Check if this is restart or fresh start.

2. Create the controllers for PE CRD, Pod CRD and CR(consistent region) CRD.

3. Create the HTTP server for notification and progress update exchanges between
consistent region operator and PEs.

4. Create the consistent region event queue: the producer of events are Pod
controoler, PE controller and REST requests from PEs.

5. Create the consistent region event consumer (consistent region FSM) which
runs as a finite state machine to process events in the event queue.

6. Start the controllers and the consistent region FSM. The constrollers and
consistent region FSM run in their own threads.

7. Wait for the shutdown signal.

8. Close the controllers and consistent region FSM.

### PE and POD Monitor

All the PEs involved in a consistent region defines a region. A region is
healthy when all the PEs in the region have *Full* connecticity and all the Pods
in the region are in the *running* phase. The controllers created for PE CRD and
Pod by the consistent region operator only monitor the PEs and Pods that are in
at least one consistent region associated with the dedicated job.

The PE CRD contains the indices of the constent region that the PE belongs to.

When there is *addition* or  *deletion* event for a PE CRD, or *modification*
event to the **connectivity** field of a PE CRD, the PE controller enqueues a
*RegionUpdate* event for each relevant region to the event queue.

Similarly, the POD controller reacts to the *addition* and *deletion* event for
a Pod and *modification* event to the Pod phase by enqueing a *RegionUpdate*
event for each relevant region. The Pod controller obtains the region
information of a Pod by querying the corresponding PE CRD. 

### Consistent Region Controller

Consistent region controller orchestrates the progress of the consistent region
FSM based on the events on ConsistentRegion CRD (CR CRD). It also orchestrate
the external connection such as notifications to PEs and metrics update for
consistent region operator.

CR CRD contains both the static and dynamic meta information of a consistent
region. The details about CR CRD can be found in [Custom Resources](#custom-resources).

At start up, if it is a fresh start, the consistent region controller notifies
the consistent region FSM to start processing events in the queue after all the
CR CRDs for the job have been created. If the consistent region operator has
been restarted either due to a failure or deletion from the user, the consistent
region controller update each CR CRD to reflect the restart. The consistent
region FSM starts processing events when all the CR CRDs for the same job have
been securely updated. 

When the consistent region FSM processes one event in the queue, it may update
the corresponding CR CRD. For example, the state of the CR CRD needs to be
updated due to the change of the Pod phase. However, the processing of the next
event for the same CR CRD depends on the updated state. Hence, in order to
ensure consistency, the consistent region FSM needs to wait for the CR CRD to be
updated before processing the next event. In this case, consistent region
controller acts as the orchestrater between the CR CRD update and the consistent
region FSM. The consistent region controller notifies the consistent region FSM
to resume processing when reacting to the modification event of the CR CRD. 

### Consistent Region Finite State Machine

There are three types of external requests for the consistent region FSM to
handle:

1. RegionUpdate: there is change to either PE connectivity or Pod phase. On
*RegionUpdate* event, the FSM queries all PEs' connectivities and Pods' phases
in the same region to update region health.

2. Progress: progress updates from either PEs or operators. The types of 
progress updates are: PE completion of checkpoint, PE completion of reset, 
drain triggered by operator and reset triggered by operator.

3. Timeout: the current drain or reset times out.

Reactions to external requests:

1. CR CRD update: the consistent region state can be transited to another state. 

2. Notifications to PE: different types of notifications maybe sent to PEs based
on the state transition. Three types of notifications can be expected:
*TriggerDrain*, *TriggerReset* and *ResumeSubmission*. 

3. Metric update: 8 metrics (average draining time, average reset time, last
consistent time, last reset time, last drain sequence id, last reset sequence
id, consistent region state, last completed id) are updated through prometheus.

## Kubernetes Integration

### Custom Resources

#### Consistent Region CRD.

One consistent region CRD is created per region. It contains both the static and
dynamic information of a region.

##### Static information 
```
Integer regionIndex;
Integer numStartOperators;
String trigger;
double period;
double drainTimeout;
double resetTimeout;
long maxConsecutiveResetAttempts;
Set<BigInteger> pesInRegion;
Map<String, Boolean> operatorsToStartRegionMap;
Map<String, Boolean> operatorsToTriggerMap;
```
##### Dynamic information
```
EState state;
Map<BigInteger, EPeStatus> peToCompletion;
boolean isRegionHealthy;
boolean isCleanStart;
boolean isHealthyFirstTime;
boolean isMustReset;
long currentResetAttempt;
long currentMaxResetAttempts;
long currentSeqID;
long lastCompletedSeqID;
long pendingSeqID;
long toRetireSeqID;
```
#### Consistent Region Operator CRD.

During job submission, if there is any consistent region in the job, a
consistent region operator CRD will be created by the job controller. The
deployment for the consistent region operator is created upon the addition event
of the consistent region operator CRD. The consistent region operator CRD
defines the job name, the number of consistent regions operators as well as the
restart information. 

When the consistent region operator starts running, it first grabs the
corresponding consistent region operator CRD to learn the restart information.
If the consistent region operator has been restarted, all the consistent regions
in the job are forced to restart to ensure consistency.
```
String jobName;     // all the controller in consistent region operator should only listent to events of this job
Integer numRegions; // number of consistent regions
boolean hasStarted; //if it is fresh start or restart
```
### Notifications

There are two possible solutions to handle communication between the consistent
region operator and PEs:

1. Exporting a REST API on both the PE and the consistent region operator allows
both notification and progress update to happen.

2. Exporting a REST API on the consistent region operator only and using loosely 
coupled UDP notifications as well as periodic polling to synchornize the PEs.

We adopted option 2 in our design since it eliminates the connection management
for the consistent region operator to send synchronous REST calls to PEs. Based
on the latency evaluation of the second design, we will examine if it is
necessary to explore the first option.

### REST API
<table>
  <tr>
    <th>GET</th>
    <th>PUT</th>
</tr>
<tr>
  <td colspan=2><code>/pe/{peid}</code></td>
</tr>
<tr>
  <td>Fetch all the notifications for a PE.</td>
  <td/>
</tr>
<tr>
  <td colspan=2><code>/pe/{peid}/region/{regionId}</code></td>
</tr>
<tr>
  <td>Fetch all the notifications of a region for a PE.</td>
  <td/>
</tr>
<tr>
  <td colspan=2><code>/region/{regionId}/pe/{peId}/checkpoint</code></td>
</tr>
<tr>
  <td/>
  <td>PE checkpoint completion progress update.</td>
</tr>
<tr>
  <td colspan=2><code>/region/{regionId}/pe/{peId}/blockingcheckpoint</code></td>
</tr>
<tr>
  <td/>
  <td>PE blocking checkpoint completion progress update.</td>
</tr>
<tr>
  <td colspan=2><code>/region/{regionId}/pe/{peId}/reset</code></td>
</tr>
<tr>
  <td/>
  <td>PE reset completion progress update.</td>
</tr>
<tr>
  <td colspan=2><code>/region/{regionId}/drain</code></td>
</tr>
<tr>
  <td/>
  <td>Drain triggered by an operator</td>
</tr>
<tr>
  <td colspan=2><code>/region/{regionId}/reset</code></td>
</tr>
<tr>
  <td/>
  <td>Reset triggered by an operator</td>
</tr>
</table>
