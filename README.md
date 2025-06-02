# Java Reverse Proxy & Smart Load Balancer

A high-performance reverse proxy and load balancer written in Java. Supports static file serving, trie-based route matching, graph-driven adaptive routing, and round-robin fallback. Configuration is hot-reloadable, and per-node metrics (health, latency, bandwidth, queue length) feed into a weighted Dijkstra pathfinding algorithm to select an optimal backend.

---

## Features

* **HTTP Reverse Proxy**

  * Listens on port 8080 by default.
  * Serves static files from a configurable `public/` directory.
  * Proxies dynamic routes (e.g. `/api/`, `/auth/`) to backend servers.

* **Trie-Based Route Matching**

  * Maintains a prefix trie of configured routes for longest-prefix matching.
  * Efficient lookup: incoming request path is matched to the most specific configured prefix with O(L) time (L = number of path segments).

* **Hot-Reloadable Configuration**

  * Reads a JSON config mapping route prefixes to backend lists (e.g. `"/api/": ["localhost:9001","localhost:9002"]`).
  * Uses Java NIO’s `WatchService` to monitor `config.json`.
  * On file change: reloads route trie and backend mappings without restarting the server.

* **Graph-Driven Smart Routing**

  * Represents the load balancer and all backend servers as nodes in a fully connected graph.
  * Periodically (every 5 seconds) collects per-backend metrics:

    * **Health**: TCP connect success/failure (1.0 or 0.0).
    * **Latency**: Simulated by measuring a quick TCP connect round-trip to each backend.
    * **Bandwidth**: Simulated by timing a small write to the backend.
    * **Queue Length**: Number of in-flight requests assigned to that backend’s first-hop.
  * Each edge’s cost is computed as:

    ```
    cost = α·latency 
         + β·(1 / bandwidth) 
         + γ·queueLength 
         + δ·(1 – health)
    ```

    where α, β, γ, δ are tunable weights.
  * Runs Dijkstra’s algorithm to find the minimal-cost path from the LB node to any candidate backend.
  * Forwards the request to the first hop on that path and injects an `X-Forward-Path` header listing the full path of servers.

* **Round-Robin Fallback**

  * If graph-based pathfinding fails (no reachable backend or all health=0), falls back to a simple round-robin selector per route.
  * Guarantees even distribution when smart routing is not applicable.

* **Bidirectional Streaming**

  * For each proxied request, spawns two threads:

    1. **Client → Backend**: copies request bytes (including optional `X-Forward-Path` header) to the backend socket.
    2. **Backend → Client**: copies response bytes back to the client.
  * Ensures full-duplex communication (for chunked transfers or streaming endpoints).

* **Connection Keep-Alive**

  * Parses `Connection:` header to detect `keep-alive` vs `close`.
  * Respects `keep-alive` by looping back to read subsequent requests on the same socket.
  * If no request arrives within a configurable timeout (600 000 ms), closes the idle connection.

* **Graceful Shutdown**

  * Registers a JVM shutdown hook to call `shutdownNow()` on all thread pools.
  * Ensures no new connections are accepted once shutdown is initiated.

---

## Architecture

**1. Entry Point / Main Loop**

* `RevProxyServer.main(...)` initializes:

  * A `ServerSocket` listening on port 8080.
  * Two `ExecutorService` pools:

    * **ioPool** (fixed thread pool, size 4) handles accepted client connections.
    * **reverseProxyPool** (fixed thread pool, size 8) handles actual proxy tasks.
  * A trie (`RouteTrie`) built from the current `config.json`.
  * A fully connected `BackendGraph` containing:

    * Node `"LoadBalancer"` plus one node per backend URI.
    * Edges linking every pair of nodes.

* A scheduled task (`metricsUpdater`) runs every 5 seconds:

  * Calls `updateMetrics()` on `BackendGraph` to refresh health, latency, bandwidth, and queue-length metrics.

* For each incoming `Socket clientSocket`:

  * `handleClient(...)` is submitted to `ioPool`.
  * Inside `handleClient(...)`:

    * Reads HTTP request line+headers (supports keep-alive looping).
    * If path maps to a static file, serves it directly (with `Files.readAllBytes`).
    * Otherwise, finds the longest route prefix via `routeTrie.longestRoutePrefix(path)`.
    * Submits a proxy task to `reverseProxyPool`, passing `clientWriter`, `clientReader`, full request, and route prefix.

**2. Proxy Task Flow (`reverseProxy(...)`)**

* Attempt **Graph-Based Routing**:

  1. `pickBackendGraph(route)`:

     * Retrieves candidate URIs from `configLoader.getBackends(route)`.
     * Converts each URI string to a `Node` via `backendGraph.getNodeFromString(...)`.
     * Calls `findShortestPath(backendGraph.source, candidates, α,β,γ,δ)`.
     * If path is non-empty:

       * First-hop = `path[1]`.
       * Builds `X-Forward-Path` header by joining `path.skip(1)`.
       * Increments `firstHop.qLen`.
       * Returns `[firstHopUri, forwardPathHeader]`.
     * If no valid path, returns `null`.

* If graph route returned `null`, fallback to **Round-Robin**:

  * `getNextBackend(route)` returns the next URI string in a thread-safe circular counter.

* Split `backendUri` into `host` and `port`, open a `Socket backendSocket = new Socket(host, port)`, then spawn two `Thread`s:

  * **Thread A (Client→Backend)**:

    * Writes the stored request bytes.
    * If `forwardPathHeader` is non-empty, writes `X-Forward-Path: <header>\r\n`.
    * Calls `forwardStream(clientReader, backendOut)` to copy any request body.
    * In its `finally`, decrements `firstHop.qLen` if it was a graph-based route.
  * **Thread B (Backend→Client)**:

    * Calls `forwardStream(backendIn, clientWriter)` to copy the response back.
  * `toBackend.start()` and `toClient.start()`, then `join()` both.

