/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.genai;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableMap.toImmutableMap;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.genai.errors.GenAiIOException;
import com.google.genai.types.ClientOptions;
import com.google.genai.types.HttpOptions;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.stream.Stream;
import okhttp3.Dispatcher;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.jspecify.annotations.Nullable;


/** Interface for an API client which issues HTTP requests to the GenAI APIs. */
abstract class ApiClient {

  // {x-version-update-start:google-genai:released}
  private static final String SDK_VERSION = "1.8.0";
  // {x-version-update-end:google-genai:released}
  private static final Logger logger = Logger.getLogger(ApiClient.class.getName());

  private static final ImmutableSet<String> METHODS_WITH_BODY =
      ImmutableSet.of("POST", "PATCH", "PUT");

  private static final ImmutableSet<String> VALID_HTTP_METHODS =
      ImmutableSet.<String>builder().addAll(METHODS_WITH_BODY).add("GET").add("DELETE").build();

  final OkHttpClient httpClient;
  HttpOptions httpOptions;
  final boolean vertexAI;
  final Optional<ClientOptions> clientOptions;
  // For Gemini APIs
  final Optional<String> apiKey;
  // For Vertex AI APIs
  final Optional<String> project;
  final Optional<String> location;
  final Optional<GoogleCredentials> credentials;

  /** Constructs an ApiClient for Google AI APIs. */
  protected ApiClient(
      Optional<String> apiKey,
      Optional<HttpOptions> customHttpOptions,
      Optional<ClientOptions> clientOptions) {
    checkNotNull(apiKey, "API Key cannot be null");
    checkNotNull(customHttpOptions, "customHttpOptions cannot be null");
    checkNotNull(clientOptions, "clientOptions cannot be null");

    try {
      this.apiKey = Optional.of(apiKey.orElse(getApiKeyFromEnv()));
    } catch (NullPointerException e) {
      throw new IllegalArgumentException(
          "API key must either be provided or set in the environment variable"
              + " GOOGLE_API_KEY or GEMINI_API_KEY. If both are set, GOOGLE_API_KEY will be used.",
          e);
    }

    this.project = Optional.empty();
    this.location = Optional.empty();
    this.credentials = Optional.empty();
    this.vertexAI = false;
    this.clientOptions = clientOptions;

    this.httpOptions = defaultHttpOptions(/* vertexAI= */ false, this.location);

    if (customHttpOptions.isPresent()) {
      this.httpOptions = mergeHttpOptions(customHttpOptions.get());
    }

    this.httpClient = createHttpClient(httpOptions.timeout(), clientOptions);
  }

  ApiClient(
      Optional<String> project,
      Optional<String> location,
      Optional<GoogleCredentials> credentials,
      Optional<HttpOptions> customHttpOptions,
      Optional<ClientOptions> clientOptions) {
    checkNotNull(project, "project cannot be null");
    checkNotNull(location, "location cannot be null");
    checkNotNull(credentials, "credentials cannot be null");
    checkNotNull(customHttpOptions, "customHttpOptions cannot be null");
    checkNotNull(clientOptions, "clientOptions cannot be null");

    try {
      this.project = Optional.of(project.orElse(System.getenv("GOOGLE_CLOUD_PROJECT")));
    } catch (NullPointerException e) {
      throw new IllegalArgumentException(
          "Project must either be provided or set in the environment variable"
              + " GOOGLE_CLOUD_PROJECT.",
          e);
    }
    if (this.project.get().isEmpty()) {
      throw new IllegalArgumentException("Project must not be empty.");
    }

    try {
      this.location = Optional.of(location.orElse(System.getenv("GOOGLE_CLOUD_LOCATION")));
    } catch (NullPointerException e) {
      throw new IllegalArgumentException(
          "Location must either be provided or set in the environment variable"
              + " GOOGLE_CLOUD_LOCATION.",
          e);
    }
    if (this.location.get().isEmpty()) {
      throw new IllegalArgumentException("Location must not be empty.");
    }

    this.credentials = Optional.of(credentials.orElseGet(() -> defaultCredentials()));

    this.clientOptions = clientOptions;

    this.httpOptions = defaultHttpOptions(/* vertexAI= */ true, this.location);

    if (customHttpOptions.isPresent()) {
      this.httpOptions = mergeHttpOptions(customHttpOptions.get());
    }
    this.apiKey = Optional.empty();
    this.vertexAI = true;
    this.httpClient = createHttpClient(httpOptions.timeout(), clientOptions);
  }

