package dev.gitcommitforai;

import com.intellij.openapi.util.JDOMUtil;
import com.intellij.util.xmlb.XmlSerializer;
import dev.gitcommitforai.core.*;
import dev.gitcommitforai.settings.PluginSettings;
import java.nio.file.*;
import java.util.List;

/** Exercises the same XML codec in two separate JVM processes without a running IDE. */
public final class PersistenceProbe {
    public static void main(String[] args) throws Exception {
        Path path = Path.of(args[1]);
        if (args[0].equals("write")) {
            SettingsState state = new SettingsState();
            ModelProfile a = new ModelProfile(); a.id = "persistent-cloud"; a.name = "我的模型 🚀"; a.model = "custom-model";
            ModelProfile b = new ModelProfile(); b.id = "persistent-local"; b.name = "本地模型"; b.protocol = "OLLAMA"; b.model = "local-model";
            state.models.addAll(List.of(a, b)); state.activeModelId = a.id;
            state.templates.add(new PromptTemplate("persistent-template", "我的模板 ✨", "请用 {language} 总结 🚀：{diff}"));
            state.activeTemplateId = "persistent-template"; state.maxPromptChars = 32000;
            PluginSettings service = new PluginSettings(); service.replace(state);
            Files.writeString(path, JDOMUtil.writeElement(XmlSerializer.serialize(service.getState())));
            System.out.println("PASS persistence process 1: model profiles and templates written to XML");
        } else {
            PluginSettings service = new PluginSettings();
            service.loadState(XmlSerializer.deserialize(JDOMUtil.load(path), SettingsState.class));
            SettingsState loaded = service.snapshot();
            if (loaded.models.size() != 2 || !loaded.activeModel().name.equals("我的模型 🚀") || loaded.maxPromptChars != 32000 || !loaded.activeTemplate().content.contains("🚀"))
                throw new AssertionError("Preferences did not survive a new JVM process.");
            System.out.println("PASS persistence process 2: profiles, templates, defaults and Unicode restored");
        }
    }
}
