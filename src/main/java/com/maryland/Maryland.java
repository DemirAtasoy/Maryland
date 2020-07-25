package com.maryland;

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

    }
}
