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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Optional;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import lombok.var;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okio.Okio;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisConnectionException;

public class BundleUtils {

  private static final Logger LOGGER = LoggerFactory.getLogger(BundleUtils.class);

  public static Optional<byte[]> loadBundleFromRedis(String name, String ns) {
    Optional<byte[]> result = Optional.empty();
    var host = "streams-api." + ns;
    /*
     * Try to fetch the SAB from redis. Retries for 30 seconds.
     */
    for (int i = 0; i < 30; i += 1) {
      try (Jedis jedis = new Jedis(host)) {
        /*
         * Connect to the repository and check if the file exists.
         */
        if (!jedis.hexists("apps", name)) {
          throw new RuntimeException("SAB file not present in the repository");
        }
        /*
         * Get the SAB data.
         */
        result = Optional.of(jedis.hget("apps".getBytes(), name.getBytes()));
        break;
      } catch (JedisConnectionException e) {
        LOGGER.error("Jedis connection exception: {}", e.getMessage());
      }
      try {
        Thread.sleep(1000);
      } catch (InterruptedException ignored) {
      }
    }
    /*
     * Throw an exception if the connection failed.
     */
    return result;
  }

  private static boolean isBundleInRedis(String name, String ns) {
    var host = "streams-api." + ns;
    /*
     * Try to store the SAB to redis. Retries for 30 seconds.
     */
    for (int i = 0; i < 30; i += 1) {
      try (Jedis jedis = new Jedis(host)) {
        return jedis.hexists("apps", name);
      } catch (JedisConnectionException e) {
        LOGGER.error("Jedis connection exception: {}", e.getMessage());
      }
      try {
        Thread.sleep(1000);
      } catch (InterruptedException ignored) {
      }
    }
    return false;
  }

  private static void storeBundleToRedis(String name, byte[] content, String ns) {
    var host = "streams-api." + ns;
    /*
     * Try to store the SAB to redis. Retries for 30 seconds.
     */
    for (int i = 0; i < 30; i += 1) {
      try (Jedis jedis = new Jedis(host)) {
        var status = jedis.hset("apps".getBytes(), name.getBytes(), content);
        LOGGER.info(
            "Bundle {} has been {} in the repository", name, status == 0 ? "updated" : "stored");
        break;
      } catch (JedisConnectionException e) {
        LOGGER.error("Jedis connection exception: {}", e.getMessage());
      }
      try {
        Thread.sleep(1000);
      } catch (InterruptedException ignored) {
      }
    }
  }

  private static Optional<byte[]> loadBundleFromRequestWithClient(
      Request request, OkHttpClient client) {
    Optional<byte[]> result = Optional.empty();
    /*
     * Try to fetch the bundle.
     */
    try {
      var response = client.newCall(request).execute();
      /*
       * Load the bundle.
       */
      if (response.isSuccessful() && response.body() != null) {
        var outputStream = new ByteArrayOutputStream();
        var sink = Okio.buffer(Okio.sink(outputStream));
        sink.writeAll(response.body().source());
        sink.close();
        var bytes = outputStream.toByteArray();
        result = Optional.of(bytes);
      } else {
        LOGGER.error("GET {} failed ({})", request.url(), response.code());
      }
    } catch (IOException e) {
      LOGGER.error("GET {} failed", request.url());
      e.printStackTrace();
    }
    /*
     * Return the result.
     */
    return result;
  }

  private static Optional<byte[]> loadBundleFromRequest(Request request) {
    var client = new OkHttpClient().newBuilder().build();
    return loadBundleFromRequestWithClient(request, client);
  }

  private static Optional<byte[]> loadBundleFromRequest(Request request, String alias, String ca) {
    X509Certificate certificate;
    KeyStore keyStore;
    TrustManagerFactory tmFactory;
    SSLContext sslContext;
    /*
     * Load the certificate.
     */
    try {
      var is = new ByteArrayInputStream(ca.getBytes());
      var certFactory = CertificateFactory.getInstance("X.509");
      certificate = (X509Certificate) certFactory.generateCertificate(is);
    } catch (CertificateException ignored) {
      LOGGER.error("Invalid custom certificate authority");
      return Optional.empty();
    }
    /*
     * Create the key store.
     */
    try {
      keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
      keyStore.load(null);
      keyStore.setCertificateEntry(alias, certificate);
    } catch (CertificateException
        | IOException
        | KeyStoreException
        | NoSuchAlgorithmException ignored) {
      LOGGER.error("Failed to load the certificate into a key store");
      return Optional.empty();
    }
    /*
     * Create the trust factory.
     */
    try {
      tmFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      tmFactory.init(keyStore);
    } catch (KeyStoreException | NoSuchAlgorithmException ignored) {
      LOGGER.error("Failed to create the trust manager");
      return Optional.empty();
    }
    /*
     * Create the SSL context.
     */
    try {
      sslContext = SSLContext.getInstance("TLS");
      sslContext.init(null, tmFactory.getTrustManagers(), null);
    } catch (KeyManagementException | NoSuchAlgorithmException ignored) {
      LOGGER.error("Failed to create the TLS context");
      return Optional.empty();
    }
    /*
     * Create the client.
     */
    var sockFactory = sslContext.getSocketFactory();
    @SuppressWarnings("deprecation")
    var client = new OkHttpClient().newBuilder().sslSocketFactory(sockFactory).build();
    /*
     * Execute the request.
     */
    return loadBundleFromRequestWithClient(request, client);
  }

