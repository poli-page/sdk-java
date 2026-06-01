package page.poli.sdk.examples.demo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import page.poli.sdk.PoliPageClient;
import page.poli.sdk.exception.PoliPageException;
import page.poli.sdk.input.ProjectModeInput;
import page.poli.sdk.model.DocumentDescriptor;
import page.poli.sdk.model.DocumentPreviewResult;

/**
 * Walks through every public method of the Poli Page Java SDK end-to-end against the develop API.
 * See {@code examples/demo/README.md} for run instructions.
 */
public final class Main {

  private static final String DEFAULT_BASE_URL = "https://api.poli.page";
  private static final int TOTAL_STEPS = 6;
  private static final ProjectModeInput INPUT =
      ProjectModeInput.builder()
          .project("getting-started")
          .template("welcome")
          .version("1.0.0")
          .data(Map.of("name", "SDK Demo"))
          .build();

  // Repo root is passed by `scripts/demo` as a system property. `mvn exec:java`
  // does not fork — it runs in Maven's JVM with cwd = wherever the user
  // launched mvn from — so a path relative to cwd is not reliable. Fall back
  // to cwd if the property is missing (e.g. running mvn directly from the
  // demo module). Resolved at startup so the values stay stable.
  private static final Path REPO_ROOT = resolveRepoRoot();
  private static final Path ENV_FILE = REPO_ROOT.resolve(".env");
  private static final Path OUTPUT_DIR = REPO_ROOT.resolve("examples/demo/output");

  private Main() {}

  public static void main(String[] args) throws IOException {
    String apiKey = ensureApiKey();
    String baseUrl = resolveBaseUrl();
    Files.createDirectories(OUTPUT_DIR);

    PoliPageClient client = buildClient(apiKey, baseUrl);

    step1RenderPdf(client);
    step2RenderPdfStream(client);
    step3RenderToFile(client);
    DocumentDescriptor doc = step4RenderDocument(client);
    step5DocumentsPreview(client, doc.documentId());
    step6ErrorHandling(client);

    Console.println("");
    Console.println(
        Console.green("✔ ")
            + Console.bold("All steps completed.")
            + " Inspect output in "
            + Console.fileLink(OUTPUT_DIR.toString()));
  }

  private static Path resolveRepoRoot() {
    String prop = System.getProperty("poli.demo.repoRoot");
    if (prop != null && !prop.isEmpty()) {
      return Path.of(prop).toAbsolutePath().normalize();
    }
    return Path.of("").toAbsolutePath();
  }

  private static PoliPageClient buildClient(String apiKey, String baseUrl) {
    PoliPageClient.Builder b =
        PoliPageClient.builder()
            .apiKey(apiKey)
            .baseUrl(URI.create(baseUrl))
            .onRetry(
                e ->
                    Console.println(
                        Console.dim(
                            "  ↻ retrying after " + e.delay().toMillis() + "ms: " + e.reason())))
            .onError(t -> Console.println(Console.dim("  ✗ error: " + t.getClass().getSimpleName())));
    return b.build();
  }

  private static void step1RenderPdf(PoliPageClient client) throws IOException {
    Console.step(1, TOTAL_STEPS, "render().pdf() — PDF bytes in memory");
    byte[] pdf = client.render().pdf(INPUT);
    Path out = OUTPUT_DIR.resolve( "render.pdf");
    Files.write(out, pdf);
    String magic = new String(pdf, 0, Math.min(4, pdf.length));
    Console.println("  " + pdf.length + " bytes, magic: " + Console.bold(magic));
    Console.println("  " + Console.dim("open: ") + Console.fileLink(out.toString()));
  }

  private static void step2RenderPdfStream(PoliPageClient client) throws IOException {
    Console.step(2, TOTAL_STEPS, "render().pdfStream() — InputStream of PDF bytes");
    Path out = OUTPUT_DIR.resolve( "stream.pdf");
    long total;
    try (InputStream in = client.render().pdfStream(INPUT)) {
      total = Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
    }
    Console.println("  " + total + " bytes streamed");
    Console.println("  " + Console.dim("open: ") + Console.fileLink(out.toString()));
  }

  private static void step3RenderToFile(PoliPageClient client) {
    Console.step(3, TOTAL_STEPS, "renderToFile() — render straight to disk");
    Path out = OUTPUT_DIR.resolve( "file.pdf");
    client.renderToFile(INPUT, out);
    Console.println("  wrote " + out);
    Console.println("  " + Console.dim("open: ") + Console.fileLink(out.toString()));
  }

