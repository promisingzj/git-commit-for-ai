package dev.gitcommitforai.settings;

import com.google.gson.Gson;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.*;
import dev.gitcommitforai.core.*;
import org.jetbrains.annotations.NotNull;
import java.util.*;

@State(name = "GitCommitForAI", storages = @Storage("gitCommitForAI.xml"))
public final class PluginSettings implements PersistentStateComponent<SettingsState> {
    private SettingsState state = new SettingsState();
    public static PluginSettings getInstance() { return ApplicationManager.getApplication().getService(PluginSettings.class); }
    @Override public synchronized @NotNull SettingsState getState() { return state.copy(); }
    @Override public synchronized void loadState(@NotNull SettingsState value) { state = normalize(value); }
    public synchronized SettingsState snapshot() { return state.copy(); }
    public synchronized void replace(SettingsState value) { state = normalize(value.copy()); }
    public synchronized void select(String modelId, String templateId, String language) {
        if (modelId != null) state.activeModelId = modelId;
        if (templateId != null) state.activeTemplateId = templateId;
        if (language != null) state.language = language;
    }
    public static SettingsState normalize(SettingsState s) {
        if (s.models == null) s.models = new ArrayList<>();
        if (s.templates == null || s.templates.isEmpty()) s.templates = SettingsState.defaults();
        Set<String> ids = new HashSet<>();
        s.models.removeIf(Objects::isNull);
        for (ModelProfile m : s.models) {
            if (m.id == null || m.id.isBlank() || !ids.add(m.id)) { m.id = UUID.randomUUID().toString(); ids.add(m.id); }
            if (m.name == null || m.name.isBlank()) m.name = "自定义模型";
            if (m.protocol == null || !Set.of("OPENAI", "OLLAMA").contains(m.protocol)) m.protocol = "OPENAI";
            if (m.authMode == null || !Set.of("BEARER", "API_KEY", "NONE").contains(m.authMode)) m.authMode = "BEARER";
            m.endpoint = Objects.toString(m.endpoint, ""); m.model = Objects.toString(m.model, "");
            m.extraBodyJson = Objects.toString(m.extraBodyJson, "{}");
            m.timeoutSeconds = Math.clamp(m.timeoutSeconds, 10, 600);
        }
        ids.clear(); s.templates.removeIf(Objects::isNull);
        if (s.templates.isEmpty()) s.templates = SettingsState.defaults();
        for (PromptTemplate t : s.templates) {
            if (t.id == null || t.id.isBlank() || !ids.add(t.id)) { t.id = UUID.randomUUID().toString(); ids.add(t.id); }
            t.name = Objects.toString(t.name, "模板"); t.content = Objects.toString(t.content, "");
        }
        s.language = Objects.toString(s.language, "简体中文");
        s.activeModelId = s.activeModel() == null ? "" : s.activeModel().id;
        s.activeTemplateId = s.activeTemplate().id;
        s.maxPromptChars = Math.clamp(s.maxPromptChars, 1000, 200000);
        s.excludePatterns = Objects.toString(s.excludePatterns, "");
        return s;
    }
    public static String fingerprint(SettingsState s) { return new Gson().toJson(s); }
}

