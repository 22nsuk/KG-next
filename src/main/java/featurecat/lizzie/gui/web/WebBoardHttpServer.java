package featurecat.lizzie.gui.web;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class WebBoardHttpServer {
  private static final int MAX_REQUEST_LINE_BYTES = 4096;
  private static final int MAX_HEADER_BYTES = 16384;
  private static final int MAX_HEADER_COUNT = 64;
  private final int port;
  private final int workers;
  private final int queuedClients;
  private final int requestTimeoutMillis;
  private volatile Run activeRun;
  private volatile int wsPort;

  private static final Map<String, String> MIME_TYPES =
      Map.of(
          "html", "text/html; charset=utf-8",
          "js", "application/javascript; charset=utf-8",
          "css", "text/css; charset=utf-8",
          "png", "image/png",
          "svg", "image/svg+xml");

  public WebBoardHttpServer(int port) {
    this(port, 4, 32, 10000);
  }

  WebBoardHttpServer(int port, int workers, int queuedClients, int requestTimeoutMillis) {
    if (workers < 1 || queuedClients < 1 || requestTimeoutMillis < 1) {
      throw new IllegalArgumentException("HTTP resource limits must be positive");
    }
    this.port = port;
    this.workers = workers;
    this.queuedClients = queuedClients;
    this.requestTimeoutMillis = requestTimeoutMillis;
  }

  public void setWsPort(int wsPort) {
    this.wsPort = wsPort;
  }

  public synchronized void start() throws IOException {
    if (activeRun != null && activeRun.running) return;
    Run run = new Run();
    activeRun = run;
    run.acceptThread.start();
  }

  public synchronized void stop() {
    Run run = activeRun;
    if (run == null) return;
    activeRun = null;
    run.shutdown();
    try {
      run.acceptThread.join(1000);
      run.clientPool.awaitTermination(1000, TimeUnit.MILLISECONDS);
      run.deadlines.awaitTermination(1000, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** Each start owns its sockets and executors; an old accept loop cannot touch a new run. */
  private final class Run {
    final ServerSocket listener = new ServerSocket(port);
    final Set<Client> clients = ConcurrentHashMap.newKeySet();
    final ThreadPoolExecutor clientPool =
        new ThreadPoolExecutor(
            workers, workers, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(queuedClients),
            r -> daemonThread(r, "WebBoardHttp-client"));
    final ScheduledThreadPoolExecutor deadlines =
        new ScheduledThreadPoolExecutor(1, r -> daemonThread(r, "WebBoardHttp-deadline"));
    final Thread acceptThread = daemonThread(() -> acceptLoop(this), "WebBoardHttp");
    volatile boolean running = true;

    Run() throws IOException {
      deadlines.setRemoveOnCancelPolicy(true);
    }

    synchronized void submit(Socket socket) {
      Client client = new Client(this, socket);
      if (!running) {
        client.close();
        return;
      }
      clients.add(client);
      try {
        socket.setSoTimeout(Math.min(5000, requestTimeoutMillis));
        // The deadline includes queue time and blocked writes, not only idle reads.
        client.deadline = deadlines.schedule(client::close, requestTimeoutMillis, TimeUnit.MILLISECONDS);
        clientPool.execute(client);
      } catch (IOException | RejectedExecutionException e) {
        // Never block the accept thread writing a response to an overloaded client.
        client.close();
      }
    }

    synchronized void shutdown() {
      if (!running) return;
      running = false;
      try {
        listener.close();
      } catch (IOException ignored) {
        // Already shutting down; all accepted sockets still need closing below.
      }
      clients.forEach(Client::close);
      clientPool.shutdownNow();
      deadlines.shutdownNow();
    }
  }

  private final class Client implements Runnable {
    private final Run owner;
    private final Socket socket;
    private volatile ScheduledFuture<?> deadline;

    Client(Run owner, Socket socket) {
      this.owner = owner;
      this.socket = socket;
    }

    @Override
    public void run() {
      try {
        handleClient(socket);
      } finally {
        close();
      }
    }

    void close() {
      try {
        socket.close();
      } catch (IOException ignored) {
        // Closing a disconnected or timed-out client is best effort.
      }
      owner.clients.remove(this);
      ScheduledFuture<?> timer = deadline;
      if (timer != null) timer.cancel(false);
    }
  }

  private static Thread daemonThread(Runnable task, String name) {
    Thread thread = new Thread(task, name);
    thread.setDaemon(true);
    return thread;
  }

  private void acceptLoop(Run run) {
    try {
      while (run.running) run.submit(run.listener.accept());
    } catch (IOException ignored) {
      // Includes listener closure on stop. Unexpected accept failure also cleans up this run.
    } finally {
      run.shutdown();
    }
  }

  private void handleClient(Socket client) {
    try (InputStream in = new BufferedInputStream(client.getInputStream());
        OutputStream out = client.getOutputStream()) {
      try {
        serve(in, out);
      } catch (HttpError e) {
        sendError(out, e.code, e.getMessage());
      }
    } catch (IOException ignored) {
      // Disconnects, read timeouts and the whole-request deadline all close the client.
    }
  }

  private void serve(InputStream in, OutputStream out) throws IOException {
    String requestLine = readLine(in, MAX_REQUEST_LINE_BYTES, 414);
    if (requestLine == null) return;
    String[] parts = requestLine.split(" ");
    if (parts.length != 3
        || !("HTTP/1.0".equals(parts[2]) || "HTTP/1.1".equals(parts[2]))) {
      throw new HttpError(400, "Bad Request");
    }
    int remaining = MAX_HEADER_BYTES;
    for (int count = 0; ; count++) {
      String header = readLine(in, remaining, 431);
      if (header == null) throw new HttpError(400, "Bad Request");
      remaining -= header.length() + 2;
      if (remaining < 0) throw new HttpError(431, "Request Header Fields Too Large");
      if (header.isEmpty()) break;
      if (count >= MAX_HEADER_COUNT || header.indexOf(':') <= 0) {
        throw new HttpError(count >= MAX_HEADER_COUNT ? 431 : 400,
            count >= MAX_HEADER_COUNT ? "Request Header Fields Too Large" : "Bad Request");
      }
    }
    if (!"GET".equalsIgnoreCase(parts[0])) {
      throw new HttpError(405, "Method Not Allowed");
    }
    String path;
    try {
      path = java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw new HttpError(400, "Bad Request");
    }
    int qIdx = path.indexOf('?');
    if (qIdx >= 0) path = path.substring(0, qIdx);
    int fIdx = path.indexOf('#');
    if (fIdx >= 0) path = path.substring(0, fIdx);
    if (!path.startsWith("/") || path.chars().anyMatch(c -> c < 32 || c == 127)) {
      throw new HttpError(400, "Bad Request");
    }
    if (path.contains("..") || path.contains("\\")) throw new HttpError(403, "Forbidden");
    if ("/".equals(path)) path = "/index.html";

    String resourcePath = "/web" + path;
    byte[] body;
    try (InputStream resource = getClass().getResourceAsStream(resourcePath)) {
      if (resource == null) throw new HttpError(404, "Not Found");
      body = resource.readAllBytes();
    }
    if (resourcePath.endsWith("index.html")) {
      String html = new String(body, StandardCharsets.UTF_8);
      html = html.replace("__WS_PORT__", String.valueOf(wsPort));
      body = html.getBytes(StandardCharsets.UTF_8);
    }
    String ext = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : "";
    String contentType = MIME_TYPES.getOrDefault(ext, "application/octet-stream");
    String header =
        "HTTP/1.1 200 OK\r\n"
            + "Content-Type: " + contentType + "\r\n"
            + "Content-Length: " + body.length + "\r\n"
            + "Access-Control-Allow-Origin: *\r\n"
            + "Connection: close\r\n\r\n";
    out.write(header.getBytes(StandardCharsets.US_ASCII));
    out.write(body);
    out.flush();
  }

  private static String readLine(InputStream in, int maxBytes, int overflowCode) throws IOException {
    StringBuilder line = new StringBuilder();
    while (true) {
      int value = in.read();
      if (value < 0) {
        if (line.length() == 0) return null;
        throw new HttpError(400, "Bad Request");
      }
      if (value == '\n') {
        int length = line.length();
        if (length > 0 && line.charAt(length - 1) == '\r') line.setLength(length - 1);
        return line.toString();
      }
      if (line.length() >= maxBytes) {
        throw new HttpError(overflowCode,
            overflowCode == 414 ? "URI Too Long" : "Request Header Fields Too Large");
      }
      line.append((char) value);
    }
  }

  private static final class HttpError extends IOException {
    private static final long serialVersionUID = 1L;
    final int code;

    HttpError(int code, String message) {
      super(message);
      this.code = code;
    }
  }

  private void sendError(OutputStream out, int code, String message) throws IOException {
    String body = "<h1>" + code + " " + message + "</h1>";
    String response =
        "HTTP/1.1 " + code + " " + message + "\r\n"
            + "Content-Type: text/html\r\n"
            + "Content-Length: " + body.length() + "\r\n"
            + "Connection: close\r\n\r\n" + body;
    out.write(response.getBytes(StandardCharsets.UTF_8));
    out.flush();
  }

  public int getPort() {
    Run run = activeRun;
    return run == null ? port : run.listener.getLocalPort();
  }
}
