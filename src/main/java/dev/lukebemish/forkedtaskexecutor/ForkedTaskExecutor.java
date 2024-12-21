package dev.lukebemish.forkedtaskexecutor;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class ForkedTaskExecutor implements AutoCloseable {
    private final Process process;
    private final ResultListener listener;

    public ForkedTaskExecutor(ForkedTaskExecutorSpec spec) {
        var builder = new ProcessBuilder();
        builder.redirectOutput(ProcessBuilder.Redirect.PIPE);
        builder.redirectError(ProcessBuilder.Redirect.PIPE);
        builder.redirectInput(ProcessBuilder.Redirect.PIPE);
        List<String> args = new ArrayList<>();
        args.add(spec.javaExecutable());
        if (spec.hideStacktrace()) {
            args.add("-Ddev.lukebemish.forkedtaskexecutor.hidestacktrace=true");
        }
        args.addAll(spec.jvmOptions());
        args.add("dev.lukebemish.forkedtaskexecutor.runner.Main");
        args.add(spec.taskClass());
        args.addAll(spec.programOptions());
        builder.command(args);
        try {
            this.process = builder.start();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        CompletableFuture<String> socketPort = new CompletableFuture<>();
        var thread = new StreamWrapper(process.getInputStream(), socketPort);
        new Thread(() -> {
            try {
                InputStreamReader reader = new InputStreamReader(process.getErrorStream());
                BufferedReader bufferedReader = new BufferedReader(reader);
                String line;
                while ((line = bufferedReader.readLine()) != null) {
                    System.err.println(line);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }).start();
        thread.start();
        try {
            String socketPortString = socketPort.get(4000, TimeUnit.MILLISECONDS);
            int port = Integer.parseInt(socketPortString);
            this.listener = new ResultListener(new Socket(InetAddress.getLoopbackAddress(), port));
            this.listener.start();
        } catch (InterruptedException | ExecutionException | TimeoutException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static final class StreamWrapper extends Thread {
        private final InputStream stream;
        private final CompletableFuture<String> socketPort;

        private StreamWrapper(InputStream stream, CompletableFuture<String> socketPort) {
            this.stream = stream;
            this.socketPort = socketPort;
            this.setUncaughtExceptionHandler((t, e) -> {
                socketPort.completeExceptionally(e);
                StreamWrapper.this.getThreadGroup().uncaughtException(t, e);
            });
        }

        @Override
        public void run() {
            try {
                var reader = new BufferedReader(new InputStreamReader(stream));
                socketPort.complete(Objects.requireNonNull(reader.readLine(), "No port provided by daemon"));
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(line);
                }
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
    }

    private static final class SocketHandle {
        private final DataOutputStream output;
        private final DataInputStream input;
        private final Socket socket;

        private SocketHandle(Socket socket) throws IOException {
            this.output = new DataOutputStream(socket.getOutputStream());
            this.input = new DataInputStream(socket.getInputStream());
            this.socket = socket;
        }

        synchronized void writeSubmission(int id, byte[] input) throws IOException {
            output.writeInt(id);
            output.writeInt(input.length);
            output.write(input);
            output.flush();
        }

        // Will be true only if a shutdown signal is successfully sent to the channel.
        private volatile boolean gracefulShutdown = false;

        synchronized void shutdown() throws IOException {
            try {
                // -1 ID signals the end of submissions
                output.writeInt(-1);
                output.flush();
                this.gracefulShutdown = true;
            } finally {
                // Then close the socket
                socket.close();
            }
        }

        int readId() throws IOException {
            try {
                return input.readInt();
            } catch (SocketException e) {
                // Could be the socket is intentionally closed during cleanup, could be something went sideways.
                // To differentiate -- check gracefulShutdown
                if (gracefulShutdown) {
                    return -1;
                }
                throw e;
            }
        }

        boolean readSuccess() throws IOException {
            return input.readBoolean();
        }

        byte[] readResult() throws IOException {
            int length = input.readInt();
            return input.readNBytes(length);
        }
    }

    private static final class ResultListener extends Thread {
        private final Map<Integer, CompletableFuture<byte[]>> results = new ConcurrentHashMap<>();
        private final SocketHandle socketHandle;
        // Handle uncaught exceptions by re-throwing them on shutdown
        private volatile Throwable thrownException;

        private ResultListener(Socket socket) throws IOException {
            this.socketHandle = new SocketHandle(socket);
            this.setUncaughtExceptionHandler((t, e) -> {
                try {
                    shutdown(e);
                    thrownException = e;
                } catch (IOException ex) {
                    var exception = new UncheckedIOException(ex);
                    exception.addSuppressed(e);
                    thrownException = exception;
                }
            });
        }

        // Non-blocking, returns a future that will complete when the result is available (or throws if the listener is closed early unexpectedly)
        public CompletableFuture<byte[]> submit(int id, byte[] input) throws IOException {
            if (closed.get()) {
                return CompletableFuture.failedFuture(new IOException("Listener is closed"));
            }
            var out = results.computeIfAbsent(id, i -> new CompletableFuture<>());
            // Submissions to the child process take the format ID, input bytes -- the ID lets the result be matched up
            socketHandle.writeSubmission(id, input);
            return out;
        }

        private final AtomicBoolean closed = new AtomicBoolean();

        // Blocks until proper thread shutdown
        public void ensureShutdown() throws Throwable {
            /*
            Cleans up the child process, stops the listener thread, and joins the thread ensuring it is closed, rethrowing
            exceptions as necessary.
             */
            shutdown(new IOException("Execution was interrupted"));

            try {
                this.join();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

            if (thrownException != null) {
                throw thrownException;
            }
        }

        // Non-blocking
        private void shutdown(Throwable t) throws IOException {
            /*
            This method handles graceful shutdown of the child process and forcing the listener thread to stop. It does
            not ensure the listener thread is closed.
            - ensure the shutdown logic runs exactly once, in the proper order; it is possible for logic running on the
              thread to request a shutdown during the shutdown process initialized from another thread.
            - prevent submission of new tasks
            - complete all pending tasks exceptionally (with the provided exception if one is given)
            - stop the child process (by sending it a "shutdown" signal with ID -1)
            - stop the thread if it is running. The thread could be waiting at a number of places. Either:
              - the readId() call, if everything is running normally
              - the readResult() or readSuccess() call, if something is going badly wrong in the child process
              - not waiting, just in the loop -- the "closed" flag will be checked at the top of the loop
              to stop in either of these cases, we simply close the socket; this results in anything blocking on reading
              from the socket throwing an exception (see Socket#close()).
             */

            // Prevent multiple concurrent shutdowns
            if (!this.closed.compareAndSet(false, true)) return;

            for (var future : results.values()) {
                future.completeExceptionally(t);
            }
            results.clear();

            socketHandle.shutdown();
        }

        @Override
        public void run() {
            try {
                if (!closed.get()) {
                    while (!closed.get()) {
                        int id = socketHandle.readId();
                        if (id == -1) {
                            // The child process has been sent a shutdown signal gracefully
                            shutdown(new IOException("Listener is closed"));
                            break;
                        }
                        boolean success = socketHandle.readSuccess();
                        if (success) {
                            byte[] result = socketHandle.readResult();
                            var future = results.remove(id);
                            if (future != null) {
                                future.complete(result);
                            }
                        } else {
                            var future = results.remove(id);
                            if (future != null) {
                                var exception = new RuntimeException("Process failed");
                                future.completeExceptionally(exception);
                            }
                        }
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    @Override
    public void close() {
        List<Throwable> suppressed = new ArrayList<>();
        if (listener != null) {
            try {
                listener.ensureShutdown();
            } catch (Throwable t) {
                suppressed.add(t);
            }
        }
        if (process != null) {
            try {
                process.destroy();
                process.waitFor();
            } catch (Throwable t) {
                suppressed.add(t);
            }
        }
        if (!suppressed.isEmpty()) {
            var exception = new IOException("Failed to close resources");
            suppressed.forEach(exception::addSuppressed);
            throw new UncheckedIOException(exception);
        }
    }

    private final AtomicInteger id = new AtomicInteger();

    public Future<byte[]> submitAsync(byte[] input) {
        var nextId = id.getAndIncrement();
        try {
            return listener.submit(nextId, input);
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    public byte[] submit(byte[] input) {
        var nextId = id.getAndIncrement();
        try {
            return listener.submit(nextId, input).get();
        } catch (IOException | ExecutionException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
