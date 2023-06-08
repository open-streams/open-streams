/*
 * Copyright 2021 IBM Corporation
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

package com.ibm.streams.controller.bundle;

import com.ibm.streams.controller.crds.jobs.BundleSpec;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.util.Base64;
import java.util.Optional;
import lombok.var;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KubernetesBundleLoader implements IBundleLoader {

  private static final Logger LOGGER = LoggerFactory.getLogger(KubernetesBundleLoader.class);
  private final KubernetesClient client;

  public KubernetesBundleLoader(KubernetesClient client) {
    this.client = client;
  }

  private Optional<byte[]> loadFileSource(BundleSpec spec, String namespace) {
    return BundleUtils.loadBundleFromFile(
        spec.getName(), spec.getFile().getPath(), spec.getPullPolicy(), namespace);
  }

  private Optional<byte[]> loadGithubSource(BundleSpec spec, String namespace) {
    Optional<byte[]> content = Optional.empty();
    if (spec.getGithub().getSecret() == null) {
      content =
          BundleUtils.loadBundleFromGithub(
              spec.getName(), spec.getGithub().getUrl(), spec.getPullPolicy(), namespace);
    } else {
      var secret =
          client.secrets().inNamespace(namespace).withName(spec.getGithub().getSecret()).get();
      if (secret == null) {
        LOGGER.error("Cannot find secret {}", spec.getGithub().getSecret());
      } else if (secret.getData() != null && secret.getData().containsKey("token")) {
        var token64 = secret.getData().get("token");
        var token = new String(Base64.getDecoder().decode(token64));
        content =
            BundleUtils.loadBundleFromGithub(
                spec.getName(), spec.getGithub().getUrl(), token, spec.getPullPolicy(), namespace);
      }
    }
    return content;
  }

  private Optional<byte[]> loadHttpSource(BundleSpec spec, String namespace) {
    return BundleUtils.loadBundleFromUrl(
        spec.getName(), spec.getHttp().getUrl(), spec.getPullPolicy(), namespace);
  }

  @Override
  public Optional<Bundle> load(BundleSpec spec, String namespace) {
    Optional<byte[]> result = Optional.empty();
    /*
     * Get the content direcly from Redis or from GitHub.
     */
    if (spec.getFile() != null && spec.getGithub() != null && spec.getHttp() != null) {
      LOGGER.error("Bundle source options are mutually exclusive");
    } else if (spec.getFile() == null && spec.getGithub() == null && spec.getHttp() == null) {
      result = BundleUtils.loadBundleFromRedis(spec.getName(), namespace);
    } else if (spec.getFile() != null) {
      result = loadFileSource(spec, namespace);
    } else if (spec.getGithub() != null) {
      result = loadGithubSource(spec, namespace);
    } else {
      result = loadHttpSource(spec, namespace);
    }
    /*
     * Invalid bundle.
     */
    return result.map(c -> new Bundle(spec.getName(), c));
  }
}
