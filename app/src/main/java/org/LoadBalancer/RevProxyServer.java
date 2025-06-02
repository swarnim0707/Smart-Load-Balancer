package org.LoadBalancer;

import java.io.*;
import java.net.*;
import java.lang.*;
import java.util.Map;
import BackendGraph.*;
import java.nio.file.Files;
import java.nio.file.*;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.*;
import ConfigLoader.ConfigLoader;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicInteger;

public class RevProxyServer {
    private static final int PORT = 8080;
    private static final String WEB_ROOT = "public";
    private static final Lock lock = new ReentrantLock();
    private static final String configPath = "src/main/java/org/LoadBalancer/config.json";
    private static Map<String, AtomicInteger> roundRobinCounters = new ConcurrentHashMap<>();
    private static volatile RouteTrie routeTrie = new RouteTrie();
    private static volatile BackendGraph backendGraph = new BackendGraph();
    private static ConfigLoader configLoader = new ConfigLoader(configPath, routeTrie, backendGraph);

    public static void main(String[] args) throws IOException {

        // Observes the config file for any change
        Thread watcherThread = new Thread(new ConfigWatcher(configLoader, configPath, routeTrie));
        watcherThread.setDaemon(true); // Won't block JVM exit
        watcherThread.start();

        // Build trie with the config for route prefix matching
        Set<String> configRoutes = configLoader.getAllRoutes();
        for(String route: configRoutes) {
            routeTrie.insertRoute(route);
        }

        // Build network graph for routing
        buildGraph(backendGraph, configLoader);

        // Scheduled thread to update node metrics in the network graph
        ScheduledExecutorService metricsUpdater = Executors.newSingleThreadScheduledExecutor();
        metricsUpdater.scheduleAtFixedRate(() -> backendGraph.updateMetrics(), 0, 5, TimeUnit.SECONDS);

        // Bind to a port
        try (ServerSocket serverSocket = new ServerSocket(PORT);) {
            serverSocket.setSoTimeout(60000);

            System.out.println("Listening on port: " + PORT);

            // Creating a thread-pool for handling requests 
            ExecutorService ioPool = Executors.newFixedThreadPool(4);
            ExecutorService reverseProxyPool = Executors.newFixedThreadPool(8);
            for (String route: configLoader.getAllRoutes()) {
                roundRobinCounters.put(route, new AtomicInteger(0));
            }

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("Shutting down");
                ioPool.shutdownNow();
                reverseProxyPool.shutdownNow();
            }));

            // Listen for incoming connections and accept it
            while(true) {
                try {
                    Socket clientSocket = serverSocket.accept();
                        ioPool.submit(() -> {
                            System.out.println("IP Address: " + clientSocket.getInetAddress() + " connected to the server");
                            handleClient(clientSocket, reverseProxyPool);
                    });
                } catch (SocketTimeoutException e) {
                    System.out.println("Timed out waiting for a client to connect.");
                    break;
                } catch(SocketException e) {
                    System.out.println("Error occurred while trying to connect with the client Socket");
                    break;
                }
            }
            ioPool.shutdown();
            reverseProxyPool.shutdown();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void buildGraph(BackendGraph backendGraph, ConfigLoader configLoader) {
        Map<String, List<String>> routeToBackendMap = configLoader.getConfig();
        for(String key: routeToBackendMap.keySet()) {
            List<String> backends = routeToBackendMap.get(key);
            for(String backend: backends) backendGraph.addNode(new Node(backend));
        }
        Node src = new Node("LoadBalancer");
        backendGraph.addNode(src);
        backendGraph.source = src;
    }

    private static String getNextBackend(String route) {
        // Fetch list of backend servers for the given route
        List<String> backends = configLoader.getBackends(route);
        if(backends.isEmpty()) return null;

        // Fetch the current index counter for the route
        AtomicInteger counter = roundRobinCounters.computeIfAbsent(route, new AtomicInteger(0));
        if(counter == null) return null;

        // Atomically get and increment the counter
        int index = counter.getAndUpdate(i -> (i+1)%backends.size());
        return backends.get(index);
    }

    private static void handleClient(Socket clientSocket, ExecutorService reverseProxyPool) {

        // Reading the input stream from the client socket
        try (
            // Get the input stream from the socket, convert the byte stream to characters
            // and wrap it with BufferedReader for more efficient reading
            InputStream inputStream = clientSocket.getInputStream();
            InputStreamReader in = new InputStreamReader(inputStream);
            BufferedReader bufferedReader = new BufferedReader(in);
            OutputStream out = clientSocket.getOutputStream();
        ) {
            clientSocket.setSoTimeout(600000);
            while(true) {
                String requestLine;
                try {
                    requestLine = bufferedReader.readLine();
                } catch (SocketTimeoutException e) {
                    System.out.println("Timeout — closing idle connection");
                    break;
                }

                if(requestLine ==  null) {
                    httpError(out, 408, "Request Timeout");
                    continue;
                }

                // // Parse headers till the Connection header
                StringBuilder request = new StringBuilder(requestLine);
                String line;
                Boolean keep_alive = true;
                while(true) {
                    line = bufferedReader.readLine();
                    request.append(line).append("\r\n");
                    if(line.toLowerCase().startsWith("connection")) {
                        keep_alive = !line.split(": ")[1].toLowerCase().contains("close");
                        break;
                    }
                    if(line.isEmpty() || line == null) break;          
                }

                String[] tokens = requestLine.split(" ");
                String httpVersion = tokens[2];
                String path = tokens[1];
                if(path.equals("/")) path = "/index.html";

                // Read the file based on the route in the HTTP request
                File file = new File(WEB_ROOT, path);

                if(!requestLine.startsWith("GET") || !file.exists() || file.isDirectory()) {
                    String route;
                    synchronized(routeTrie) {
                        route = routeTrie.longestRoutePrefix(path);
                    }
                    
                    if(route == null) {
                        httpError(out, 404, "Invalid route");
                        continue;
                    }

                    reverseProxyPool.submit(() -> {
                        String requestPart = new String(request);
                        reverseProxy(out, inputStream, requestPart, route);
                    });

                    if(keep_alive) continue;
                    else break;
                }

                byte[] fileContent = null;
                if(lock.tryLock(1, TimeUnit.SECONDS)) {
                    try {
                        fileContent = Files.readAllBytes(file.toPath());
                    } finally {
                        lock.unlock();
                    }
                }
                else {
                    httpError(out, 503, "Service Unavailable");
                    if(!keep_alive) break;
                    continue;
                }

                String responseHeader = httpVersion + " 200 OK\r\n" +
                                        "Content-Length: " + fileContent.length + "\r\n" +
                                        "Content-Type: " + guessMIMEType(file.getName()) + "\r\n" +
                                        (keep_alive ? "Connection: keep-alive\r\n" : "Connection: close\r\n") +
                                        "\r\n";

                out.write(responseHeader.getBytes());
                out.write(fileContent);
                out.flush();
                if(!keep_alive) break;
            }
            
        } catch(IOException | InterruptedException e) {
            System.err.println("Error handling client: " + e.getMessage());
        } finally {
            try {
                clientSocket.close();
            } catch(IOException ignored) {}
        }
    }

    private static void httpError(OutputStream out, int errorCode, String errorMessage) throws IOException {
        String response = "HTTP/1.0 " + errorCode + " " + errorMessage + "\r\n\r\n<html><body><h1>" +
                            errorMessage + "</h1></body></html>";
        try {
            out.write(response.getBytes());
            out.flush();
        } catch (IOException e) {
            System.err.println("Failed to send error to client: " + e.getMessage());
        }
    }

    private static String guessMIMEType(String filename) {
        if (filename.endsWith(".html") || filename.endsWith(".htm")) return "text/html";
        if (filename.endsWith(".jpg") || filename.endsWith(".jpeg")) return "image/jpeg";
        if (filename.endsWith(".png")) return "image/png";
        if (filename.endsWith(".css")) return "text/css";
        if (filename.endsWith(".js")) return "application/javascript";
        return "application/octet-stream";
    }

    private static String[] pickBackendGraph(String route) {
        List<String> candidates = configLoader.getBackends(route);

        Set<Node> backendTargets = new HashSet<>();
        for(String candidate: candidates) {
            backendTargets.add(backendGraph.getNodeFromString(candidate));
        }

        List<Node> backendPath = backendGraph.findShortestPath(backendGraph.source, backendTargets, 2.0, 0.5, 5.0, 10.0);

        if(backendPath.isEmpty()) {
            return null;
        }

        Node firstHop = backendPath.get(1);

        String forwardPathHeader = String.join(",", 
            backendPath.stream().skip(1).map(n -> n.server).toArray(String[]::new));

        firstHop.qLen.incrementAndGet();

        return new String[]{firstHop.server, forwardPathHeader};
    }

    private static void reverseProxy(OutputStream clientWriter, InputStream inputStream, String request, String route) {
        final String backend;
        final String forwardPathHeader;
        final Node firstHop;

        try {
            String[] graphBackends = pickBackendGraph(route);
            if(graphBackends == null) {
                throw new RuntimeException("Graph routing failed");
            }
            backend = graphBackends[0];
            forwardPathHeader = graphBackends[1];
            firstHop = backendGraph.getNodeFromString(backend);
        } catch(Exception e) {
            backend = getNextBackend(route);
            forwardPathHeader = "";
            firstHop = null;
        }    

        if(backend == null) {
            httpError(clientWriter, 503, "No backends available for this route");
            return;
        }

        String[] backendHostPort = backend.split(":");
        String backendHost = backendHostPort[0];
        int backendPort;

        try {
            backendPort = Integer.parseInt(backendHostPort[1]);
        } catch (NumberFormatException e) {
            System.err.println("Invalid backend port value: " + backendHostPort + " " + e.getMessage());
            httpError(clientWriter, 500, "Internal Server Error");
            return;
        }
        
        try (
            Socket backendSocket = new Socket(backendHost, backendPort);
            OutputStream backendOut = backendSocket.getOutputStream();
            InputStream backendIn = backendSocket.getInputStream();
        ) {
            Thread toBackend = new Thread(() -> {
                // Write the request bytes to the backend output stream
                try {
                    backendOut.write((request).getBytes());
                    if(!forwardPathHeader.equals("")) backendOut.write(("X-Forward-Path: " + forwardPathHeader + "\r\n").getBytes());
                    forwardStream(inputStream, backendOut);
                } catch (IOException e) {
                    System.err.println("Error sending request to backend: " + backendHost + " " + e.getMessage());
                } finally {
                    if(firstHop != null) firstHop.qLen.decrementAndGet();
                }
                
            });

            Thread toClient = new Thread(() -> {
                // Receive the response from the backend and write to the client output stream
                try {
                    forwardStream(backendIn, clientWriter);
                } catch (IOException e) {
                    System.err.println("Error sending response to client: " + e.getMessage());
                }
                
            });

            toBackend.start();
            toClient.start();
            toBackend.join();
            toClient.join();
        } catch (IOException e) {
            try {
                httpError(clientWriter, 502, "Bad Gateway");
            } catch (IOException ignored) {}
            System.err.println("Error proxying to backend: " + e.getMessage());
        } catch (InterruptedException e) {
            System.err.println("Proxy thread interrupted: " + e.getMessage());
        }
    }

    private static void forwardStream(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = in.read(buffer)) != -1) {
            out.write(buffer, 0, bytesRead);
        }
        out.flush();
    }
}

