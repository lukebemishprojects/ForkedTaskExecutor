package dev.lukebemish.forkedtaskexecutor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ForkedTaskExecutorSpec {
    private final String javaExecutable;
    private final List<String> jvmOptions;
    private final List<String> programOptions;
    private final boolean hideStacktrace;
    private final String taskClass;

    private ForkedTaskExecutorSpec(String javaExecutable, List<String> jvmOptions, List<String> programOptions, boolean hideStacktrace, String taskClass) {
        this.javaExecutable = javaExecutable;
        this.jvmOptions = List.copyOf(jvmOptions);
        this.programOptions = List.copyOf(programOptions);
        this.hideStacktrace = hideStacktrace;
        this.taskClass = taskClass;
    }

    public String javaExecutable() {
        return javaExecutable;
    }

    public List<String> jvmOptions() {
        return jvmOptions;
    }

    public List<String> programOptions() {
        return programOptions;
    }

    public boolean hideStacktrace() {
        return hideStacktrace;
    }

    public String taskClass() {
        return taskClass;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String javaExecutable;
        private final List<String> jvmOptions = new ArrayList<>();
        private final List<String> programOptions = new ArrayList<>();
        private boolean hideStacktrace = false;
        private String taskClass;

        private Builder() {}

        public Builder javaExecutable(Path javaExecutable) {
            this.javaExecutable = javaExecutable.toString();
            return this;
        }

        public Builder javaExecutable(String javaExecutable) {
            this.javaExecutable = javaExecutable;
            return this;
        }

        public Builder addJvmOption(String jvmOption) {
            jvmOptions.add(jvmOption);
            return this;
        }

        public Builder addProgramOption(String programOption) {
            programOptions.add(programOption);
            return this;
        }

        public Builder hideStacktrace(boolean hideStacktrace) {
            this.hideStacktrace = hideStacktrace;
            return this;
        }

        public Builder taskClass(String taskClass) {
            this.taskClass = taskClass;
            return this;
        }

        public ForkedTaskExecutorSpec build() {
            return new ForkedTaskExecutorSpec(javaExecutable, jvmOptions, programOptions, hideStacktrace, taskClass);
        }
    }
}
