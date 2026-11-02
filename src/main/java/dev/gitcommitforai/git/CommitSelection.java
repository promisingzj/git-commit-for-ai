package dev.gitcommitforai.git;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.vcs.*;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.vcs.commit.CommitWorkflowUi;
import com.intellij.vcs.commit.CommitMessageUi;
import java.util.List;

public record CommitSelection(List<Change> changes, List<FilePath> unversioned, CommitMessageI message, Document document, CommitMessageUi messageUi) {
    public String text() { return document != null ? document.getText() : messageUi.getText(); }
    public static CommitSelection from(AnActionEvent event) {
        CommitWorkflowUi workflow = event.getData(VcsDataKeys.COMMIT_WORKFLOW_UI);
        CommitMessageI message = event.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL);
        if (workflow == null || message == null) return null;
        return new CommitSelection(List.copyOf(workflow.getIncludedChanges()), List.copyOf(workflow.getIncludedUnversionedFiles()), message, event.getData(VcsDataKeys.COMMIT_MESSAGE_DOCUMENT), workflow.getCommitMessageUi());
    }
}