  private static DocumentDescriptor step4RenderDocument(PoliPageClient client) {
    Console.step(4, TOTAL_STEPS, "render().document() — store the document, return descriptor");
    DocumentDescriptor doc = client.render().document(INPUT);
    Console.println("  " + Console.dim("documentId: ") + Console.bold(doc.documentId()));
    Console.println("  " + Console.dim("pageCount:  ") + doc.pageCount());
    Console.println("  " + Console.dim("sizeBytes:  ") + doc.sizeBytes());
    return doc;
  }

  private static void step5DocumentsPreview(PoliPageClient client, String documentId)
      throws IOException {
    Console.step(5, TOTAL_STEPS, "documents().preview(id) — stored document HTML (no engine work)");
    DocumentPreviewResult preview = client.documents().preview(documentId);
    Path out = OUTPUT_DIR.resolve( "preview.html");
    Files.writeString(out, preview.html());
    Console.println(
        "  "
            + Console.bold(String.valueOf(preview.pageCount()))
            + " page(s), "
            + preview.html().length()
            + " chars");
    Console.println("  " + Console.dim("open: ") + Console.fileLink(out.toString()));
  }

  private static void step6ErrorHandling(PoliPageClient client) {
    Console.step(6, TOTAL_STEPS, "error handling — DEMO ONLY (we trigger an error on purpose)");
    Console.println(
        Console.yellow("  ⚠  This step is intentional — the SDK is about to throw, but the"));
    Console.println(
        Console.yellow("     demo will catch and inspect it. ")
            + Console.bold("The demo is NOT crashing."));
    Console.println(
        Console.dim(
            "     (We send an invalid version string, expecting the API to return 400"
                + " INVALID_VERSION_FORMAT.)"));
    Console.println("");

    ProjectModeInput bad =
        ProjectModeInput.builder()
            .project("getting-started")
            .template("welcome")
            .version("banana")
            .data(Map.of())
            .build();

    try {
      client.render().pdf(bad);
      Console.println(
          "  " + Console.red("✗ unexpected: the call succeeded but should have failed"));
      System.exit(1);
    } catch (PoliPageException e) {
      Console.println(
          "  " + Console.green("✔ ") + "Error caught successfully. PoliPageException exposed:");
      Console.println("     " + Console.dim("type:        ") + e.getClass().getSimpleName());
      Console.println("     " + Console.dim("code:        ") + e.code());
      Console.println("     " + Console.dim("statusCode:  ") + e.statusCode());
      Console.println("     " + Console.dim("requestId:   ") + e.requestId());
      Console.println("     " + Console.dim("isRetryable: ") + e.isRetryable());
    }
  }

  /**
   * Three-tier resolution: env var wins, then {@code .env} at the repo root, then an interactive
   * prompt that persists a valid pasted key to {@code .env} for future runs.
   */
  private static String ensureApiKey() throws IOException {
    String fromEnv = System.getenv("POLI_PAGE_API_KEY");
    if (fromEnv != null && !fromEnv.isEmpty()) {
      persistIfMissing("POLI_PAGE_API_KEY", fromEnv);
      return fromEnv;
    }

    String fromFile = EnvFile.read(ENV_FILE).get("POLI_PAGE_API_KEY");
    if (fromFile != null && !fromFile.isEmpty()) {
      Console.println(Console.dim("  using POLI_PAGE_API_KEY from " + ENV_FILE));
      return fromFile;
    }

    return promptForApiKey();
  }

  /**
   * Append {@code key=value} to {@code .env} only when the file doesn't already contain that key,
   * so the env var becomes the seed for future no-arg runs without ever overwriting a manual entry.
   */
  private static void persistIfMissing(String key, String value) throws IOException {
    String existing = EnvFile.read(ENV_FILE).get(key);
    if (existing == null || existing.isEmpty()) {
      EnvFile.append(ENV_FILE, key, value);
      Console.println(Console.dim("  saved " + key + " to " + ENV_FILE));
    }
  }

