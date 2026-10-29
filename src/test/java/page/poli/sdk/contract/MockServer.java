package page.poli.sdk.contract;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/**
 * A local HTTP/1.1 server on a real socket, answering scripted replies in arrival order and
 * recording every request. Faults are what a client can meet in production: {@link Drop} closes the
 * connection after the request arrived, without a byte of answer; {@link Hang} never answers;
 * {@link Answer#breakBodyAfter} closes the connection part-way through the body; {@link
 * Answer#dripMillis} sends the body one byte at a time.
 *
 * <p>Connections are kept alive between answers, as a production server does, so that a client
 * reusing a pooled connection meets the same failures there.
 */
public final class MockServer implements AutoCloseable {

  /** What the server does with one request. */
  public sealed interface Reply permits Answer, Drop, Hang {}

  /**
   * An HTTP answer.
   *
   * @param status the status code
   * @param headers response headers, in order
   * @param body the body
   * @param breakBodyAfter close the connection after this many body bytes, or -1 to send it all
   * @param dripMillis pause between body bytes, or 0 to send it at once
   */
  public record Answer(
      int status, Map<String, String> headers, byte[] body, int breakBodyAfter, long dripMillis)
      implements Reply {

    public Answer(int status, Map<String, String> headers, byte[] body) {
      this(status, headers, body, -1, 0);
    }
  }

  /** Close the connection without answering. */
  public record Drop() implements Reply {}

  /** Never answer; the connection stays open until the client or the server closes it. */
  public record Hang() implements Reply {}

  /** Chooses the reply to the request with this 0-based arrival index. */
  @FunctionalInterface
  public interface Script {
    Reply reply(int index, String origin);
  }

  /**
   * One request as it arrived.
   *
   * @param method the method
   * @param target the request target, query included, as sent
   * @param headers the headers, names lowercased
   * @param body the body bytes
   * @param arrivalNanos {@link System#nanoTime()} when the request line arrived
   * @param connection 1-based number of the connection it came on
   */
  public record Recorded(
      String method,
      String target,
      Map<String, List<String>> headers,
      byte[] body,
      long arrivalNanos,
      int connection) {

    public @Nullable String header(String name) {
      List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
      return values == null ? null : String.join(", ", values);
    }
  }

  private static final Map<Integer, String> REASONS =
      Map.ofEntries(
          Map.entry(200, "OK"),
          Map.entry(204, "No Content"),
          Map.entry(400, "Bad Request"),
          Map.entry(401, "Unauthorized"),
          Map.entry(403, "Forbidden"),
          Map.entry(404, "Not Found"),
          Map.entry(410, "Gone"),
          Map.entry(413, "Content Too Large"),
          Map.entry(429, "Too Many Requests"),
          Map.entry(500, "Internal Server Error"),
          Map.entry(502, "Bad Gateway"),
          Map.entry(503, "Service Unavailable"),
          Map.entry(504, "Gateway Timeout"));

  private final ServerSocket listener;
  private final Script script;
  private final String origin;
  private final List<Recorded> requests = Collections.synchronizedList(new ArrayList<>());
  private final List<String> problems = Collections.synchronizedList(new ArrayList<>());
  private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
  private final AtomicInteger connections = new AtomicInteger();
  private final Object arrivalOrder = new Object();
  private volatile boolean closed;

  private MockServer(Script script) throws IOException {
    this.listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
    this.script = script;
    this.origin = "http://127.0.0.1:" + listener.getLocalPort();
    Thread acceptor = new Thread(this::acceptLoop, "mock-server-accept");
    acceptor.setDaemon(true);
    acceptor.start();
  }

  /**
   * @param script the replies
   * @return a started server
   */
  public static MockServer start(Script script) {
    try {
      return new MockServer(script);
    } catch (IOException e) {
      throw new IllegalStateException("cannot start the mock server", e);
    }
  }

  /**
   * @param replies the replies, in arrival order; a request past the last gets {@code 599}
   * @return a started server
   */
  public static MockServer start(List<Reply> replies) {
    return start(
        (index, origin) ->
            index < replies.size()
                ? replies.get(index)
                : new Answer(
                    599,
                    Map.of("Content-Type", "application/json"),
                    "{\"error\":\"MOCK_EXHAUSTED\"}".getBytes(StandardCharsets.UTF_8)));
  }

  /**
   * @return the origin, e.g. {@code http://127.0.0.1:41234}
   */
  public String origin() {
    return origin;
  }

  /**
   * @return the requests received so far, in arrival order
   */
  public List<Recorded> requests() {
    synchronized (requests) {
      return List.copyOf(requests);
    }
  }

  /**
   * @return what the server could not do as scripted
   */
  public List<String> problems() {
    synchronized (problems) {
      return List.copyOf(problems);
    }
  }