  private String getApiKeyFromEnv() {
    String googleApiKey = System.getenv("GOOGLE_API_KEY");
    if (googleApiKey != null && googleApiKey.isEmpty()) {
      googleApiKey = null;
    }
    String geminiApiKey = System.getenv("GEMINI_API_KEY");
    if (geminiApiKey != null && geminiApiKey.isEmpty()) {
      geminiApiKey = null;
    }
    if (googleApiKey != null && geminiApiKey != null) {
      logger.warning("Both GOOGLE_API_KEY and GEMINI_API_KEY are set. Using GOOGLE_API_KEY.");
    }
    if (googleApiKey != null) {
      return googleApiKey;
    }
    return geminiApiKey;
  }

  private OkHttpClient createHttpClient(
      Optional<Integer> timeout, Optional<ClientOptions> clientOptions) {
    OkHttpClient.Builder builder = new OkHttpClient.Builder();
    // Remove timeouts by default (OkHttp has a default of 10 seconds)
    builder.connectTimeout(Duration.ofMillis(0));
    builder.readTimeout(Duration.ofMillis(0));
    builder.writeTimeout(Duration.ofMillis(0));

    timeout.ifPresent(connectTimeout -> builder.connectTimeout(Duration.ofMillis(connectTimeout)));

    clientOptions.ifPresent(
        options -> {
          Dispatcher dispatcher = new Dispatcher();
          options.maxConnections().ifPresent(dispatcher::setMaxRequests);
          options.maxConnectionsPerHost().ifPresent(dispatcher::setMaxRequestsPerHost);
          builder.dispatcher(dispatcher);
        });

    return builder.build();
  }

  /** Builds a HTTP request given the http method, path, and request json string. */
  protected Request buildRequest(
      String httpMethod,
      String path,
      String requestJson,
      Optional<HttpOptions> requestHttpOptions) {
    String capitalizedHttpMethod = Ascii.toUpperCase(httpMethod);
    boolean queryBaseModel =
        capitalizedHttpMethod.equals("GET") && path.startsWith("publishers/google/models");
    if (this.vertexAI() && !path.startsWith("projects/") && !queryBaseModel) {
      path =
          String.format("projects/%s/locations/%s/", this.project.get(), this.location.get())
              + path;
    }

    HttpOptions mergedHttpOptions = mergeHttpOptions(requestHttpOptions.orElse(null));

    String requestUrl;

    String baseUrl =
        mergedHttpOptions
            .baseUrl()
            .orElseThrow(() -> new IllegalStateException("baseUrl is required."));
    if (baseUrl.endsWith("/")) {
      // Sometimes users set the base URL with a trailing slash, so we need to remove it.
      baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
    }
    String apiVersion =
        mergedHttpOptions
            .apiVersion()
            .orElseThrow(() -> new IllegalStateException("apiVersion is required."));

    if (apiVersion.isEmpty()) {
      requestUrl = String.format("%s/%s", baseUrl, path);
    } else {
      requestUrl = String.format("%s/%s/%s", baseUrl, apiVersion, path);
    }

    if (!VALID_HTTP_METHODS.contains(capitalizedHttpMethod)) {
      throw new IllegalArgumentException("Unsupported HTTP method: " + capitalizedHttpMethod);
    }

    RequestBody body;
    if (METHODS_WITH_BODY.contains(capitalizedHttpMethod)) {
      body = RequestBody.create(requestJson, MediaType.parse("application/json"));
    } else {
      body = null;
    }
    Request.Builder requestBuilder =
        new Request.Builder().url(requestUrl).method(capitalizedHttpMethod, body);

    setHeaders(requestBuilder, mergedHttpOptions);
    return requestBuilder.build();
  }

