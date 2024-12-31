package dev.lukebemish.forkedtaskexecutor.runner;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class Main implements AutoCloseable {
    private static final boolean STACKTRACE = !Boolean.getBoolean("dev.lukebemish.forkedtaskexecutor.hidestacktrace");

    private final ServerSocket socket;
    private final ExecutorService executor = ExecutorUtils.newService();
    private final Task task;

    private Main(Task task) throws IOException {
        this.task = task;
        this.socket = new ServerSocket(0);
    }

    private static final PrintStream OUT = System.out;
    private static final PrintStream ERR = System.err;
    private static final InputStream IN = System.in;

    public static void main(String[] args) {
        try {
            Class<?> taskClass = Class.forName(args[0], false, Main.class.getClassLoader());
            if (!Task.class.isAssignableFrom(taskClass)) {
                throw new IllegalArgumentException("Class " + args[0] + " does not implement "+Task.class.getName());
            }
            Constructor<?> constructor = taskClass.getConstructor(String[].class);
            String[] otherArgs = new String[args.length - 1];
            System.arraycopy(args, 1, otherArgs, 0, otherArgs.length);
            var task = (Task) constructor.newInstance((Object) otherArgs);

            System.setOut(task.replaceSystemOut(OUT));
            System.setErr(task.replaceSystemErr(ERR));
            System.setIn(task.replaceSystemIn(IN));

            try (Main runner = new Main(task)) {
                runner.run(task);
            }
            System.exit(0);
        } catch (Throwable t) {
            logException(t);
            System.exit(1);
        }
    }

    private void run(Task task) throws IOException {
        // This tells the parent process what port we're listening on
        OUT.println(socket.getLocalPort());
        var socket = this.socket.accept();
        // Communication back to the parent is done through this handle, which ensures synchronization on the output stream.
        var socketHandle = new SocketHandle(socket);
        task.setupLifecycleWatcher(currentlyExecuting::get, () -> {
            if (shutdown.get()) {
                return true;
            }
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            shutdownRequest.updateAndGet(existing -> {
                if (existing != null) {
                    future.complete(false);
                    return existing;
                }
                return future::complete;
            });
            try {
                socketHandle.writeAskShutdown();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            try {
                return future.get();
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        });
        while (true) {
            int id = socketHandle.readId();
            if (id == -2) {
                if (currentlyExecuting.get() == 0) {
                    shutdown.set(true);
                    if (shutdownRequest.get() != null) {
                        shutdownRequest.get().accept(true);
                    }
                    // We are allowed to shut down, so we do
                    socketHandle.writeShutdown();
                    break;
                }
            } else if (id < 0) {
                shutdown.set(true);
                if (shutdownRequest.get() != null) {
                    shutdownRequest.get().accept(true);
                }
                // We have been sent a signal to gracefully shutdown, so we stop processing new submissions
                socketHandle.writeShutdown();
                break;
            }
            if (shutdownRequest.get() != null) {
                shutdownRequest.get().accept(false);
            }
            byte[] input = socketHandle.readBytes();
            // Submissions to the child process take the format ID, input bytes
            execute(id, input, socketHandle);
            currentlyExecuting.incrementAndGet();
        }
    }

    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final AtomicInteger currentlyExecuting = new AtomicInteger(0);
    private final AtomicReference<Consumer<Boolean>> shutdownRequest = new AtomicReference<>(null);

    private void execute(int id, byte[] input, SocketHandle socketHandle) {
        executor.submit(() -> {
            try {
                byte[] output = task.run(input);
                socketHandle.writeSuccess(id, output);
            } catch (Throwable t) {
                logException(t);
                try {
                    socketHandle.writeFailure(id);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                throw new RuntimeException(t);
            } finally {
                currentlyExecuting.decrementAndGet();
            }
        });
    }

    @Override
    public void close() throws IOException, TimeoutException {
        socket.close();
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(4000, TimeUnit.MILLISECONDS)) {
                throw new TimeoutException("Executor did not terminate in time");
            }
        } catch (InterruptedException e) {
            logException(e);
            throw new RuntimeException(e);
        }
    }

    private static void logException(Throwable t) {
        if (STACKTRACE) {
            t.printStackTrace(ERR);
        } else {
            ERR.println(t);
        }
    }

    private static final class SocketHandle {
        private final DataOutputStream output;
        private final DataInputStream input;

        private SocketHandle(Socket socket) throws IOException {
            this.output = new DataOutputStream(socket.getOutputStream());
            this.input = new DataInputStream(socket.getInputStream());
        }

        synchronized void writeFailure(int id) throws IOException {
            output.writeInt(id);
            output.writeBoolean(false);
            output.flush();
        }

        synchronized void writeSuccess(int id, byte[] result) throws IOException {
            output.writeInt(id);
            output.writeBoolean(true);
            output.writeInt(result.length);
            output.write(result);
            output.flush();
        }

        synchronized void writeAskShutdown() throws IOException {
            output.writeInt(-2);
            output.flush();
        }

        synchronized void writeShutdown() throws IOException {
            output.writeInt(-1);
            output.flush();
        }

        int readId() throws IOException {
            return input.readInt();
        }

        byte[] readBytes() throws IOException {
            int length = input.readInt();
            return input.readNBytes(length);
        }
    }
}
