package dev.gitcommitforai.git;

import com.intellij.openapi.application.*;
import com.intellij.openapi.project.Project;
import dev.gitcommitforai.core.Cancellation;
import java.util.concurrent.atomic.*;

public final class StreamingCommitSink {
    private final Project project;
    private final CommitSelection selection;
    private final Cancellation cancel;
    private final String original;
    private String expected;
    private final StringBuilder received = new StringBuilder();
    private final AtomicReference<String> latest = new AtomicReference<>("");
    private final AtomicBoolean pending = new AtomicBoolean();
    private volatile boolean stopped;
    public StreamingCommitSink(Project project, CommitSelection selection, Cancellation cancel) {
        this.project = project; this.selection = selection; this.cancel = cancel;
        original = selection.text(); expected = original;
    }
    public synchronized void accept(String part) {
        received.append(part); latest.set(received.toString()); schedule();
    }
    public void complete(String text) { latest.set(text); schedule(); }
    private void schedule() {
        if (!pending.compareAndSet(false, true)) return;
        ApplicationManager.getApplication().invokeLater(() -> {
            pending.set(false); if (stopped || project.isDisposed()) return;
            if (!selection.text().equals(expected)) { stopped = true; cancel.cancel(); return; }
            String value = latest.get();
            WriteAction.run(() -> selection.message().setCommitMessage(value));
            expected = selection.text();
        }, ModalityState.any());
    }
    public void restore() {
        stopped = true;
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            if (!selection.text().equals(expected)) return;
            WriteAction.run(() -> selection.message().setCommitMessage(original));
        }, ModalityState.any());
    }
}