  /** Builds a HTTP request given the http method, url, and request bytes. */
  protected Request buildRequest(
      String httpMethod,
      String url,
      byte[] requestBytes,
      Optional<HttpOptions> requestHttpOptions) {
    HttpOptions mergedHttpOptions = mergeHttpOptions(requestHttpOptions.orElse(null));
    if (httpMethod.equalsIgnoreCase("POST")) {
      RequestBody body =
          RequestBody.create(MediaType.get("application/octet-stream"), requestBytes);
      Request.Builder requestBuilder = new Request.Builder().url(url).post(body);
      setHeaders(requestBuilder, mergedHttpOptions);
      return requestBuilder.build();
    } else {
      throw new IllegalArgumentException(
          "The request method with bytes is only supported for POST. Unsupported HTTP method: "
              + httpMethod);
    }
  }

  /** Sets the required headers (including auth) on the request object. */
  private void setHeaders(Request.Builder request, HttpOptions requestHttpOptions) {
    for (Map.Entry<String, String> header :
        requestHttpOptions.headers().orElse(ImmutableMap.of()).entrySet()) {
      request.header(header.getKey(), header.getValue());
    }

    if (apiKey.isPresent()) {
      request.header("x-goog-api-key", apiKey.get());
    } else {
      GoogleCredentials cred =
          credentials.orElseThrow(() -> new IllegalStateException("credentials is required"));
      try {
        cred.refreshIfExpired();
      } catch (IOException e) {
        throw new GenAiIOException("Failed to refresh credentials.", e);
      }
      String accessToken;
      try {
        accessToken = cred.getAccessToken().getTokenValue();
      } catch (NullPointerException e) {
        // For test cases where the access token is not available.
        if (e.getMessage()
            .contains(
                "because the return value of"
                    + " \"com.google.auth.oauth2.GoogleCredentials.getAccessToken()\" is null")) {
          accessToken = "";
        } else {
          throw e;
        }
      }
      request.header("Authorization", "Bearer " + accessToken);

      if (cred.getQuotaProjectId() != null) {
        request.header("x-goog-user-project", cred.getQuotaProjectId());
      }
    }
  }

  /** Sends a Http request given the http method, path, and request json string. */
  @CanIgnoreReturnValue
  public abstract ApiResponse request(
      String httpMethod, String path, String requestJson, Optional<HttpOptions> httpOptions);

  /** Sends a Http request given the http method, path, and request bytes. */
  public abstract ApiResponse request(
      String httpMethod, String path, byte[] requestBytes, Optional<HttpOptions> httpOptions);

  /** Returns the library version. */
  static String libraryVersion() {
    // TODO: Automate revisions to the SDK library version.
    String libraryLabel = String.format("google-genai-sdk/%s", SDK_VERSION);
    String languageLabel = "gl-java/" + System.getProperty("java.version");
    return libraryLabel + " " + languageLabel;
  }

  /** Returns whether the client is using Vertex AI APIs. */
  public boolean vertexAI() {
    return vertexAI;
  }

  /** Returns the project ID for Vertex AI APIs. */
  public @Nullable String project() {
    return project.orElse(null);
  }

  /** Returns the location for Vertex AI APIs. */
  public @Nullable String location() {
    return location.orElse(null);
  }

  /** Returns the API key for Google AI APIs. */
  public @Nullable String apiKey() {
    return apiKey.orElse(null);
  }

