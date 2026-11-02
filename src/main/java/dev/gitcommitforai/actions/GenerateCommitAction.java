package dev.gitcommitforai.actions;

import com.intellij.notification.*;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.*;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.VcsDataKeys;
import dev.gitcommitforai.core.*;
import dev.gitcommitforai.core.Cancellation;
import dev.gitcommitforai.git.*;
import dev.gitcommitforai.settings.*;
import dev.gitcommitforai.ui.AiCommitConfigurable;
import org.jetbrains.annotations.NotNull;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Objects;

public final class GenerateCommitAction extends AnAction {
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
    @Override public void update(@NotNull AnActionEvent e) {
        Project p = e.getProject();
        boolean available = p != null && !p.isDisposed() && e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) != null;
        e.getPresentation().setEnabledAndVisible(available);
        e.getPresentation().setText(available && AiCommitSession.get(p).isRunning() ? "取消 AI 生成" : "AI 生成提交信息");
    }
    @Override public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject(); if (project == null) return;
        AiCommitSession session = AiCommitSession.get(project);
        if (session.isRunning()) { session.cancel(); return; }
        SettingsState settings = PluginSettings.getInstance().snapshot();
        ModelProfile profile = settings.activeModel();
        if (profile == null) { ShowSettingsUtil.getInstance().showSettingsDialog(project, AiCommitConfigurable.class); return; }
        try { ModelClient.validate(profile); } catch (Exception failure) { notify(project, failure.getMessage()); ShowSettingsUtil.getInstance().showSettingsDialog(project, AiCommitConfigurable.class); return; }
        CommitSelection selection = CommitSelection.from(e);
        if (selection == null) { notify(project, "请在 Commit 工具窗口勾选文件后，使用提交信息框旁的 AI 按钮。"); return; }
        if (selection.changes().isEmpty() && selection.unversioned().isEmpty()) { notify(project, "请先勾选本次需要提交的文件。"); return; }
        Cancellation token = new Cancellation(); if (!session.start(token)) return;
        StreamingCommitSink sink = new StreamingCommitSink(project, selection, token);
        new Task.Backgroundable(project, "AI 生成提交信息", true) {
            @Override public void run(@NotNull ProgressIndicator indicator) {
                try {
                    indicator.setIndeterminate(true);
                    Cancellation requestCancel = new Cancellation(() -> { token.check(); return indicator.isCanceled() || project.isDisposed(); });
                    var context = new CommitContextCollector().collect(project, selection, settings, indicator);
                    String template = settings.activeTemplate().content;
                    if (settings.useProjectPrompt && project.getBasePath() != null) {
                        Path projectFile = Path.of(project.getBasePath(), "commit-prompt.txt");
                        if (Files.isRegularFile(projectFile)) {
                            if (Files.size(projectFile) > 128 * 1024) throw new IllegalArgumentException("项目 Prompt 文件超过 128 KiB。");
                            template = Files.readString(projectFile, StandardCharsets.UTF_8);
                        }
                    }
                    String prompt = PromptEngine.render(template, context.diff(), settings.language, context.project(), context.branch(), settings.maxPromptChars);
                    session.setLastPrompt(prompt); indicator.setText("等待 " + profile.name + " · " + profile.model);
                    String key = profile.authMode.equals("NONE") ? "" : SecretStore.read(profile.id);
                    String result = new ModelClient().generate(profile, key, prompt, settings.useIdeProxy, sink::accept, requestCancel);
                    requestCancel.check(); sink.complete(result);
                } catch (java.util.concurrent.CancellationException ignored) { sink.restore(); }
                catch (Exception failure) { sink.restore(); GenerateCommitAction.notify(project, Objects.toString(failure.getMessage(), "生成失败，请检查模型配置。")); }
                finally { ApplicationManager.getApplication().invokeLater(() -> session.finish(token), ModalityState.any()); }
            }
        }.queue();
    }
    private static void notify(Project p, String message) {
        NotificationGroupManager.getInstance().getNotificationGroup("Git Commit for AI")
            .createNotification("git commit for ai", message, NotificationType.WARNING).notify(p);
    }
}

