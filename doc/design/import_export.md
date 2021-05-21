# Import/Export

## Design

### The Export operator

The `Export` operator makes a stream available for external subscription. It
accepts 3 parameters:

* `allowFilter`: optional boolean; if `false` forbid `Import` operators to specify a filter
* `properties`: a key-value pair list of properties that describe the exported stream
* `streamId`: a unique identifier for the exported stream

The parameters `properties` and `streamId` are mutually exclusive.

### The Import operator

The `Import` operator subscribe to streams made available by `Export` operators.
It accepts 5 parameters:

* `subscription`: expression used to match `Export` operator properties
* `applicationName`: the name of the application from which the stream is imported
* `applicationScope`: the scope of the exporting application
* `streamId`: the identifier of the stream to import
* `filter`: the filter to be applied on the exported stream

The parameters `(applicationName, streamId)` and `subscription` are mutually exclusive.

### Connection brokering

`SAM` performs the connection brokering between the `Import` and `Export`
operators. In effect, `SAM` instructs the `Export` operator to connect to the
`Import` operators that requested its stream.

`SAM` keeps track of all jobs and all available exporting ports. When importing
ports are processed, `SAM` scans exporting port for matching exporting ports.
For each match, `SAM` sends an *update route* notification to each owning PE.

`Import` and `Export` parameters can also be altered programmatically by the
application using the following functions:
```spl
int32 setInputPortImportFilterExpression(rstring filter, uint32 port)
int32 setInputPortImportSubscription(rstring subscription, uint32 port)

<tuple T> int32 setOutputPortExportProperties(T properties, uint32 port)
int32 setOutputPortExportProperties(list<tuple<rstring name, rstring value, rstring typ>> properties, uint32 port)
```
The route change notification and the application of the changes programmatically
defined by these functions are processed by the `HostController` [API](https://github.com/IBMStreams/OSStreams/blob/main/src/cpp/K8S/K8SPlatform.h#L47) of the `PEC`.

### Dynamic connections

Dynamic connections, or optional connections, may come and go. However, they are
implemented using the same classes as the static connections. To handle their
dynamic aspect, their connection process differs from the one use by the static
connections.

#### Handling connection lost

When a static connection fails, the reconnection process is handled by the
`TCPConnection::write()` function. The `write` notices that the connection is
broken, attemps to reconnect, and resumes its operation once the reconnection
has succeeded.

When a dynamic connection fails, the `write()` goes through much of that
process, except that:

1. It sends a notification to a _connection helper_, a separate thread that
   will attempt to reconnect on its behalf.
2. It throws an `OptionalConnectIncompleteException` to let the caller know that
   the reconnect has failed and that it can skip the connection until the next
   write.

In principle this design is sound. However, its use of exceptions in the
critical path of the thread (the `write` path) is unwise and potentially the
reason why dynamic connection do not perform on par with static connections.

#### Handling congestion

A congestion policy was added to the `Export` operator to address adverse
performance effects happening when some `Import` are slow. References:

1. The [documentation](https://www.ibm.com/support/knowledgecenter/SSCRJU_4.3.0/com.ibm.streams.toolkits.doc/spldoc/dita/tk$spl/op$spl.adapter$Export.html#spldoc_operator__parameter__congestionPolicy) about the policy
2. The [actual implementation](https://github.com/IBMStreams/OSStreams/blob/main/src/cpp/TRANS/TCPConnection.cpp#L766)

There is a disconnect between the documentation, the title of the design
document and the actual implementation. Connections are not dropped when they
are congested, _the data is_. The `wait` policy add a blocking wait in the
critical `write()` path in the hope that the connection helper will succeed in
establishing the connection. The `drop` policy _do not block_ and simply go
through throwing an exception.

Blocking in the `write()` end up delaying all the other importers down the line
in `TCPSender::write()`. Since `drop` does not drop the connection, the same
reconnect/fail/wait process happens for every write.

## Kubernetes integration

### Custom resources

The integration of the import/export subscription system hinges around two new
custom resources: the `Import` resource and the `Export` resource. The `Import`
resource collects all imports available across jobs in a namespace. The `Export`
resource collects all exports available across jobs in a namespace.

### Controllers

For each new resource, there is a new controller. When the `ImportController`
gets an `onAddition` event, it will collects all existing exports in the
namespace and select the one matching the new import requirements. The
`ExportController` behaves similarly, collecting all matching imports for the
new export.

### Notifications

Sharing state changes event with PEs is a challenging problems. The solution we
explored are: 1/ exporting the changes into the job `ConfigMap` and monitor the
file change; 2/ use a secondary container with a local import/export controller
to proxy state changes to the PE; 3/ export a REST API to manipulate and query
optional connections.

1. Exporting the changes into the job `ConfigMap` quickly became irrelevant as
   we learnt that import/export parameters can be updated programmatically.

2. Running an import/export controller allows to proxy both changes and
   notifications between PEs and Kubernetes. But running a controller in a
   separate container for each PE in the system is too heavyweight.

3. Exporting a REST API on both the PE and the Streams controller allows both
   notification and parameter alterations to happen. It's also quite lightweight
   on both side.

4. Exporting a REST API on the Streams controller only and use loosely coupled
   UDP notifications as well as periodic polling to synchornize the PEs

Options 4 is a variant on option 3 with the added benefit that the controller
does not need to manage connections to PEs to send synchronous REST calls. In
that scenario, PEs and controller stay loosely coupled, which is a very
attractive attribute.

Therefore we selected option 4. We may need to revisit that choice as we
progress with our integration of the Consistent Regions. Indeed, the JCP will
have to send state change events to PEs and in that case a REST API on PEs will
be needed.

## Event diagrams

### Import/Export job creation

![impexp_events](https://user-images.githubusercontent.com/73404/108544627-057caa00-72e7-11eb-9253-72132b103c19.png)

## Implementation details

### The subscriptions REST API

The controller export an API endpoint for subscriptions at `/api/subscriptions`.
Path entries surrounded by `{}` are attributes set by the client.

<table>
  <tr>
    <th>GET</th>
    <th>PUT</th>
    <th>PATCH</th>
    <th>DELETE</th>
  </tr>
  <tr>
    <td colspan=4><code>/job/{jobid}/pe/{peid}</code></td>
  </tr>
  <tr>
    <td>Fetch all the subscriptions for a PE.</td>
    <td/>
    <td/>
    <td/>
  </tr>
  <tr>
    <td colspan=4><code>/job/{jobid}/pe/{peid}/export/{opid}</code></td>
  </tr>
  <tr>
    <td>Fetch the description of the export.</td>
    <td/>
    <td/>
    <td/>
  </tr>
  <tr>
    <td colspan=4><code>/job/{jobid}/pe/{peid}/export/{opid}/properties</code></td>
  </tr>
  <tr>
    <td>Get all properties of the export.</td>
    <td>Replace all properties of the export.</td>
    <td>Delete some properties of the export.</td>
    <td/>
  </tr>
  <tr>
    <td colspan=4><code>/job/{jobid}/pe/{peid}/export/{opid}/property/{name}</code></td>
  </tr>
  <tr>
    <td>Get a property of the export by name.</td>
    <td>Replace a property of the export by name.</td>
    <td/>
    <td>Delete a property of the export by name.</td>
  </tr>
  <tr>
    <td colspan=4><code>/job/{jobid}/pe/{peid}/import/{opid}</code></td>
  </tr>
  <tr>
    <td>Fetch the description of the import.</td>
    <td/>
    <td/>
    <td/>
  </tr>
  <tr>
    <td colspan=4><code>/job/{jobid}/pe/{peid}/import/{opid}/filter</code></td>
  </tr>
  <tr>
    <td>Get the filter of the import.</td>
    <td>Replace the filter of the import.</td>
    <td/>
    <td/>
  </tr>
  <tr>
    <td colspan=4><code>/job/{jobid}/pe/{peid}/import/{opid}/streams</code></td>
  </tr>
  <tr>
    <td>Get the imported streams of the import.</td>
    <td>Replace the imported streams of the import.</td>
    <td/>
    <td/>
  </tr>
</table>

### Operator behavior

`Import` and `Export` operators are not actual operators. They are annotations
that change the behavior of the input port and output port, respectively, of the
operator they are connected to.

Therefore, when an `Import` is connected to more than one operator, each
connection is reflected as a separate `Import` resource in Kubernetes.

Connecting multiple subscription `Import` to the same operator port is not
supported. It is prevented by the compiler. As for `Export`, the same stream
cannot be exported more than once. It is prevented by the compiler.

### About platform getters and setters

It is possible that the subscription service becomes unreachable if the operator
goes down.  Setters, in `SPLFunctions.cpp`, properly handle that situation by
catching any downstream exception and returning error code 3. Getters, on the
other hand, don't.

When such a situation occur and an exception is thrown, the exception is caught
upstream of the application and the PE is terminated. We replicate that behavior
in `K8SPlatform.cpp`. Ideally, getters should also catch downstream exceptions
and return error code 3.