  private static String promptForApiKey() throws IOException {
    String rule =
        Console.dim("  ─────────────────────────────────────────────────────────────────────");
    Console.println("");
    Console.println(rule);
    Console.println(Console.bold(Console.yellow("   No POLI_PAGE_API_KEY found.")));
    Console.println(rule);
    Console.println("");
    Console.println("   This demo needs a test key (" + Console.cyan("pp_test_*") + ") to");
    Console.println("   talk to the Poli Page API. Test keys never bill or send real");
    Console.println("   documents.");
    Console.println("");
    Console.println(Console.bold("   How to get one:"));
    Console.println("     1. Sign in at " + Console.cyan("https://app.poli.page"));
    Console.println("     2. Go to your organization's API keys page:");
    Console.println("          " + Console.cyan("https://app.poli.page/orgs/{YOUR_ORG}/keys"));
    Console.println(
        Console.dim("        (replace {YOUR_ORG} with your org slug — visible in the"));
    Console.println(Console.dim("         dashboard URL when you're inside your organization)"));
    Console.println("     3. Click \"Create key\" and copy");
    Console.println("        the value (starts with " + Console.cyan("pp_test_") + ").");
    Console.println("");
    Console.println(
        "   Paste it below — we'll save it to " + Console.cyan(".env") + " (repo root) so");
    Console.println("   future runs pick it up automatically. (You can also set");
    Console.println(
        "   " + Console.dim("POLI_PAGE_API_KEY") + " in your shell — that wins over the file.)");
    Console.println("");

    BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    System.out.print(
        Console.bold("   Paste your pp_test_* key") + " (or Ctrl-C to cancel): ");
    String line = in.readLine();
    String key = line == null ? "" : line.trim();

    if (!key.startsWith("pp_test_")) {
      Console.println(
          "\n  " + Console.red("✗") + " Expected a key starting with `pp_test_`. Aborting.\n");
      System.exit(1);
    }

    EnvFile.append(ENV_FILE, "POLI_PAGE_API_KEY", key);
    Console.println("  " + Console.green("✔") + " saved to " + Console.cyan(ENV_FILE.toString()) + "\n");
    return key;
  }

  /**
   * Three-tier resolution for the base URL. No prompt — the default works for everyone.
   */
  private static String resolveBaseUrl() throws IOException {
    String fromEnv = System.getenv("POLI_PAGE_BASE_URL");
    if (fromEnv != null && !fromEnv.isEmpty()) {
      return fromEnv;
    }
    String fromFile = EnvFile.read(ENV_FILE).get("POLI_PAGE_BASE_URL");
    if (fromFile != null && !fromFile.isEmpty()) {
      return fromFile;
    }
    return DEFAULT_BASE_URL;
  }

  /**
   * Minimal {@code .env} parser/writer. Skips blank lines and {@code #} comments, trims, strips a
   * single matching pair of surrounding single or double quotes. Last occurrence of a key wins.
   * Dependency-free — Java has no stdlib dotenv.
   */
  static final class EnvFile {

    private EnvFile() {}

    static Map<String, String> read(Path path) throws IOException {
      Map<String, String> result = new HashMap<>();
      if (!Files.exists(path)) {
        return result;
      }
      for (String raw : Files.readAllLines(path, StandardCharsets.UTF_8)) {
        String line = raw.trim();
        if (line.isEmpty() || line.startsWith("#")) {
          continue;
        }
        int eq = line.indexOf('=');
        if (eq == -1) {
          continue;
        }
        String key = line.substring(0, eq).trim();
        String value = line.substring(eq + 1).trim();
        if (value.length() >= 2
            && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
          value = value.substring(1, value.length() - 1);
        }
        result.put(key, value);
      }
      return result;
    }

    /**
     * Append {@code key=value} to {@code path}, creating the file if absent and prepending a
     * newline if existing content does not end with one. Naive — does not de-duplicate; the
     * last-wins parse rule means the appended line takes precedence on subsequent reads.
     */
    static void append(Path path, String key, String value) throws IOException {
      String prefix = "";
      if (Files.exists(path)) {
        long size = Files.size(path);
        if (size > 0) {
          byte[] tail = new byte[1];
          try (var ch = java.nio.channels.FileChannel.open(path)) {
            ch.position(size - 1);
            ch.read(java.nio.ByteBuffer.wrap(tail));
          }
          if (tail[0] != (byte) '\n') {
            prefix = "\n";
          }
        }
      }
      Files.writeString(
          path,
          prefix + key + "=" + value + "\n",
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    }
  }

  /**
   * Tiny ANSI helper. Honors the {@code NO_COLOR} env var per the no-color.org convention; otherwise
   * always emits color. Java's {@link System#console()} returns null when launched via {@code mvn
   * exec:java}, so a TTY check would be too aggressive.
   */
  static final class Console {
    private static final boolean ENABLED = System.getenv("NO_COLOR") == null;
    private static final String ESC = "";
    private static final String RESET = ESC + "[0m";

    private Console() {}

    static void println(String s) {
      System.out.println(s);
    }

    static void step(int n, int total, String label) {
      System.out.println();
      System.out.println(bold(cyan("[" + n + "/" + total + "] " + label)));
    }

    static String dim(String s) {
      return wrap("[2m", s);
    }

    static String bold(String s) {
      return wrap("[1m", s);
    }

    static String cyan(String s) {
      return wrap("[36m", s);
    }

    static String green(String s) {
      return wrap("[32m", s);
    }

    static String yellow(String s) {
      return wrap("[33m", s);
    }

    static String red(String s) {
      return wrap("[31m", s);
    }

    static String fileLink(String path) {
      return cyan("file://" + Path.of(path).toAbsolutePath());
    }

    private static String wrap(String code, String s) {
      return ENABLED ? ESC + code + s + RESET : s;
    }
  }
}
