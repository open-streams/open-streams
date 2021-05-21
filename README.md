# Open-source Streams (6.x.x.x)

Open-source, Cloud-native version of [IBM Streams](https://www.ibm.com/support/knowledgecenter/SSCRJU_4.3.0/com.ibm.streams.welcome.doc/doc/ibminfospherestreams-introduction.html). Is is designed to natively run
on top of Kubernetes, leveraging the [operator pattern](https://kubernetes.io/docs/concepts/extend-kubernetes/operator/) to manage its internal
resources like _Jobs_ or _Processing Elements_.

## Documentation

### General

* [Getting started](doc/general/getting_started.md)
* [Building Streams](doc/general/building_streams.md)
* [Compatibility](doc/general/compatibility.md)

### Design

* [Instance operator](doc/design/instance_operator.md)
* [Processing elements](doc/design/processing_elements.md)
* [Kubernetes adapter](doc/design/kubernetes_adapter.md)
* [Consistent regions](doc/design/consistent_regions.md)
* [Failure handling](doc/design/failure_handling.md)
* [Host constraints](doc/design/host_constraints.md)
* [Import/Export](doc/design/import_export.md)

### Deep dives

* [Installation](doc/deep_dives/installation.md)
* [Instance operator](doc/deep_dives/instance_operator.md)
* [Job submission](doc/deep_dives/job_submission.md)
* [State machines](doc/deep_dives/state_machines.md)
* [Consistent regions](doc/deep_dives/consistent_regions.md)
* [Import/Export](doc/deep_dives/import_export.md)

## License

Apache 2.0. Please see the `LICENSE` file.