package org.LoadBalancer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.HashSet;
import java.util.PriorityQueue;
import java.util.HashMap;
import java.util.Collections;

class Node {
    String server = "";
    AtomicInteger qLen = new AtomicInteger(0);
    double health = 1.0;
    // Map<String, Node> neighbors = new ConcurrentHashMap<>();

    Node(String server) {
        this.server = server;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof Node)) return false;
        Node other = (Node) obj;
        return this.server.equals(other.server);
    }

    @Override
    public int hashCode() {
        return server.hashCode();
    }

    @Override
    public String toString() {
        return server;
    }
}

class Edge {
    Node source;
    Node target;
    double latency = 100.0;
    double bandwidth = 10_000.0;
    double cost;

    Edge(Node source, Node target) {
        this.source = source;
        this.target = target;
    }
}

class BackendGraph {
    Map<Node, List<Edge>> graph;
    Node source;

    BackendGraph() {
        graph = new ConcurrentHashMap<>();
    }

    void addNode(Node node) {
        if(graph.containsKey(node)) return;
        List<Edge> edges = new ArrayList<>();
        for(Node nodeKey: graph.keySet()) {
            Edge toNode = new Edge(nodeKey, node);
            Edge fromNode = new Edge(node, nodeKey);
            graph.get(nodeKey).add(toNode);
            edges.add(fromNode);
        }
        graph.put(node, edges);
    }

    List<Edge> getEdges(Node node) {
        return graph.getOrDefault(node, new ArrayList<>());
    }

    Set<Node> getNodes() {
        return graph.keySet();
    }

    Node getNodeFromString(String server) {
        Set<Node> nodes = graph.keySet();
        for(Node node: nodes) {
            if(node.server.equals(server)) return node;
        }

        return null;
    }

    void updateMetrics() {
        // Update health metric of each backend server
        for(Node node: getNodes()) {
            if(node.server.equals("LoadBalancer")) continue;
            node.health = checkServerHealth(node.server);
        }

        for(Node node: getNodes()) {
            if(node.server.equals("LoadBalancer")) continue;
            // Updating latency and bandwith of edges
            for(Edge edge: getEdges(node)) {
                edge.latency = measureLatencyFromLB(edge.target);
                edge.bandwidth = measureBandwidthFromLB(edge.target);
            }
        }
    }

    double checkServerHealth(String server) {
        String[] serverParts = server.split(":");
        String host = serverParts[0], port = serverParts[1];
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, Integer.parseInt(port)), 200);
            return 1.0;
        } catch (IOException ex) {
            return 0.0;
        }
    }

    // Placeholder logic for within LB since a connection cannot be established
    // from source to target in a standalone LB project
    double measureLatencyFromLB(Node target) {
        String[] serverParts = target.server.split(":");
        String host = serverParts[0], port = serverParts[1];
        long start = System.nanoTime();
        try(Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, Integer.parseInt(port)), 500);
            long end = System.nanoTime();
            return (end - start) / 1_000_000.0; // ms as double
        } catch (IOException ex) {
            return Double.MAX_VALUE; // treat as infinite latency if unreachable
        }
    }

    /**
     * Naively measure bandwidth (bytes/sec) from LB to edge.target.
     * Note: This simply times a 512‐byte write. In real scenarios, TCP buffering
     * may make this inaccurate. Consider a proper request/response if you need precision.
     */
    double measureBandwidthFromLB(Node node) {
        String[] serverParts = node.server.split(":");
        String host = serverParts[0], port = serverParts[1];
        try(Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, Integer.parseInt(port)), 500);
            byte[] sample = new byte[512];
            for(int i = 0; i < sample.length; i++) {
                sample[i] = 0;
            }
            OutputStream backendOut = s.getOutputStream();
            long start = System.nanoTime();
            backendOut.write(sample);
            backendOut.flush();
            long end = System.nanoTime();

            double elapsedMs = (end - start) / 1_000_000.0; // convert ns to ms
            if (elapsedMs <= 0.0) {
                return 0.0;
            }

            return (512.0 * 1000.0) / elapsedMs; // Returning bytes/sec
        } catch (IOException ex) {
            return 0.0; // treat as infinite latency if unreachable
        }
    }

    double computeCost(Edge edge, double alphaLatency, double betaBandwidth, double gammaQueue, double deltaHealth) {
        double latencyCost = edge.latency;
        double bandwidthCost;
        try {
            bandwidthCost = 1.0/(edge.bandwidth);
        } catch(Exception e) {
            bandwidthCost = Double.MAX_VALUE;
        }
        double healthCost = 1.0 - edge.target.health;
        double queueCost = 1.0 * edge.target.qLen.get();

        return alphaLatency * latencyCost
             + betaBandwidth * bandwidthCost
             + gammaQueue * queueCost
             + deltaHealth * healthCost;
    }

    List<Node> findShortestPath(Node source, Set<Node> targetBackends,
                                       double alphaLatency,
                                       double betaBandwidth,
                                       double gammaQueue,
                                       double deltaHealth
                                ) {
        // distance and predecessor map
        Map<Node, Double> dist = new HashMap<>();
        Map<Node, Node> prev = new HashMap<>();
                                    
        for(Node node: graph.keySet()) {
            dist.put(node, Double.POSITIVE_INFINITY);
        }
        dist.put(source, 0.0);

        PriorityQueue<NodeEntry> minpq = new PriorityQueue<>((a,b) -> Double.compare(a.distance, b.distance));

        minpq.add(new NodeEntry(source, 0.0));

        Set<Node> visited = new HashSet<>();

        while(!minpq.isEmpty()) {
            NodeEntry entry = minpq.poll();

            Node node = entry.node;
            double distance = entry.distance;

            visited.add(node);

            if(distance > dist.get(node)) continue;

            if(targetBackends.contains(node)) {
                return constructSrcToTargetPath(source, node, prev);
            }

            for(Edge edge: graph.getOrDefault(node, new ArrayList<>())) {
                Node target = edge.target;
                if(visited.contains(target)) continue;
                double cost = computeCost(edge, alphaLatency, betaBandwidth, gammaQueue, deltaHealth);

                NodeEntry newEntry = new NodeEntry(target, (distance + cost));
                minpq.add(newEntry);

                if((distance + cost) < dist.get(target)) {
                    dist.put(target, (distance + cost));
                    prev.put(target, node);
                }

            }
        }

        return Collections.emptyList();
    }

    private List<Node> constructSrcToTargetPath(Node source, Node curr, Map<Node, Node> prev) {
        LinkedList<Node> path = new LinkedList<>();

        while(curr != null) {
            path.addFirst(curr);
            curr = prev.get(curr);
        }

        // If path does not start with source, it means no valid path
        if (path.isEmpty() || !path.get(0).equals(source)) {
            return Collections.emptyList();
        }

        return path;
    }

    private static class NodeEntry {
        Node node;
        double distance;
        NodeEntry(Node node, double distance) {
            this.node = node;
            this.distance = distance;
        }
    }
}

