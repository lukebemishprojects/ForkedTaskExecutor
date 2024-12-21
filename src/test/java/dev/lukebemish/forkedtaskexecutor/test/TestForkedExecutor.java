package dev.lukebemish.forkedtaskexecutor.test;

import dev.lukebemish.forkedtaskexecutor.ForkedTaskExecutor;
import dev.lukebemish.forkedtaskexecutor.ForkedTaskExecutorSpec;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class TestForkedExecutor {
    @Test
    void testMain() {
        var jvmExecutable = ProcessHandle.current()
            .info()
            .command()
            .orElse(null);
        assertNotNull(jvmExecutable, "JVM executable not found");
        var spec = ForkedTaskExecutorSpec.builder()
            .taskClass(EchoTask.class.getName())
            .javaExecutable(jvmExecutable)
            .addJvmOption("-classpath")
            .addJvmOption(System.getProperty("forkedtaskexecutor.test.daemonclasspath"))
            .build();
        try (var executor = new ForkedTaskExecutor(spec)) {
            byte count = 10;
            @SuppressWarnings("unchecked") Future<byte[]>[] outputs = new Future[count];
            for (byte i = 0; i < 10; i++) {
                outputs[i] = executor.submitAsync(new byte[] {i});
            }
            for (byte i = 0; i < 10; i++) {
                try {
                    byte[] output = outputs[i].get();
                    assertArrayEquals(new byte[] {i}, output);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }
}
