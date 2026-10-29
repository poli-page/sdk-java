package page.poli.sdk.contract;

import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import page.poli.sdk.PoliPageClient;
import page.poli.sdk.RequestEvent;
import page.poli.sdk.RetryEvent;
import page.poli.sdk.exception.PoliPageException;
import page.poli.sdk.input.RenderInput;
import page.poli.sdk.model.DocumentPreviewResult;
import page.poli.sdk.model.Orientation;
import page.poli.sdk.model.PageFormat;
import page.poli.sdk.model.ThumbnailFormat;
import page.poli.sdk.model.ThumbnailOptions;

/**
 * Conformance with the Poli Page API contract (<a
 * href="https://github.com/poli-page/api-contract">poli-page/api-contract</a>).
 *
 * <p>CI copies the pinned {@code contract.json} with the {@code poli-page/api-contract} action and
 * sets {@code POLI_CONTRACT} to its path. Every {@code kind: "sdk"} case runs against a {@link
 * MockServer} on a real socket that answers the case's responses in order and records each request,
 * through the SDK's real HTTP client; the test then compares the recorded requests, the {@code
 * onRequest} / {@code onRetry} events and the normalized result with the case's {@code expect}.
 * Timing is checked as lower bounds, measured on the server.
 *
 * <p>Each case runs on the blocking facade ({@code client.render()}, {@code client.documents()}),
 * where cancellation is a thread interrupt, and on the async facade ({@code client.renderAsync()},
 * {@code client.documentsAsync()}), where it is {@code cancel(true)} on the returned future. A
 * cancelled async call throws the JDK's {@code CancellationException}, whose cause is the SDK's
 * {@code aborted} error: that cause is the result compared.
 *
 * <p>{@code src/test/resources/contract-skip.json} maps the case ids allowed to fail to the reason.
 * A listed case that passes, or a listed id the contract does not have, fails the run.
 *
 * <p>Locally: {@code POLI_CONTRACT=/path/to/contract.json ./mvnw test}. Without it the suite is
 * skipped, except when {@code CI} is set, where a missing contract is an error.
 */
class ContractTest {

  /** The contract.json layout this file reads (README of poli-page/api-contract). */
  private static final int FORMAT_VERSION = 1;

  /** This SDK's name in {@code policy.cancellationSdks}. */
  private static final String SDK_NAME = "java";

  /** The client's key when a case sets no {@code apiKey}. */
  private static final String TEST_API_KEY = "pp_sandbox_00000000000000000000000000000000";

  private static final String SKIP_FILE = "contract-skip.json";

  /** Bound of one case; the slowest (a 2 s Retry-After date, twice) takes about 5 s. */
  private static final Duration CASE_TIMEOUT = Duration.ofSeconds(30);

  /** The {@code policy.normalizations} ids this file applies; each must still be listed. */
  private static final List<String> NORMALIZATIONS =
      List.of(
          "N-STATUS-NULL",
          "N-REQID-NULL",
          "N-BYTES",
          "N-DELETE-NULL",
          "N-THUMBS-ARRAY",
          "N-PREVIEW",
          "N-DESCRIPTOR",
          "N-DURATION-MS");

  /** The DocumentDescriptor fields of sdk-specification.md §7.1 (N-DESCRIPTOR). */
  private static final List<String> DESCRIPTOR_FIELDS =
      List.of(
          "documentId",
          "organizationId",
          "projectId",
          "projectSlug",
          "templateId",
          "templateSlug",
          "environment",
          "apiKeyId",
          "format",
          "orientation",
          "locale",
          "pageCount",
          "sizeBytes",
          "createdAt",
          "metadata",
          "presignedPdfUrl",
          "expiresAt");

