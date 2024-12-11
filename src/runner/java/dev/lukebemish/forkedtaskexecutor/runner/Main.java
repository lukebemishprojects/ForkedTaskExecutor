package dev.lukebemish.forkedtaskexecutor.runner;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class Main implements AutoCloseable {
    private static final boolean STACKTRACE = !Boolean.getBoolean("dev.lukebemish.forkedtaskexecutor.hidestacktrace");

    private final ServerSocket socket;
    private final ExecutorService executor = ExecutorUtils.newService();
    private final Task task;

    private Main(Task task) throws IOException {
        this.task = task;
        this.socket = new ServerSocket(0);
    }

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
            try (Main runner = new Main(task)) {
                runner.run();
            }
            System.exit(0);
        } catch (Throwable t) {
            logException(t);
            System.exit(1);
        }
    }

    private void run() throws IOException {
        // This tells the parent process what port we're listening on
        System.out.println(socket.getLocalPort());
        var socket = this.socket.accept();
        // Communication back to the parent is done through this handle, which ensures synchronization on the output stream.
        var socketHandle = new SocketHandle(socket);
        while (true) {
            int id = socketHandle.readId();
            if (id == -1) {
                // We have been sent a signal to gracefully shutdown, so we stop processing new submissions
                break;
            }
            byte[] input = socketHandle.readBytes();
            // Submissions to the child process take the format ID, input bytes
            execute(id, input, socketHandle);
        }
    }

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
            t.printStackTrace(System.err);
        } else {
            System.err.println(t);
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
            output.write(result.length);
            output.write(result);
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