* If any `IOException` or `InterruptedException` occurs during streaming, sends a `502 Bad Gateway` or logs the interruption.

**3. Data Structures**

* **`RouteTrie`**: Each node has a `Map<String,RouteTrieNode>` for child segments and a boolean `isEndOfRoute`.

  * `insertRoute("/api/users")` splits on `'/'`, inserts `"api"` then `"users"`, marking the final node.
  * `longestRoutePrefix("/api/users/123")` walks segments until no child exists, returning the last matched prefix.

* **`BackendGraph`**:

  * `Map<Node,List<Edge>> graph` stores adjacency lists.
  * Each `Node` wraps a `String server` (e.g. `"localhost:9001"`), an `AtomicInteger qLen`, and a `double health`.
  * Each `Edge` has `Node source`, `Node target`, `double latency`, `double bandwidth`, and a computed `cost`.
  * `updateMetrics()` iterates all nodes (skipping the `"LoadBalancer"` node) to:

    * `checkServerHealth(...)`: attempts a TCP connect with 200 ms timeout → sets `health` to 1.0 or 0.0.
    * For each outgoing `Edge`, calls:

      * `measureLatencyFromLB(edge.target)`: times a TCP connect (500 ms timeout) → returns ms or `Double.MAX_VALUE`.
      * `measureBandwidthFromLB(edge.target)`: opens a 500 ms TCP connect, writes 512 bytes→times/write speed.
  * `computeCost(edge, α,β,γ,δ)` returns `α·latency + β·(1/bandwidth) + γ·queueLength + δ·(1 – health)`.
  * `findShortestPath(source, targets, α,β,γ,δ)` implements Dijkstra’s algorithm over this weighted graph.
  * `constructSrcToTargetPath(...)` reconstructs the path by backtracking a `Map<Node,Node> prev`.

---

## Prerequisites

* **Java 11** (or newer)
* **Git** (for cloning)
* **Gradle Wrapper** (included in this repo)

Ensure `JAVA_HOME` is set to a JDK 11+ installation in your environment.

---

## Configuration

Create or update the JSON file at `src/main/java/org/LoadBalancer/config.json`. It should map route prefixes to arrays of backend `host:port` strings. For example:

```json
{
  "/api/":      ["localhost:9001", "localhost:9002", "localhost:9003"],
  "/auth/":     ["localhost:9005", "localhost:9006"],
  "/api/users": ["localhost:8001", "localhost:8002"],
  "/api/posts": ["localhost:8003", "localhost:8004"]
}
```

* **Keys** must begin and end with `/`.
* Each **value** is a list of backend URIs (no `http://` prefix).
* Incoming paths are matched by longest-prefix; e.g. `/api/users/123` matches `/api/users` over `/api/`.

---

## Building & Running

1. **Clone the repository**

   ```bash
   git clone https://github.com/<your-username>/<repo-name>.git
   cd <repo-name>
   ```

2. **Build with Gradle**

   ```bash
   ./gradlew clean build
   ```

   This will compile all classes and package an executable JAR under `build/libs/`.

3. **Run the Proxy**

   ```bash
   ./gradlew run
   ```

   or directly:

   ```bash
   java -jar build/libs/<project-name>.jar
   ```

   The proxy will start listening on `http://localhost:8080`.

---

## Example Usage

1. **Start Dummy Backends**
   In separate terminals, run a simple HTTP server on each port:

   ```bash
   python3 -m http.server 9001
   python3 -m http.server 9002
   python3 -m http.server 9003
   python3 -m http.server 9004
   ```

2. **Send a Request**

   ```bash
   curl -i http://localhost:8080/api/
   ```

   * The load balancer selects a backend using graph-based Dijkstra (or round-robin fallback).
   * The chosen backend receives the request with an extra header:

     ```
     X-Forward-Path: localhost:900X,localhost:900Y,...
     ```
   * The backend’s response (e.g. directory listing) is relayed back to `curl`.

3. **Static File Test**
   Place an `index.html` in the `public/` folder (project root). Then:

   ```bash
   curl -i http://localhost:8080/
   ```

   * You should see the contents of `public/index.html`.

4. **Hot-Reload Test**

   * Modify `config.json` (e.g. add `"/status/": ["localhost:9005"]`).
   * Save the file.
   * In the proxy’s console, you’ll see “Config file modified, reloading…”.
   * Now:

     ```bash
     curl -i http://localhost:8080/status/
     ```

     should be routed to `localhost:9005`.

---

## Building & Running Commands (Gradle)

```bash
# Clean previous builds
./gradlew clean

# Compile & run tests (if any)
./gradlew test

# Build the JAR
./gradlew build

# Run the application via Gradle
./gradlew run

# Or, run the generated JAR directly:
java -jar build/libs/<project-name>.jar
```

* Replace `<project-name>` with the actual JAR filename (check `build/libs/` after the build).
* All dependencies are managed by Gradle; no external setup needed beyond Java 11.

---

## Contributing

1. Fork the repository.
2. Create a feature branch (`git checkout -b feature/your-feature`).
3. Make changes, add tests/documentation, and commit (`git commit -m "Add <feature>"`).
4. Push to your fork (`git push origin feature/your-feature`).
5. Open a Pull Request against `main`, describing the changes and tests.

---

## License

This project is licensed under the MIT License. See `LICENSE` for details.