  private static final Set<String> CASE_FIELDS =
      Set.of(
          "kind",
          "id",
          "title",
          "requires",
          "apiKey",
          "options",
          "call",
          "responses",
          "expect",
          "stopAfterRetryEvents");

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([^}]*)\\}\\}");
  private static final Pattern HTTP_DATE = Pattern.compile("^httpDate\\+(\\d+)$");
  private static final Pattern UUID_V4 =
      Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final ObjectMapper CANONICAL =
      JsonMapper.builder()
          .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .enable(SerializationFeature.INDENT_OUTPUT)
          .build();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private enum Facade {
    BLOCKING,
    ASYNC
  }

  // ===================================================================
  // Suite
  // ===================================================================

  @TestFactory
  Stream<DynamicNode> contract() throws IOException {
    String path = System.getenv("POLI_CONTRACT");
    if (path == null || path.isEmpty()) {
      String ci = System.getenv("CI");
      if (ci != null && !ci.isEmpty()) {
        fail(
            "POLI_CONTRACT is not set: in CI the \"API contract\" step must provide contract.json"
                + " (poli-page/api-contract action, output \"path\")");
      }
      // A skipped dynamic test, so the report shows the reason.
      return Stream.of(
          DynamicTest.dynamicTest(
              "API contract",
              () ->
                  Assumptions.abort(
                      "API contract tests skipped: set POLI_CONTRACT to a contract.json path")));
    }
    JsonNode contract = loadContract(Path.of(path));
    Map<String, String> skip = loadSkipList();

    List<String> fileProblems = new ArrayList<>();
    List<JsonNode> sdkCases = new ArrayList<>();
    Set<String> ids = new java.util.HashSet<>();
    int index = 0;
    for (JsonNode raw : contract.path("cases")) {
      String kind = raw.path("kind").asText();
      String id = raw.path("id").asText();
      switch (kind) {
        case "sdk" -> {
          ids.add(id);
          sdkCases.add(raw);
        }
        case "impl" -> {
          // For integration libraries.
        }
        default ->
            fileProblems.add(
                "cases["
                    + index
                    + "] ("
                    + id
                    + ") has kind \""
                    + kind
                    + "\": this test knows only \"sdk\" and \"impl\"");
      }
      index++;
    }
    if (sdkCases.isEmpty()) {
      fail("the contract has no \"sdk\" case");
    }
    Set<String> listed = new java.util.HashSet<>();
    for (JsonNode n : contract.path("policy").path("normalizations")) {
      listed.add(n.path("id").asText());
    }
    for (String id : NORMALIZATIONS) {
      if (!listed.contains(id)) {
        fileProblems.add("normalization " + id + " is applied here but no longer in the contract");
      }
    }
    for (String id : listed) {
      if (!NORMALIZATIONS.contains(id)) {
        fileProblems.add(
            "the contract lists normalization " + id + ", which this test does not apply");
      }
    }
    // The SDK's isRetryable() must agree with the contract's never-retried API codes.
    for (JsonNode code : contract.path("policy").path("nonRetryableCodes")) {
      for (int status : new int[] {429, 502, 503}) {
        PoliPageException error = new PoliPageException(code.asText(), status, "x", null, null);
        if (error.isRetryable()) {
          fileProblems.add(
              "policy.nonRetryableCodes lists "
                  + code.asText()
                  + ", but isRetryable() is true for it with status "
                  + status);
        }
      }
    }
    boolean runsCancellation = false;
    for (JsonNode sdk : contract.path("policy").path("cancellationSdks")) {
      runsCancellation |= SDK_NAME.equals(sdk.asText());
    }
    for (String id : skip.keySet()) {
      if (!ids.contains(id)) {
        fileProblems.add(
            SKIP_FILE + " lists \"" + id + "\", which the contract does not have: remove it");
      }
    }

    List<DynamicNode> tests = new ArrayList<>();
    tests.add(
        DynamicTest.dynamicTest(
            "contract.json and " + SKIP_FILE,
            () -> {
              if (!fileProblems.isEmpty()) {
                fail(String.join("\n", fileProblems));
              }
            }));
    for (JsonNode raw : sdkCases) {
      String id = raw.path("id").asText();
      boolean cancellationOk = runsCancellation;
      tests.add(DynamicTest.dynamicTest(id, () -> runListed(raw, skip.get(id), cancellationOk)));
    }
    return tests.stream();
  }

  private static JsonNode loadContract(Path path) throws IOException {
    JsonNode contract;
    try {
      contract = JSON.readTree(Files.readAllBytes(path));
    } catch (IOException e) {
      throw new IOException(path + " is not a readable JSON file: " + e.getMessage(), e);
    }
    JsonNode version = contract.get("formatVersion");
    if (version == null) {
      fail(path + " is not a contract.json: it has no formatVersion");
    }
    if (version.asInt() != FORMAT_VERSION) {
      fail(
          "contract.json formatVersion "
              + version
              + " is not supported (this test reads format "
              + FORMAT_VERSION
              + "): update ContractTest");
    }
    return contract;
  }

  private static Map<String, String> loadSkipList() throws IOException {
    try (InputStream in = ContractTest.class.getClassLoader().getResourceAsStream(SKIP_FILE)) {
      if (in == null) {
        fail(SKIP_FILE + " is missing from src/test/resources");
      }
      JsonNode entries = JSON.readTree(in);
      if (!entries.isObject()) {
        fail(SKIP_FILE + " must be an object of case id to reason");
      }
      Map<String, String> skip = new TreeMap<>();
      for (Map.Entry<String, JsonNode> entry : entries.properties()) {
        if (!entry.getValue().isTextual() || entry.getValue().asText().isBlank()) {
          fail(SKIP_FILE + ": \"" + entry.getKey() + "\" needs a reason");
        }
        skip.put(entry.getKey(), entry.getValue().asText());
      }
      return skip;
    }
  }

  private static void runListed(JsonNode raw, @Nullable String skipReason, boolean runsCancellation)
      throws Exception {
    String id = raw.path("id").asText();
    boolean needsCancellation = "cancellation".equals(raw.path("requires").asText(null));
    if (needsCancellation && !runsCancellation) {
      if (skipReason != null) {
        fail(id + " does not run for " + SDK_NAME + ": remove it from " + SKIP_FILE);
      }
      Assumptions.abort("policy.cancellationSdks does not list \"" + SDK_NAME + "\"");
    }
    List<String> problems = new ArrayList<>();
    try {
      problems.addAll(prefixed("blocking facade", runCase(raw, Facade.BLOCKING)));
      problems.addAll(prefixed("async facade", runCase(raw, Facade.ASYNC)));
    } catch (CaseFormatException e) {
      problems.add("cannot run the case: " + e.getMessage());
    }
    if (skipReason == null && !problems.isEmpty()) {
      fail(raw.path("title").asText() + "\n" + String.join("\n", problems));
    }
    if (skipReason != null && problems.isEmpty()) {
      fail(id + " passes now: remove it from " + SKIP_FILE);
    }
    if (skipReason != null) {
      Assumptions.abort("expected to fail (" + skipReason + "):\n" + String.join("\n", problems));
    }
  }

  private static List<String> prefixed(String label, List<String> problems) {
    List<String> out = new ArrayList<>();
    for (String problem : problems) {
      out.add("[" + label + "] " + problem);
    }
    return out;
  }

  /** The case does not have the shape this file reads. */
  private static final class CaseFormatException extends Exception {
    CaseFormatException(String message) {
      super(message);
    }
  }

  // ===================================================================
  // Running one case
  // ===================================================================

  private record Event(String type, int attempt, double delayMs) {}

  private static List<String> runCase(JsonNode c, Facade facade) throws Exception {
    checkShape(c);
    MockServer server = MockServer.start(script(c.path("responses")));
    try {
      return runCase(c, facade, server);
    } finally {
      server.close();
    }
  }

  private static List<String> runCase(JsonNode c, Facade facade, MockServer server)
      throws Exception {
    String origin = server.origin();
    String apiKey = c.has("apiKey") ? c.get("apiKey").asText() : TEST_API_KEY;
    JsonNode options = c.path("options");
    JsonNode call = c.path("call");
    int stopAfter = c.path("stopAfterRetryEvents").asInt(0);

    List<Event> events = new CopyOnWriteArrayList<>();
    AtomicInteger retries = new AtomicInteger();
    AtomicReference<CompletableFuture<?>> pending = new AtomicReference<>();
    AtomicBoolean stopRequested = new AtomicBoolean();
    Runnable stop =
        () -> {
          stopRequested.set(true);
          if (facade == Facade.BLOCKING) {
            // onRetry runs on the calling thread on the blocking facade.
            Thread.currentThread().interrupt();
          } else {
            CompletableFuture<?> future = pending.get();
            if (future != null) {
              future.cancel(true);
            }
          }
        };

    Outcome outcome;
    PoliPageClient client;
    try {
      client =
          PoliPageClient.builder()
              .apiKey(apiKey)
              .baseUrl(URI.create(origin))
              .maxRetries(options.path("maxRetries").asInt())
              .retryDelay(Duration.ofMillis(options.path("retryDelayMs").asLong()))
              .requestTimeout(Duration.ofMillis(options.path("timeoutMs").asLong()))
              .onRequest((RequestEvent e) -> events.add(new Event("request", e.attempt(), 0)))
              .onRetry(
                  (RetryEvent e) -> {
                    events.add(new Event("retry", e.attempt(), e.delay().toNanos() / 1_000_000.0));
                    if (stopAfter > 0 && retries.incrementAndGet() == stopAfter) {
                      stop.run();
                    }
                  })
              .build();
      outcome = invoke(client, facade, call, origin, pending, stopRequested);
    } catch (PoliPageException e) {
      outcome = Outcome.failed(e);
    }

    List<String> problems = new ArrayList<>(server.problems());
    if (outcome.timedOut()) {
      problems.add("the call did not return within " + CASE_TIMEOUT);
      return problems;
    }

    JsonNode wantEvents = c.path("expect").path("events");
    JsonNode wantRequests = c.path("expect").path("requests");
    boolean checkResult = true;
    if (stopAfter > 0) {
      int[] cut = prefixAt(wantEvents, stopAfter);
      wantEvents = slice(wantEvents, cut[0]);
      wantRequests = slice(wantRequests, cut[1]);
      checkResult = false;
    }
    JsonNode substitutedRequests = substitute(wantRequests, origin);
    compare(
        problems,
        "requests",
        describeRequests(server.requests(), wantRequests, apiKey),
        substitutedRequests);
    compare(problems, "onRequest / onRetry events", describeEvents(events, wantEvents), wantEvents);
    if (checkResult) {
      compare(
          problems,
          "result",
          outcome.normalized(),
          substitute(c.path("expect").path("result"), origin));
    }
    return problems;
  }

  /** The events and requests up to the {@code n}th retry event. */
  private static int[] prefixAt(JsonNode events, int n) throws CaseFormatException {
    int retries = 0;
    for (int i = 0; i < events.size(); i++) {
      if ("retry".equals(events.get(i).path("type").asText()) && ++retries == n) {
        int attempts = 0;
        for (int j = 0; j <= i; j++) {
          if ("request".equals(events.get(j).path("type").asText())) {
            attempts++;
          }
        }
        return new int[] {i + 1, attempts};
      }
    }
    throw new CaseFormatException("stopAfterRetryEvents " + n + ": the case has fewer retries");
  }

  private static JsonNode slice(JsonNode array, int length) {
    ArrayNode out = NODES.arrayNode();
    for (int i = 0; i < Math.min(length, array.size()); i++) {
      out.add(array.get(i));
    }
    return out;
  }

  private static void checkShape(JsonNode c) throws CaseFormatException {
    Iterator<String> names = c.fieldNames();
    while (names.hasNext()) {
      String name = names.next();
      if (!CASE_FIELDS.contains(name)) {
        throw new CaseFormatException("unknown case field \"" + name + "\"");
      }
    }
    String requires = c.path("requires").asText(null);
    if (requires != null && !requires.equals("cancellation")) {
      throw new CaseFormatException("unknown case requirement \"" + requires + "\"");
    }
    int i = 0;
    for (JsonNode step : c.path("responses")) {
      int bodies =
          (step.has("json") ? 1 : 0) + (step.has("text") ? 1 : 0) + (step.has("base64") ? 1 : 0);
      if (step.has("fault")) {
        String fault = step.get("fault").asText();
        if (!fault.equals("drop") && !fault.equals("hang")) {
          throw new CaseFormatException("responses[" + i + "]: unknown fault \"" + fault + "\"");
        }
        if (step.size() != 1) {
          throw new CaseFormatException("responses[" + i + "]: a fault has no status or body");
        }
      } else if (!step.has("status")) {
        throw new CaseFormatException("responses[" + i + "]: no status");
      } else if (bodies > 1) {
        throw new CaseFormatException("responses[" + i + "]: more than one of json, text, base64");
      }
      i++;
    }
  }

  // ===================================================================
  // The mock server's script: the case's responses, placeholders replaced when answered
  // ===================================================================

  private static MockServer.Script script(JsonNode steps) {
    return (index, origin) -> {
      if (index >= steps.size()) {
        return new MockServer.Answer(
            599,
            Map.of("Content-Type", "application/json"),
            "{\"error\":\"MOCK_EXHAUSTED\"}".getBytes(StandardCharsets.UTF_8));
      }
      JsonNode step = steps.get(index);
      if (step.has("fault")) {
        return step.get("fault").asText().equals("drop")
            ? new MockServer.Drop()
            : new MockServer.Hang();
      }
      Map<String, String> headers = new LinkedHashMap<>();
      try {
        for (Map.Entry<String, JsonNode> header : step.path("headers").properties()) {
          headers.put(header.getKey(), substitute(header.getValue().asText(), origin));
        }
        byte[] body = new byte[0];
        if (step.has("json")) {
          body = JSON.writeValueAsBytes(substitute(step.get("json"), origin));
          headers.putIfAbsent("Content-Type", "application/json");
        } else if (step.has("text")) {
          body = step.get("text").asText().getBytes(StandardCharsets.UTF_8);
        } else if (step.has("base64")) {
          body = Base64.getDecoder().decode(step.get("base64").asText());
        }
        return new MockServer.Answer(step.get("status").asInt(), headers, body);
      } catch (CaseFormatException | IOException e) {
        return new MockServer.Answer(
            598,
            Map.of(),
            ("mock cannot answer: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
      }
    };
  }

  private static JsonNode substitute(JsonNode value, String origin) throws CaseFormatException {
    if (value.isTextual()) {
      return NODES.textNode(substitute(value.asText(), origin));
    }
    if (value.isArray()) {
      ArrayNode out = NODES.arrayNode();
      for (JsonNode item : value) {
        out.add(substitute(item, origin));
      }
      return out;
    }
    if (value.isObject()) {
      ObjectNode out = NODES.objectNode();
      for (Map.Entry<String, JsonNode> field : value.properties()) {
        out.set(field.getKey(), substitute(field.getValue(), origin));
      }
      return out;
    }
    return value;
  }

  private static String substitute(String text, String origin) throws CaseFormatException {
    Matcher m = PLACEHOLDER.matcher(text);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String name = m.group(1);
      String replacement;
      Matcher date = HTTP_DATE.matcher(name);
      if (name.equals("mock")) {
        replacement = origin;
      } else if (date.matches()) {
        // An HTTP-date has whole seconds: round to the nearest one.
        long millis = System.currentTimeMillis() + Long.parseLong(date.group(1)) * 1000;
        long seconds = Math.round(millis / 1000.0);
        replacement =
            DateTimeFormatter.RFC_1123_DATE_TIME.format(
                ZonedDateTime.ofInstant(java.time.Instant.ofEpochSecond(seconds), ZoneOffset.UTC));
      } else {
        throw new CaseFormatException("unknown contract placeholder " + m.group());
      }
      m.appendReplacement(out, Matcher.quoteReplacement(replacement));
    }
    m.appendTail(out);
    return out.toString();
  }

  // ===================================================================
  // Calling the SDK
  // ===================================================================

  /** The call's result: a normalized value, an error, or no return in time. */
  private record Outcome(@Nullable JsonNode value, @Nullable Throwable error, boolean timedOut) {

    static Outcome ok(JsonNode value) {
      return new Outcome(value, null, false);
    }

    static Outcome failed(Throwable error) {
      return new Outcome(null, error, false);
    }

    static Outcome hung() {
      return new Outcome(null, null, true);
    }

    JsonNode normalized() {
      ObjectNode out = NODES.objectNode();
      if (error == null) {
        out.put("ok", true);
        out.set("value", value);
      } else {
        out.put("ok", false);
        out.set("error", normalizeError(error));
      }
      return out;
    }
  }

  /** The SDK call, run on its own thread so that a cancellation can interrupt it. */
  @FunctionalInterface
  private interface SdkCall {
    JsonNode run() throws Exception;
  }

  private static Outcome invoke(
      PoliPageClient client,
      Facade facade,
      JsonNode call,
      String origin,
      AtomicReference<CompletableFuture<?>> pending,
      AtomicBoolean stopRequested)
      throws Exception {
    String method = call.path("method").asText();
    JsonNode args = call.path("args");
    SdkCall sdkCall =
        facade == Facade.BLOCKING
            ? blockingCall(client, method, args)
            : asyncCall(client, method, args, pending, stopRequested);

    CompletableFuture<Outcome> done = new CompletableFuture<>();
    Thread worker =
        new Thread(
            () -> {
              try {
                done.complete(Outcome.ok(sdkCall.run()));
              } catch (Throwable t) {
                done.complete(Outcome.failed(unwrap(t)));
              }
            },
            "contract-call");
    worker.setDaemon(true);

    ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    try {
      worker.start();
      if (call.has("cancelAfterMs")) {
        long after = call.get("cancelAfterMs").asLong();
        timer.schedule(
            () -> {
              if (done.isDone()) {
                return;
              }
              if (facade == Facade.BLOCKING) {
                worker.interrupt();
              } else {
                CompletableFuture<?> future = pending.get();
                if (future != null) {
                  future.cancel(true);
                }
              }
            },
            after,
            TimeUnit.MILLISECONDS);
      }
      try {
        Outcome outcome = done.get(CASE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        if (outcome.error() instanceof CaseFormatException e) {
          throw e;
        }
        return outcome;
      } catch (TimeoutException e) {
        worker.interrupt();
        CompletableFuture<?> future = pending.get();
        if (future != null) {
          future.cancel(true);
        }
        return Outcome.hung();
      }
    } finally {
      timer.shutdownNow();
    }
  }

  private static SdkCall blockingCall(PoliPageClient client, String method, JsonNode args)
      throws CaseFormatException {
    switch (method) {
      case "render.document" -> {
        RenderInput input = renderInput(args);
        return () -> descriptor(client.render().document(input));
      }
      case "render.pdf" -> {
        RenderInput input = renderInput(args);
        return () -> bytes(client.render().pdf(input));
      }
      case "render.pdfStream" -> {
        RenderInput input = renderInput(args);
        return () -> {
          try (InputStream pdf = client.render().pdfStream(input)) {
            return bytes(pdf.readAllBytes());
          }
        };
      }
      case "documents.get" -> {
        String id = documentId(args, 1);
        return () -> descriptor(client.documents().get(id));
      }
      case "documents.preview" -> {
        String id = documentId(args, 1);
        return () -> preview(client.documents().preview(id));
      }
      case "documents.thumbnails" -> {
        String id = documentId(args, 2);
        ThumbnailOptions options = thumbnailOptions(args);
        return () -> JSON.valueToTree(client.documents().thumbnails(id, options));
      }
      case "documents.delete" -> {
        String id = documentId(args, 1);
        return () -> {
          client.documents().delete(id);
          return NODES.nullNode(); // N-DELETE-NULL
        };
      }
      default -> throw new CaseFormatException("unknown SDK method in the contract: " + method);
    }
  }

  private static SdkCall asyncCall(
      PoliPageClient client,
      String method,
      JsonNode args,
      AtomicReference<CompletableFuture<?>> pending,
      AtomicBoolean stopRequested)
      throws CaseFormatException {
    switch (method) {
      case "render.document" -> {
        RenderInput input = renderInput(args);
        return () ->
            descriptor(await(client.renderAsync().document(input), pending, stopRequested));
      }
      case "render.pdf" -> {
        RenderInput input = renderInput(args);
        return () -> bytes(await(client.renderAsync().pdf(input), pending, stopRequested));
      }
      case "render.pdfStream" -> {
        RenderInput input = renderInput(args);
        return () -> {
          try (InputStream pdf =
              await(client.renderAsync().pdfStream(input), pending, stopRequested)) {
            return bytes(pdf.readAllBytes());
          }
        };
      }
      case "documents.get" -> {
        String id = documentId(args, 1);
        return () -> descriptor(await(client.documentsAsync().get(id), pending, stopRequested));
      }
      case "documents.preview" -> {
        String id = documentId(args, 1);
        return () -> preview(await(client.documentsAsync().preview(id), pending, stopRequested));
      }
      case "documents.thumbnails" -> {
        String id = documentId(args, 2);
        ThumbnailOptions options = thumbnailOptions(args);
        return () ->
            JSON.valueToTree(
                await(client.documentsAsync().thumbnails(id, options), pending, stopRequested));
      }
      case "documents.delete" -> {
        String id = documentId(args, 1);
        return () -> {
          await(client.documentsAsync().delete(id), pending, stopRequested);
          return NODES.nullNode(); // N-DELETE-NULL
        };
      }
      default -> throw new CaseFormatException("unknown SDK method in the contract: " + method);
    }
  }

  private static <T> T await(
      CompletableFuture<T> future,
      AtomicReference<CompletableFuture<?>> pending,
      AtomicBoolean stopRequested)
      throws Exception {
    pending.set(future);
    if (stopRequested.get()) {
      // The stop event fired before the future was handed back.
      future.cancel(true);
    }
    return future.get(CASE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
  }

  /**
   * The SDK error a call ended with. A cancelled async call throws the JDK's {@code
   * CancellationException}, whose cause is the SDK's {@code aborted} error.
   */
  private static Throwable unwrap(Throwable error) {
    Throwable current = error;
    while ((current instanceof CompletionException
            || current instanceof ExecutionException
            || (current instanceof java.util.concurrent.CancellationException
                && current.getCause() instanceof PoliPageException))
        && current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  private static void argCount(JsonNode args, int min, int max) throws CaseFormatException {
    if (!args.isArray() || args.size() < min || args.size() > max) {
      throw new CaseFormatException(
          "call.args has " + args.size() + " values, want " + min + " to " + max);
    }
  }

  private static String documentId(JsonNode args, int maxArgs) throws CaseFormatException {
    argCount(args, 1, maxArgs);
    if (!args.get(0).isTextual()) {
      throw new CaseFormatException("call.args[0] is not a document id string");
    }
    return args.get(0).asText();
  }

  private static RenderInput renderInput(JsonNode args) throws CaseFormatException {
    argCount(args, 1, 1);
    JsonNode in = args.get(0);
    RenderInput.Builder input = RenderInput.builder();
    for (Map.Entry<String, JsonNode> field : in.properties()) {
      JsonNode value = field.getValue();
      switch (field.getKey()) {
        case "project" -> input.project(value.asText());
        case "template" -> input.template(value.asText());
        case "data" -> input.data(map(value));
        case "metadata" -> input.metadata(map(value));
        case "format" -> input.format(PageFormat.valueOf(value.asText()));
        case "orientation" -> input.orientation(Orientation.fromWire(value.asText()));
        case "locale" -> input.locale(value.asText());
        default -> throw new CaseFormatException("unknown RenderInput field " + field.getKey());
      }
    }
    try {
      return input.build();
    } catch (RuntimeException e) {
      throw new CaseFormatException("call.args[0] is not a RenderInput: " + e.getMessage());
    }
  }

  private static ThumbnailOptions thumbnailOptions(JsonNode args) throws CaseFormatException {
    ThumbnailOptions.Builder options = ThumbnailOptions.builder();
    if (args.size() < 2) {
      return options.build();
    }
    for (Map.Entry<String, JsonNode> field : args.get(1).properties()) {
      JsonNode value = field.getValue();
      switch (field.getKey()) {
        case "width" -> options.width(value.asInt());
        case "format" -> options.format(ThumbnailFormat.fromWire(value.asText()));
        case "quality" -> options.quality(value.asInt());
        case "pages" -> {
          List<Integer> pages = new ArrayList<>();
          value.forEach(page -> pages.add(page.asInt()));
          options.pages(pages);
        }
        default ->
            throw new CaseFormatException("unknown ThumbnailOptions field " + field.getKey());
      }
    }
    return options.build();
  }

  private static Map<String, Object> map(JsonNode value) throws CaseFormatException {
    if (!value.isObject()) {
      throw new CaseFormatException("expected a JSON object, got " + value);
    }
    return JSON.convertValue(value, new TypeReference<Map<String, Object>>() {});
  }

  // ===================================================================
  // Normalizations (policy.normalizations)
  // ===================================================================

  /** N-DESCRIPTOR: the fields of sdk-specification.md §7.1 only. */
  private static JsonNode descriptor(Object descriptor) {
    JsonNode all = JSON.valueToTree(descriptor);
    ObjectNode out = NODES.objectNode();
    for (String field : DESCRIPTOR_FIELDS) {
      if (all.has(field)) {
        out.set(field, all.get(field));
      }
    }
    return out;
  }

  /** N-BYTES. */
  private static JsonNode bytes(byte[] data) throws NoSuchAlgorithmException {
    byte[] sha = MessageDigest.getInstance("SHA-256").digest(data);
    ObjectNode out = NODES.objectNode();
    out.put("sha256", HexFormat.of().formatHex(sha));
    out.put("length", data.length);
    return out;
  }

  /** N-PREVIEW. */
  private static JsonNode preview(DocumentPreviewResult preview) {
    ObjectNode out = NODES.objectNode();
    out.put("html", preview.html());
    out.put("pageCount", preview.pageCount());
    return out;
  }

  /** N-STATUS-NULL and N-REQID-NULL. An error that is not a PoliPageException shows in the diff. */
  private static JsonNode normalizeError(Throwable error) {
    ObjectNode out = NODES.objectNode();
    if (!(error instanceof PoliPageException e)) {
      out.put("code", "not a PoliPageException");
      out.put("message", error.getClass().getName() + ": " + error.getMessage());
      return out;
    }
    out.put("code", e.code());
    if (e.statusCode() != 0) {
      out.put("status", e.statusCode());
    } else {
      out.putNull("status");
    }
    out.put("message", e.getMessage());
    out.put("requestId", e.requestId());
    out.put("retryable", e.isRetryable());
    out.put("isAuthError", e.isAuthError());
    out.put("isRateLimitError", e.isRateLimitError());
    out.put("isValidationError", e.isValidationError());
    out.put("isNetworkError", e.isNetworkError());
    return out;
  }

  // ===================================================================
  // Comparing
  // ===================================================================

  /**
   * The recorded requests in the shape of {@code expect.requests}. A field that breaks the
   * expectation keeps the observed value, so the diff shows it.
   */
  private static JsonNode describeRequests(
      List<MockServer.Recorded> recorded, JsonNode expected, String apiKey) {
    ArrayNode out = NODES.arrayNode();
    String firstKey = null;
    for (int i = 0; i < recorded.size(); i++) {
      MockServer.Recorded request = recorded.get(i);
      JsonNode want = i < expected.size() ? expected.get(i) : NODES.objectNode();
      ObjectNode item = NODES.objectNode();
      item.put("method", request.method());
      item.put("path", request.target());

      List<String> auth = request.headers().get("authorization");
      if (auth == null) {
        item.put("auth", "none");
      } else if (auth.size() == 1 && auth.get(0).equals("Bearer " + apiKey)) {
        item.put("auth", "bearer");
      } else {
        item.put("auth", "unexpected Authorization: " + String.join(", ", auth));
      }

      String key = request.header("idempotency-key");
      if (key == null) {
        item.put("idempotencyKey", "absent");
      } else if (!UUID_V4.matcher(key).matches()) {
        item.put("idempotencyKey", "not a lowercase UUID v4: " + key);
      } else if (firstKey == null || firstKey.equals(key)) {
        firstKey = key;
        item.put("idempotencyKey", "stable");
      } else {
        item.put("idempotencyKey", "changed: " + firstKey + " then " + key);
      }

      if (want.has("json")) {
        try {
          item.set("json", JSON.readTree(request.body()));
        } catch (IOException e) {
          item.put(
              "json", "body is not JSON: " + new String(request.body(), StandardCharsets.UTF_8));
        }
      }
      if (want.has("minGapMsBefore")) {
        long min = want.get("minGapMsBefore").asLong();
        // N-DURATION-MS: the gap is compared in integer milliseconds.
        long gap =
            i == 0
                ? 0
                : Math.round((request.arrivalNanos() - recorded.get(i - 1).arrivalNanos()) / 1e6);
        if (gap >= min) {
          item.put("minGapMsBefore", min);
        } else {
          item.put("minGapMsBefore", "only " + gap + " ms");
        }
      }
      out.add(item);
    }
    return out;
  }

  /** The observed events; a delay inside an expected range is shown as that range. */
  private static JsonNode describeEvents(List<Event> observed, JsonNode expected) {
    ArrayNode out = NODES.arrayNode();
    for (int i = 0; i < observed.size(); i++) {
      Event event = observed.get(i);
      ObjectNode item = NODES.objectNode();
      item.put("type", event.type());
      item.put("attempt", event.attempt());
      if (event.type().equals("retry")) {
        long delay = Math.round(event.delayMs()); // N-DURATION-MS
        item.put("delayMs", delay);
        JsonNode want = i < expected.size() ? expected.get(i).path("delayMs") : NODES.missingNode();
        if (want.isObject()
            && delay >= want.path("min").asLong()
            && delay < want.path("max").asLong()) {
          item.set("delayMs", want);
        }
      }
      out.add(item);
    }
    return out;
  }

  private static void compare(List<String> problems, String label, JsonNode got, JsonNode want)
      throws IOException {
    String gotText = canonical(got);
    String wantText = canonical(want);
    if (!gotText.equals(wantText)) {
      problems.add(label + " differ\n  want: " + wantText + "\n  got:  " + gotText);
    }
  }

  private static String canonical(JsonNode node) throws IOException {
    Object plain = JSON.treeToValue(node, Object.class);
    return CANONICAL.writeValueAsString(plain);
  }
}
