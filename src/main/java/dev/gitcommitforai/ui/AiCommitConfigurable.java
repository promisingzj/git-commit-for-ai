package dev.gitcommitforai.ui;

import com.intellij.openapi.options.*;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.application.ApplicationManager;
import dev.gitcommitforai.core.*;
import dev.gitcommitforai.core.Cancellation;
import dev.gitcommitforai.settings.*;
import org.jetbrains.annotations.NotNull;
import javax.swing.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

public final class AiCommitConfigurable implements SearchableConfigurable {
    private SettingsPanel panel;
    private SettingsState baseline;
    private final List<Cancellation> tests = new ArrayList<>();
    @Override public @NotNull String getId() { return "dev.gitcommitforai.settings"; }
    @Override public String getDisplayName() { return "git commit for ai"; }
    @Override public JComponent createComponent() {
        baseline = PluginSettings.getInstance().snapshot();
        panel = new SettingsPanel(baseline, (profile, override, proxy, result) -> {
            Cancellation cancellation = new Cancellation(); tests.add(cancellation);
            new Task.Backgroundable(null, "测试 AI 模型连接", true) {
                @Override public void run(@NotNull ProgressIndicator indicator) {
                    String message;
                    try {
                        String key = profile.authMode.equals("NONE") ? "" : override != null ? override : SecretStore.read(profile.id);
                        Cancellation token = new Cancellation(() -> { cancellation.check(); return indicator.isCanceled(); });
                        String response = new ModelClient().generate(profile, key, "Reply with OK only.", proxy, chunk -> {}, token);
                        message = "✓ 连接成功 · " + PromptEngine.truncate(response.replace('\n', ' '), 90, "…");
                    } catch (java.util.concurrent.CancellationException e) { message = "连接测试已取消。"; }
                    catch (Exception e) { message = "连接失败：" + Objects.toString(e.getMessage(), e.getClass().getSimpleName()); }
                    String finished = message;
                    ApplicationManager.getApplication().invokeLater(() -> { tests.remove(cancellation); if (panel != null) result.accept(finished); });
                }
            }.queue();
        });
        return panel;
    }
    @Override public boolean isModified() {
        return panel != null && (!panel.keyChanges().isEmpty() || !PluginSettings.fingerprint(panel.draft()).equals(PluginSettings.fingerprint(baseline)));
    }
    @Override public void apply() throws ConfigurationException {
        if (panel == null) return;
        SettingsState next = panel.draft();
        try {
            for (ModelProfile m : next.models) {
                if (m.name.isBlank()) throw new IllegalArgumentException("模型配置名称不能为空。");
                ModelClient.extraBody(m);
            }
            for (PromptTemplate t : next.templates) {
                if (t.name.isBlank()) throw new IllegalArgumentException("模板名称不能为空。");
                if (!t.content.contains("{diff}")) throw new IllegalArgumentException("模板「" + t.name + "」必须包含 {diff}。");
            }
        } catch (IllegalArgumentException e) { throw new ConfigurationException(e.getMessage()); }
        Map<String, String> keys = panel.keyChanges();
        for (ModelProfile old : baseline.models) if (next.models.stream().noneMatch(m -> m.id.equals(old.id))) keys.put(old.id, "");
        AtomicReference<Exception> failure = new AtomicReference<>();
        if (!keys.isEmpty()) {
            ProgressManager.getInstance().run(new Task.Modal(null, "保存模型配置与 API Key", false) {
                @Override public void run(@NotNull ProgressIndicator indicator) {
                    try { SecretStore.save(keys); } catch (Exception e) { failure.set(e); }
                }
            });
        }
        if (failure.get() != null) throw new ConfigurationException("配置未应用：" + failure.get().getMessage());
        for (ModelProfile m : next.models) if (keys.containsKey(m.id)) m.credentialConfigured = !keys.get(m.id).isEmpty();
        PluginSettings.getInstance().replace(next); baseline = PluginSettings.getInstance().snapshot(); panel.reload(baseline);
    }
    @Override public void reset() { if (panel != null) { baseline = PluginSettings.getInstance().snapshot(); panel.reload(baseline); } }
    @Override public void disposeUIResources() { tests.forEach(Cancellation::cancel); tests.clear(); if (panel != null) panel.dispose(); panel = null; }
}

