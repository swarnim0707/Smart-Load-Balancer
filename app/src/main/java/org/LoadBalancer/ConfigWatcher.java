package org.LoadBalancer;

import ConfigLoader.ConfigLoader;
import RevProxyServer.RouteTrie;

import java.nio.file.*;
import static java.nio.file.StandardWatchEventKinds.*;

public class ConfigWatcher implements Runnable {
    private final Path configFile;
    private final ConfigLoader loader;
    private final RouteTrie routeTrie;

    public ConfigWatcher(ConfigLoader loader, String configPath, RouteTrie routeTrie) {
        this.configFile = Paths.get(configPath).toAbsolutePath();
        this.loader = loader;
        this.routeTrie = routeTrie;
    }

    @Override
    public void run() {
        try {
            WatchService watchService = FileSystems.getDefault().newWatchService();
            Path dir = configFile.getParent();
            dir.register(watchService, ENTRY_MODIFY);

            while (true) {
                WatchKey key = watchService.take(); // blocks
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == ENTRY_MODIFY) {
                        Path changed = (Path) event.context();
                        if (changed.equals(configFile.getFileName())) {
                            System.out.println("Config file modified, reloading...");
                            loader.reload(configFile.toString(), routeTrie);
                        }
                    }
                }
                key.reset();
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("Error watching config file: " + e.getMessage());
        }
    }
}