  @Override
  public void close() {
    closed = true;
    try {
      listener.close();
    } catch (IOException ignored) {
      // closing
    }
    for (Socket socket : sockets) {
      try {
        socket.close();
      } catch (IOException ignored) {
        // closing
      }
    }
  }

  private void acceptLoop() {
    while (!closed) {
      Socket socket;
      try {
        socket = listener.accept();
      } catch (IOException e) {
        return;
      }
      sockets.add(socket);
      int connection = connections.incrementAndGet();
      Thread worker = new Thread(() -> serve(socket, connection), "mock-server-" + connection);
      worker.setDaemon(true);
      worker.start();
    }
  }

  private void serve(Socket socket, int connection) {
    try (socket) {
      InputStream in = socket.getInputStream();
      OutputStream out = socket.getOutputStream();
      while (!closed) {
        String requestLine = readLine(in);
        if (requestLine == null) {
          return;
        }
        if (requestLine.isEmpty()) {
          continue;
        }
        long arrival = System.nanoTime();
        String[] parts = requestLine.split(" ", 3);
        if (parts.length != 3) {
          problem("malformed request line: " + requestLine);
          return;
        }
        Map<String, List<String>> headers = readHeaders(in);
        byte[] body = readBody(in, headers);
        if (body == null) {
          return;
        }
        int index;
        synchronized (arrivalOrder) {
          index = requests.size();
          requests.add(new Recorded(parts[0], parts[1], headers, body, arrival, connection));
        }
        Reply reply = script.reply(index, origin);
        if (reply instanceof Drop) {
          return;
        }
        if (reply instanceof Hang) {
          // Discard anything else the client sends until it, or the server, closes.
          while (in.read() != -1) {
            // keep waiting
          }
          return;
        }
        if (!answer(out, (Answer) reply)) {
          return;
        }
      }
    } catch (SocketException closedByPeerOrServer) {
      // the client or close() ended the connection
    } catch (IOException | InterruptedException e) {
      if (!closed) {
        problem("connection " + connection + ": " + e);
      }
    }
  }

  /** Write an answer; false when the connection must close. */
  private boolean answer(OutputStream out, Answer answer) throws IOException, InterruptedException {
    StringBuilder head = new StringBuilder();
    head.append("HTTP/1.1 ")
        .append(answer.status())
        .append(' ')
        .append(REASONS.getOrDefault(answer.status(), "Status"))
        .append("\r\n");
    answer
        .headers()
        .forEach((name, value) -> head.append(name).append(": ").append(value).append("\r\n"));
    boolean noBody = answer.status() == 204 || answer.status() == 304;
    if (!noBody) {
      head.append("Content-Length: ").append(answer.body().length).append("\r\n");
    }
    head.append("\r\n");
    out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
    out.flush();
    if (noBody) {
      return true;
    }
    byte[] body = answer.body();
    int limit =
        answer.breakBodyAfter() >= 0 ? Math.min(answer.breakBodyAfter(), body.length) : body.length;
    if (answer.dripMillis() > 0) {
      for (int i = 0; i < limit; i++) {
        out.write(body[i]);
        out.flush();
        TimeUnit.MILLISECONDS.sleep(answer.dripMillis());
      }
    } else {
      out.write(body, 0, limit);
      out.flush();
    }
    return limit == body.length;
  }

  private void problem(String text) {
    problems.add(text);
  }

  private static @Nullable String readLine(InputStream in) throws IOException {
    ByteArrayOutputStream line = new ByteArrayOutputStream();
    int c;
    while ((c = in.read()) != -1) {
      if (c == '\n') {
        byte[] bytes = line.toByteArray();
        int length =
            bytes.length > 0 && bytes[bytes.length - 1] == '\r' ? bytes.length - 1 : bytes.length;
        return new String(bytes, 0, length, StandardCharsets.ISO_8859_1);
      }
      line.write(c);
    }
    return line.size() == 0 ? null : line.toString(StandardCharsets.ISO_8859_1);
  }

  private static Map<String, List<String>> readHeaders(InputStream in) throws IOException {
    Map<String, List<String>> headers = new LinkedHashMap<>();
    String line;
    while ((line = readLine(in)) != null && !line.isEmpty()) {
      int colon = line.indexOf(':');
      if (colon <= 0) {
        continue;
      }
      String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
      headers.computeIfAbsent(name, k -> new ArrayList<>()).add(line.substring(colon + 1).trim());
    }
    return headers;
  }

  private byte @Nullable [] readBody(InputStream in, Map<String, List<String>> headers)
      throws IOException {
    List<String> encoding = headers.get("transfer-encoding");
    if (encoding != null) {
      problem("this mock reads Content-Length bodies only, got Transfer-Encoding " + encoding);
      return null;
    }
    List<String> length = headers.get("content-length");
    if (length == null) {
      return new byte[0];
    }
    int n = Integer.parseInt(length.get(0));
    byte[] body = in.readNBytes(n);
    return body.length == n ? body : null;
  }
}
