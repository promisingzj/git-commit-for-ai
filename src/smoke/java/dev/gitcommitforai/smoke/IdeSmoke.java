package dev.gitcommitforai.smoke;

import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.application.*;
import com.intellij.openapi.extensions.PluginId;
import dev.gitcommitforai.core.*;
import dev.gitcommitforai.settings.PluginSettings;
import dev.gitcommitforai.ui.AiCommitConfigurable;
import java.nio.file.*;
import java.util.*;

/** Test-only starter packaged separately from the distributed plugin. */
public final class IdeSmoke implements ApplicationStarter {
    @Override public String getCommandName() { return "git-commit-for-ai-smoke"; }
    @Override public boolean isHeadless() { return true; }
    @Override public int getRequiredModality() { return NOT_IN_EDT; }
    @Override public void main(List<String> args) {
        try {
            var descriptor = PluginManagerCore.getPlugin(PluginId.getId("dev.gitcommitforai.idea"));
            require(descriptor != null && descriptor.isEnabled(), "plugin descriptor enabled");
            for (String id : List.of("GitCommitForAi.Generate", "GitCommitForAi.Switch", "GitCommitForAi.Settings", "GitCommitForAi.LastPrompt"))
                require(ActionManager.getInstance().getAction(id) != null, "action instantiated: " + id);
            PluginSettings service = PluginSettings.getInstance();
            String phase = args.size() > 1 ? args.get(1) : "verify";
            if (phase.equals("write")) {
                SettingsState state = new SettingsState();
                ModelProfile cloud = new ModelProfile(); cloud.id = "smoke-cloud"; cloud.name = "持久化模型 🚀";
                cloud.model = "custom-model"; cloud.endpoint = "http://127.0.0.1:11434/v1";
                ModelProfile local = new ModelProfile(); local.id = "smoke-local"; local.name = "本地模型"; local.protocol = "OLLAMA";
                local.endpoint = "http://127.0.0.1:11434/api/generate"; local.model = "local-model"; local.authMode = "NONE";
                state.models.addAll(List.of(cloud, local)); state.activeModelId = cloud.id; state.maxPromptChars = 32000;
                state.templates.add(new PromptTemplate("smoke-template", "持久化模板 ✨", "用 {language} 总结 🚀：{diff}"));
                state.activeTemplateId = "smoke-template"; service.replace(state);
                ApplicationManager.getApplication().saveSettings();
                Path config = Path.of(System.getProperty("idea.config.path"), "options", "gitCommitForAI.xml");
                long deadline = System.nanoTime() + 15_000_000_000L;
                while ((!Files.isRegularFile(config) || !Files.readString(config).contains("smoke-cloud")) && System.nanoTime() < deadline) Thread.sleep(100);
                require(Files.isRegularFile(config) && Files.readString(config).contains("smoke-cloud"), "state written to sandbox disk");
            }
            SettingsState restored = service.snapshot();
            require(restored.models.size() == 2 && restored.activeModel().name.equals("持久化模型 🚀"), "profiles survived " + phase);
            require(restored.maxPromptChars == 32000 && restored.activeTemplate().content.contains("🚀"), "options and Unicode template survived " + phase);
            ApplicationManager.getApplication().invokeAndWait(() -> {
                AiCommitConfigurable configurable = new AiCommitConfigurable();
                try {
                    require(configurable.createComponent() != null, "settings UI created in real IDE");
                    require(!configurable.isModified(), "opening settings keeps configuration unchanged");
                } finally { configurable.disposeUIResources(); }
            }, ModalityState.any());
            Files.writeString(Path.of(System.getProperty("gitcommitforai.smoke.output"), "smoke-" + phase + ".txt"), "PASS: descriptor, actions, settings UI, model profiles, templates, Unicode, disk persistence.\n");
            System.out.println("GIT_COMMIT_FOR_AI_SMOKE_PASS " + phase);
            System.exit(0);
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        System.out.println("PASS " + message);
    }
}
