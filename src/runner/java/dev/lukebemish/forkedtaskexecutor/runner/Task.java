package dev.lukebemish.forkedtaskexecutor.runner;

import java.io.InputStream;
import java.io.PrintStream;
import java.util.function.Supplier;

public interface Task {
    byte[] run(byte[] input) throws Exception;

    default PrintStream replaceSystemOut(PrintStream out) {
        return out;
    }

    default InputStream replaceSystemIn(InputStream in) {
        return in;
    }

    default PrintStream replaceSystemErr(PrintStream err) {
        return err;
    }

    default void setupLifecycleWatcher(Supplier<Integer> currentTasks, Supplier<Boolean> attemptShutdown) {}
}
