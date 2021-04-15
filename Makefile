#
# Copyright 2021 IBM Corporation
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

PWD := $(shell pwd)
NPROC ?= $(shell nproc --all)
BUILD_TYPE ?= Debug

DOCKER_BUILD = $(shell echo "$(BUILD_TYPE)" | tr '[:upper:]' '[:lower:]')
DOCKER_REGISTRY ?= docker.io
DOCKER_NAMESPACE ?= openstreams

.PHONY: all default x86 info platform runtime test format lint image clean

default: prepare info platform runtime

x86: clean default image-build image-push manifest-build-x86 manifest-push

all: clean default image-build image-push manifest-build manifest-push

builder:
	@make -C docker builder

info:
	@echo "Building using ${NPROC} processors"

prepare:
	@([ ! -d $(HOME)/.m2 ] && mkdir -p $(HOME)/.m2) || true

ifeq ($(DOCKER),)

platform:
	@docker run -it --rm \
		-v $(PWD):$(PWD):rw \
		-v $(HOME)/.m2:/home/builder/.m2:rw \
		$(DOCKER_REGISTRY)/$(DOCKER_NAMESPACE)/streams-builder:6.$(shell uname -m) \
		doas $(shell id -u) $(shell id -g) $(PWD) \
	  make NPROC=$(NPROC) platform

platform-test:
	@docker run -it --rm \
		-v $(PWD):$(PWD):rw \
		-v $(HOME)/.m2:/home/builder/.m2:rw \
		$(DOCKER_REGISTRY)/$(DOCKER_NAMESPACE)/streams-builder:6.$(shell uname -m) \
		doas $(shell id -u) $(shell id -g) $(PWD) \
		make NPROC=$(NPROC) platform-test

runtime:
	@docker run -it --rm \
		-v $(PWD):$(PWD):rw \
		-v $(HOME)/.m2:/home/builder/.m2:rw \
		$(DOCKER_REGISTRY)/$(DOCKER_NAMESPACE)/streams-builder:6.$(shell uname -m) \
		doas $(shell id -u) $(shell id -g) $(PWD) \
		make BUILD_TYPE=$(BUILD_TYPE) NPROC=$(NPROC) runtime

runtime-doc:
	@docker run -it --rm \
		-v $(PWD):$(PWD):rw \
		-v $(HOME)/.m2:/home/builder/.m2:rw \
		$(DOCKER_REGISTRY)/$(DOCKER_NAMESPACE)/streams-builder:6.$(shell uname -m) \
		doas $(shell id -u) $(shell id -g) $(PWD) \
		make BUILD_TYPE=$(BUILD_TYPE) NPROC=$(NPROC) runtime-doc

runtime-test:
	@docker run -it --rm \
		-v $(PWD):$(PWD):rw \
		-v $(HOME)/.m2:/home/builder/.m2:rw \
		$(DOCKER_REGISTRY)/$(DOCKER_NAMESPACE)/streams-builder:6.$(shell uname -m) \
		doas $(shell id -u) $(shell id -g) $(PWD) \
		make BUILD_TYPE=$(BUILD_TYPE) NPROC=$(NPROC) runtime-test

format:
	@docker run -it --rm \
		-v $(PWD):$(PWD):rw \
		-v $(HOME)/.m2:/home/builder/.m2:rw \
		$(DOCKER_REGISTRY)/$(DOCKER_NAMESPACE)/streams-builder:6.$(shell uname -m) \
		doas $(shell id -u) $(shell id -g) $(PWD) \
		make BUILD_TYPE=$(BUILD_TYPE) NPROC=$(NPROC) format

lint:
	@docker run -it --rm \
		-v $(PWD):$(PWD):rw \
		-v $(HOME)/.m2:/home/builder/.m2:rw \
		$(DOCKER_REGISTRY)/$(DOCKER_NAMESPACE)/streams-builder:6.$(shell uname -m) \
		doas $(shell id -u) $(shell id -g) $(PWD) \
		make BUILD_TYPE=$(BUILD_TYPE) NPROC=$(NPROC) lint

else

platform:
	@mvn -ntp -T ${NPROC} package

platform-test:
	@mvn -ntp -T ${NPROC} -DskipTests=false -Dassembly.skipAssembly=true package

runtime:
	@make -C src -f Makefile NPROC=$(NPROC) BUILD_TYPE=$(BUILD_TYPE)

runtime-doc:
	@make -C src -f Makefile NPROC=$(NPROC) BUILD_TYPE=$(BUILD_TYPE) spl_doc_generate

runtime-test:
	@make -C src -f Makefile BUILD_TYPE=$(BUILD_TYPE) test

format:
	@make -C src -f Makefile BUILD_TYPE=$(BUILD_TYPE) format

lint:
	@make -C src -f Makefile BUILD_TYPE=$(BUILD_TYPE) lint

endif

clean:
	@rm -rf build

#
# Docker environment
#

interactive: prepare
	@docker run -it --rm \
		-v $(PWD):$(PWD):rw \
		-v $(HOME)/.m2:/home/builder/.m2:rw \
		$(DOCKER_REGISTRY)/$(DOCKER_NAMESPACE)/streams-builder:6.$(shell uname -m) \
		doas $(shell id -u) $(shell id -g) $(PWD) bash

#
# Image build
#

runtime-image-build:
	@make -C docker \
		DOCKER_BUILD=$(DOCKER_BUILD) \
		DOCKER_NAMESPACE=$(DOCKER_NAMESPACE) \
		DOCKER_REGISTRY=$(DOCKER_REGISTRY) \
		runtime-img-build

image-build: runtime-image-build

#
# Image push
#

runtime-image-push:
	@make -C docker \
		DOCKER_BUILD=$(DOCKER_BUILD) \
		DOCKER_NAMESPACE=$(DOCKER_NAMESPACE) \
		DOCKER_REGISTRY=$(DOCKER_REGISTRY) \
		runtime-img-push

image-push: runtime-image-push

#
# Image manifest build - x86 only (for testing)
#

runtime-manifest-build-x86:
	@docker manifest create --amend --insecure \
		$(DOCKER_REGISTRY)/$(DOCKER_NAMESPACE)/streams-runtime:6.${DOCKER_BUILD} \
		$(DOCKER_REGISTRY)/$(DOCKER_NAMESPACE)/streams-runtime:6.x86_64.${DOCKER_BUILD}

manifest-build-x86: runtime-manifest-build-x86

#
# Image manifest build - x86 and POWER
#

runtime-manifest-build:
	@docker manifest create --amend --insecure \
		$(DOCKER_REGISTRY)/$(DOCKER_NAMESPACE)/streams-runtime:6.${DOCKER_BUILD} \
		$(DOCKER_REGISTRY)/$(DOCKER_NAMESPACE)/streams-runtime:6.ppc64le.${DOCKER_BUILD} \
		$(DOCKER_REGISTRY)/$(DOCKER_NAMESPACE)/streams-runtime:6.x86_64.${DOCKER_BUILD}

manifest-build: runtime-manifest-build

#
# Image manifest push
#

runtime-manifest-push:
	@docker manifest push --purge --insecure \
		$(DOCKER_REGISTRY)/$(DOCKER_NAMESPACE)/streams-runtime:6.${DOCKER_BUILD}

manifest-push: runtime-manifest-push
