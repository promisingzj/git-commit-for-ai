package dev.gitcommitforai.actions;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.options.ShowSettingsUtil;
import dev.gitcommitforai.ui.AiCommitConfigurable;
import org.jetbrains.annotations.NotNull;

public final class OpenSettingsAction extends AnAction {
    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        ShowSettingsUtil.getInstance().showSettingsDialog(event.getProject(), AiCommitConfigurable.class);
    }
}

