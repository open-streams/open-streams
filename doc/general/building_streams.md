# Building Streams images

## Install a local registry

See [the official documentation](https://docs.docker.com/registry/deploying/).

## Export registry configuration
```bash
$ export DOCKER_REGISTRY=localhost:5000
$ export DOCKER_NAMESPACE=$USER
```
## Building and pushing the builder image
```bash
$ make builder
```
## Building Streams platform and runtime
```bash
$ make
```
## (Optional) Running unit tests
```bash
$ make platform-test
$ make runtime-test
```
## Building and pushing the runtime image
```bash
$ make runtime-image-build
$ make runtime-image-push
```
## Building and pushing the runtime manifest
```bash
$ make runtime-manifest-build-x86
$ make runtime-manifest-push
```