  public static Optional<byte[]> loadBundleFromFile(
      String name, String path, EBundlePullPolicy pullPolicy, String ns) {
    /*
     * Evaluate the pull policy.
     */
    if (pullPolicy == EBundlePullPolicy.IfNotPresent && isBundleInRedis(name, ns)) {
      LOGGER.info("Bundle {} already present in the repository", name);
      return loadBundleFromRedis(name, ns);
    }
    /*
     * Read the file
     */
    Optional<byte[]> result = Optional.empty();
    var file = new File(path);
    if (file.exists()) {
      if (file.isFile()) {
        try {
          result = Optional.of(FileUtils.readFileToByteArray(file));
        } catch (IOException ignored) {
          LOGGER.error("Failed to read SAB at path {}", path);
        }
      } else {
        LOGGER.error("Path is not a file: {}", path);
      }
    } else {
      LOGGER.error("Invalid path: {}", path);
    }
    /*
     * Return the result.
     */
    result.ifPresent(
        v -> {
          LOGGER.info("Bundle {} successfully loaded from {}", name, path);
          storeBundleToRedis(name, v, ns);
        });
    return result;
  }

  public static Optional<byte[]> loadBundleFromGithub(
      String name, String url, EBundlePullPolicy pullPolicy, String ns) {
    /*
     * Evaluate the pull policy.
     */
    if (pullPolicy == EBundlePullPolicy.IfNotPresent && isBundleInRedis(name, ns)) {
      LOGGER.info("Bundle {} already present in the repository", name);
      return loadBundleFromRedis(name, ns);
    }
    /*
     * Perform the request.
     */
    var request =
        new Request.Builder().url(url).addHeader("Accept", "application/vnd.github.v3.raw").build();
    var result = loadBundleFromRequest(request);
    result.ifPresent(
        v -> {
          LOGGER.info("Bundle {} successfully loaded from {}", name, url);
          storeBundleToRedis(name, v, ns);
        });
    return result;
  }

  public static Optional<byte[]> loadBundleFromGithub(
      String name, String url, String secret, EBundlePullPolicy pullPolicy, String ns) {
    /*
     * Evaluate the pull policy.
     */
    if (pullPolicy == EBundlePullPolicy.IfNotPresent && isBundleInRedis(name, ns)) {
      LOGGER.info("Bundle {} already present in the repository", name);
      return loadBundleFromRedis(name, ns);
    }
    /*
     * Perform the request.
     */
    var request =
        new Request.Builder()
            .url(url)
            .addHeader("Authorization", "token " + secret)
            .addHeader("Accept", "application/vnd.github.v3.raw")
            .build();
    var result = loadBundleFromRequest(request);
    result.ifPresent(
        v -> {
          LOGGER.info("Bundle {} successfully loaded from {}", name, url);
          storeBundleToRedis(name, v, ns);
        });
    return result;
  }

  public static Optional<byte[]> loadBundleFromUrl(
      String name, String url, EBundlePullPolicy pullPolicy, String ns) {
    /*
     * Evaluate the pull policy.
     */
    if (pullPolicy == EBundlePullPolicy.IfNotPresent && isBundleInRedis(name, ns)) {
      LOGGER.info("Bundle {} already present in the repository", name);
      return loadBundleFromRedis(name, ns);
    }
    /*
     * Perform the request.
     */
    var request =
        new Request.Builder().url(url).addHeader("Accept", "application/octet-stream").build();
    var result = loadBundleFromRequest(request);
    result.ifPresent(
        v -> {
          LOGGER.info("Bundle {} successfully loaded from {}", name, url);
          storeBundleToRedis(name, v, ns);
        });
    return result;
  }

  public static Optional<byte[]> loadBundleFromUrl(
      String name, String url, String alias, String ca, EBundlePullPolicy pullPolicy, String ns) {
    /*
     * Evaluate the pull policy.
     */
    if (pullPolicy == EBundlePullPolicy.IfNotPresent && isBundleInRedis(name, ns)) {
      LOGGER.info("Bundle {} already present in the repository", name);
      return loadBundleFromRedis(name, ns);
    }
    /*
     * Perform the request.
     */
    var request =
        new Request.Builder().url(url).addHeader("Accept", "application/octet-stream").build();
    var result = loadBundleFromRequest(request, alias, ca);
    result.ifPresent(
        v -> {
          LOGGER.info("Bundle {} successfully loaded from {}", name, url);
          storeBundleToRedis(name, v, ns);
        });
    return result;
  }
}
