package org.LoadBalancer;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.stream.Collectors;
import java.io.File;
import java.util.*;

import RevProxyServer.RouteTrie;
import BackendGraph.BackendGraph;

public class ConfigLoader {
    private volatile Map<String, List<String>> routeToBackendMap = Collections.emptyMap();
    private volatile RouteTrie routeTrie;
    private volatile BackendGraph backendGraph;

    public ConfigLoader(String path, RouteTrie routeTrie, BackendGraph backendGraph) {
        loadConfigFile(path);
        this.routeTrie = routeTrie;
        this.backendGraph = backendGraph;
    }

    public void reload(String path, RouteTrie routeTrie) {
        loadConfigFile(path);
        synchronized(routeTrie) {
            routeTrie.clear();
            routeTrie.load(path);
        }
        // @TODO: Implement backend graph re-initialization
    }

    private void loadConfigFile(String path) {
        ObjectMapper mapper = new ObjectMapper();
        try {
            File file = new File(path);
            Map<String, List<String>> loadedMap = mapper.readValue(file, mapper.getTypeFactory().constructMapType(Map.class, String.class, List.class));
            
            Map<String, List<String>> unmodifiableMap = loadedMap.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                    Map.Entry::getKey,
                    e -> List.copyOf(e.getValue())
                ));

            routeToBackendMap = unmodifiableMap;
        
        } catch(Exception e) {
            System.err.println("Failed to load config: " + e.getMessage());
            routeToBackendMap = new HashMap<>();
        }
    }

    public List<String> getBackends(String route) {
        return routeToBackendMap.getOrDefault(route, Collections.emptyList());
    }

    public Set<String> getAllRoutes() {
        return routeToBackendMap.keySet();
    }

    public Map<String, List<String>> getConfig() {
        return routeToBackendMap;
    }
}