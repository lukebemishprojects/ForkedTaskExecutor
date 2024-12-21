package dev.lukebemish.forkedtaskexecutor.test;

import dev.lukebemish.forkedtaskexecutor.runner.Task;

public class EchoTask implements Task {
    public EchoTask(String[] args) {}

    @Override
    public byte[] run(byte[] input) throws InterruptedException {
        Thread.sleep(200);
        return input;
    }
}
