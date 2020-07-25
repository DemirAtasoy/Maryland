package com.maryland;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Supplier;
import java.util.logging.Logger;

public final class Maryland {

    private static final Logger LOG = ((Supplier<Logger>) () -> {
        return Logger.getLogger("Maryland");
    }).get();

    public static Logger getLog() {
        return Maryland.LOG;
    }

    private Maryland() {

    }

    public static void main(String[] args) throws Exception {
        final Path file = Paths.get("C:\\Users\\Demir Atasoy\\Documents\\Code\\JavaWorkspace\\Maryland\\run\\Plugin-1.0-SNAPSHOT.jar");
        final Plugin plugin = Plugin.load(file);

        plugin.post("Hello World!");

        System.out.println("".getClass());
        System.out.println("".getClass().getClass());

    }
}