class RouteTrieNode {
    Map<String, RouteTrieNode> children = new HashMap<>();
    Boolean isEndOfRoute = false;
}

public class RouteTrie {
    private final RouteTrieNode root;

    public RouteTrie() {
        root = new RouteTrieNode();
    }

    public void clear() {
        root.children.clear(); // Recursively GC-ed if nothing references the nodes
    }

    public void load(String configPath) {
        // Build trie with the config for route prefix matching
        ConfigLoader configLoader = new ConfigLoader(configPath, routeTrie, backendGraph);
        Set<String> configRoutes = configLoader.getAllRoutes();
        for(String route: configRoutes) {
            insertRoute(route);
        }
    }

    public void insertRoute(String route) {
        String[] routeSegments = route.split("/");
        RouteTrieNode node = root;

        for(String segment: routeSegments) {
            if(segment.isEmpty()) continue;

            node.children.putIfAbsent(segment, new RouteTrieNode());
            node = node.children.get(segment);
        }
        node.isEndOfRoute = true;
    }

    public String longestRoutePrefix(String path) {
        String[] routeSegments = path.split("/");
        RouteTrieNode node = root;
        StringBuilder currentPrefix = new StringBuilder();
        StringBuilder longestMatchedPrefix = new StringBuilder();

        for(String segment: routeSegments) {
            if(segment.isEmpty()) continue;
            if(!node.children.containsKey(segment)) break;

            currentPrefix.append("/").append(segment);
            node = node.children.get(segment);

            if (node.isEndOfRoute) {
                longestMatchedPrefix.setLength(0); // reset
                longestMatchedPrefix.append(currentPrefix);
            }
        }
        return longestMatchedPrefix.length() == 0 ? null : longestMatchedPrefix.toString();
    }
}