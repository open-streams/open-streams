# Compatibility

One of our main design goals is full compatibility with Streams 4.3
applications. We want `.sabs` from Streams 4.3 applications to be able to
run unchanged. However, we have discovered some incompatibilities that are
either unavoidable, or supporting them would have hampered the other goals,
such as fully native Kubernetes integration and usability.

1. **No JMX.** The [Consistent Region](Consistent_Region.md) implementation does not rely on JMX, as that
would introduced a communication and management layer outside of Kubernetes
itself. Platform communication in general has also moved to more Cloud-native
methods. User-applications in Streams 4.3 could use JMX for their own needs.
But, we are not aware of any user applications which actually made use of this
feature. Since Streams itself no longer needs JMX, it is not a widely used
feature, and continuing to support it would impose a significant burden on the
new Streams platform, we will not support it.

2. **Host colocation and exlocation tokens.** The [`hostColocation`][streams_coloc] and [`hostExlocation`][streams_exloc]
config options in SPL allow arbitrary `rstring` values as the coordinating
identifier. A typical example would be `"sources"`, but a string with spaces is
also valid in Streams 4.3, such as `"all of the sources"`. As part of the
[Host Constraints](Streams_Host_Constraints.md) implementation, all of the colocation and exlocation
identifiers are becoming values in pod labels. However, Kubernetes values in
labels are [restricted to 63 characters and do not allow spaces][kube_labels]. The key in
the label already uses the SHA-1 of the identifier. We could have made the value
the SHA-1 of the identifier as well, which would have allowed us to fully
support the valid identifiers from Streams 4.3. But, that would have removed the
usefulness of human-readable labels, and the label itself would no longer has an
obvious connection to what was written in the application. In practice, we think
most identifiers will be under 63 characters and will not use spaces, so this
incompatibility should not impact many user applications.

[streams_coloc]: https://www.ibm.com/support/knowledgecenter/SSCRJU_4.3.0/com.ibm.streams.ref.doc/doc/placementhostcolocation.html
[streams_exloc]: https://www.ibm.com/support/knowledgecenter/SSCRJU_4.3.0/com.ibm.streams.ref.doc/doc/placementhostexlocation.html
[kube_labels]: https://kubernetes.io/docs/concepts/overview/working-with-objects/labels/#syntax-and-character-set
