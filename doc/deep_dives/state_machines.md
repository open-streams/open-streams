# State Machines

The instance controller makes extensive use of state machines to ensure the
correctness of resource states transitions across supported operations. There
are two state machines: the Job state machine and the Pod state machine.

## General design

State machines are implemented as generic event listeners. Generic event
listeners are classes that can be registered with controllers to be forwarded
events those controllers receive. This allows the state machines to follow
events that arise from multiple sources and keep track of the related resources. 

As a result, a state machine is implemented as an `IEventConsumerDelegate` for
`HasMetadata` objects. It implements three methods: `onAddition`,
on`Modification` and `onDeletion` callbacks that will be called when the
corresponding events are received.

Within each of these functions, the resource are triaged according to their
actual type. If the event is relevant to the state machine, it is enqueued for
later processing by the main state machine thread. Upon reception of the event,
the main thread will further triage it and run through the state transitions of
the machine.

## Job state machine

The Job state machine is responsible for ensuring the proper execution of a Job
submission. It listens to all resources involved in the operation of a Job. The
state progression of the Job state machine is stored in the `JobProgress` class:
```java
    EJobState next() {
        switch (this.state) {
            case SUBMITTING:
                this.state = EJobState.WAIT_FOR_CONFIG_MAPS;
                break;
            case WAIT_FOR_CONFIG_MAPS:
                this.state = EJobState.WAIT_FOR_SERVICES;
                break;
            case WAIT_FOR_SERVICES:
                this.state = EJobState.WAIT_FOR_HOSTPOOLS;
                break;
            case WAIT_FOR_HOSTPOOLS:
                this.state = EJobState.WAIT_FOR_EXPORTS;
                break;
            case WAIT_FOR_EXPORTS:
                this.state = EJobState.WAIT_FOR_IMPORTS;
                break;
            case WAIT_FOR_IMPORTS:
                this.state = EJobState.WAIT_FOR_CONSISTENT_REGION_OPERATOR;
                break;
            case WAIT_FOR_CONSISTENT_REGION_OPERATOR:
                this.state = EJobState.WAIT_FOR_CONSISTENT_REGIONS;
                break;
            case WAIT_FOR_CONSISTENT_REGIONS:
                this.state = EJobState.WAIT_FOR_PARALLEL_REGIONS;
                break;
            case WAIT_FOR_PARALLEL_REGIONS:
                this.state = EJobState.WAIT_FOR_DELETE_PES;
                break;
            case WAIT_FOR_DELETE_PES:
                this.state = EJobState.WAIT_FOR_PES;
                break;
            case WAIT_FOR_PES:
                this.state = EJobState.WAIT_FOR_PODS;
                break;
            case WAIT_FOR_PODS:
                this.state = EJobState.SUBMITTED;
                break;
            case SUBMITTED:
                break;
        }
        return this.state;
    }
```

## Pod state machine

The Pod state machine is responsible for the creation of Pods. It listens to
events on the following resources: `ConfigMap`, `HostPool`, `Job`, and
`ProcessingElement`. The state progression of the Pod state machine is stored in
the `PodProgress` class:
```java
    EPodState next()  {
        switch (state) {
            case WAITING_FOR_HOSTPOOLS:
                state = EPodState.WAITING_FOR_CONFIGMAP;
                break;
            case WAITING_FOR_CONFIGMAP:
                state = EPodState.WAITING_FOR_PROCESSINGELEMENT;
                break;
            case WAITING_FOR_PROCESSINGELEMENT:
                state = EPodState.CREATED;
                break;
            case CREATED:
                break;
        }
        return state;
    }
```
