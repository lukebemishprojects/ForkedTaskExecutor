package dev.lukebemish.parallelrunner.runner;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ExecutorUtils {
    private ExecutorUtils() {}

    static ExecutorService newService() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
