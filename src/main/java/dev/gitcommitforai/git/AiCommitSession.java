package dev.gitcommitforai.git;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import dev.gitcommitforai.core.Cancellation;
import java.util.concurrent.atomic.AtomicReference;

@Service(Service.Level.PROJECT)
public final class AiCommitSession implements Disposable {
    private final AtomicReference<Cancellation> running = new AtomicReference<>();
    private volatile String lastPrompt = "";
    public static AiCommitSession get(Project project) { return project.getService(AiCommitSession.class); }
    public boolean start(Cancellation token) { return running.compareAndSet(null, token); }
    public boolean isRunning() { return running.get() != null; }
    public void cancel() { Cancellation token = running.get(); if (token != null) token.cancel(); }
    public void finish(Cancellation token) { running.compareAndSet(token, null); }
    public void setLastPrompt(String prompt) { lastPrompt = prompt; }
    public String getLastPrompt() { return lastPrompt; }
    @Override public void dispose() { cancel(); lastPrompt = ""; }
}

