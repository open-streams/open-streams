/*
 * Copyright 2023 Xenogenics
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ibm.streams.controller.crds.jobs;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.fabric8.kubernetes.api.model.KubernetesResource;
import lombok.Getter;
import lombok.Setter;

@JsonDeserialize
public class HttpSourceSpec implements KubernetesResource {

  @Getter @Setter private String url;
  @Getter @Setter private CertificationAuthoritySpec certificationAuthority;

  public HttpSourceSpec() {}

  public HttpSourceSpec(HttpSourceSpec spec) {
    this.url = spec.url;
    this.certificationAuthority =
        spec.certificationAuthority != null
            ? new CertificationAuthoritySpec(spec.certificationAuthority)
            : null;
  }
}
