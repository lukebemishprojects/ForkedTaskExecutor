package dev.lukebemish.forkedtaskexecutor.runner;

public interface Task {
    byte[] run(byte[] input);
}