  /** Returns the HttpClient for API calls. */
  OkHttpClient httpClient() {
    return httpClient;
  }

  private Optional<Map<String, String>> getTimeoutHeader(HttpOptions httpOptionsToApply) {
    if (httpOptionsToApply.timeout().isPresent()) {
      int timeoutInSeconds = (int) Math.ceil((double) httpOptionsToApply.timeout().get() / 1000.0);
      // TODO(b/329147724): Document the usage of X-Server-Timeout header.
      return Optional.of(ImmutableMap.of("X-Server-Timeout", Integer.toString(timeoutInSeconds)));
    }
    return Optional.empty();
  }

  /**
   * Merges the http options to the client's http options.
   *
   * @param httpOptionsToApply the http options to apply
   * @return the merged http options
   */
  HttpOptions mergeHttpOptions(HttpOptions httpOptionsToApply) {
    if (httpOptionsToApply == null) {
      return this.httpOptions;
    }
    HttpOptions.Builder mergedHttpOptionsBuilder = this.httpOptions.toBuilder();
    if (httpOptionsToApply.baseUrl().isPresent()) {
      mergedHttpOptionsBuilder.baseUrl(httpOptionsToApply.baseUrl().get());
    }
    if (httpOptionsToApply.apiVersion().isPresent()) {
      mergedHttpOptionsBuilder.apiVersion(httpOptionsToApply.apiVersion().get());
    }
    if (httpOptionsToApply.timeout().isPresent()) {
      mergedHttpOptionsBuilder.timeout(httpOptionsToApply.timeout().get());
    }
    if (httpOptionsToApply.headers().isPresent()) {
      Stream<Map.Entry<String, String>> headersStream =
          Stream.concat(
              Stream.concat(
                  this.httpOptions.headers().orElse(ImmutableMap.of()).entrySet().stream(),
                  getTimeoutHeader(httpOptionsToApply)
                      .orElse(ImmutableMap.of())
                      .entrySet()
                      .stream()),
              httpOptionsToApply.headers().orElse(ImmutableMap.of()).entrySet().stream());
      Map<String, String> mergedHeaders =
          headersStream.collect(
              toImmutableMap(Map.Entry::getKey, Map.Entry::getValue, (val1, val2) -> val2));
      mergedHttpOptionsBuilder.headers(mergedHeaders);
    }
    return mergedHttpOptionsBuilder.build();
  }

  static HttpOptions defaultHttpOptions(boolean vertexAI, Optional<String> location) {
    ImmutableMap.Builder<String, String> defaultHeaders = ImmutableMap.builder();
    defaultHeaders.put("Content-Type", "application/json");
    defaultHeaders.put("user-agent", libraryVersion());
    defaultHeaders.put("x-goog-api-client", libraryVersion());

    HttpOptions.Builder defaultHttpOptionsBuilder =
        HttpOptions.builder().headers(defaultHeaders.build());

    if (vertexAI && location.isPresent()) {
      defaultHttpOptionsBuilder
          .baseUrl(
              location.get().equalsIgnoreCase("global")
                  ? "https://aiplatform.googleapis.com"
                  : String.format("https://%s-aiplatform.googleapis.com", location.get()))
          .apiVersion("v1beta1");
    } else if (vertexAI && !location.isPresent()) {
      throw new IllegalArgumentException("Location must be provided for Vertex AI APIs.");
    } else {
      defaultHttpOptionsBuilder
          .baseUrl("https://generativelanguage.googleapis.com")
          .apiVersion("v1beta");
    }

    return defaultHttpOptionsBuilder.build();
  }

  GoogleCredentials defaultCredentials() {
    try {
      return GoogleCredentials.getApplicationDefault()
          .createScoped("https://www.googleapis.com/auth/cloud-platform");
    } catch (IOException e) {
      throw new GenAiIOException(
          "Failed to get application default credentials, please explicitly provide credentials.",
          e);
    }
  }
}
