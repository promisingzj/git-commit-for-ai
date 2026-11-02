package dev.gitcommitforai.actions;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import dev.gitcommitforai.core.*;
import dev.gitcommitforai.settings.PluginSettings;
import dev.gitcommitforai.ui.AiCommitConfigurable;
import org.jetbrains.annotations.NotNull;

public final class SwitchProfileAction extends AnAction {
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
    @Override public void update(@NotNull AnActionEvent e) { e.getPresentation().setEnabledAndVisible(e.getProject() != null); }
    @Override public void actionPerformed(@NotNull AnActionEvent e) {
        PluginSettings settings = PluginSettings.getInstance(); SettingsState s = settings.snapshot();
        DefaultActionGroup group = new DefaultActionGroup();
        group.addSeparator("默认模型");
        for (ModelProfile p : s.models) group.add(new ToggleAction(p.name + "  ·  " + p.model) {
            public boolean isSelected(@NotNull AnActionEvent event) { return p.id.equals(settings.snapshot().activeModelId); }
            public void setSelected(@NotNull AnActionEvent event, boolean selected) { if (selected) settings.select(p.id, null, null); }
        });
        if (s.models.isEmpty()) group.add(new AnAction("添加模型配置…") { public void actionPerformed(@NotNull AnActionEvent event) { ShowSettingsUtil.getInstance().showSettingsDialog(e.getProject(), AiCommitConfigurable.class); }});
        group.addSeparator("默认 Prompt");
        for (PromptTemplate t : s.templates) group.add(new ToggleAction(t.name) {
            public boolean isSelected(@NotNull AnActionEvent event) { return t.id.equals(settings.snapshot().activeTemplateId); }
            public void setSelected(@NotNull AnActionEvent event, boolean selected) { if (selected) settings.select(null, t.id, null); }
        });
        group.addSeparator();
        group.add(new AnAction("模型与 Prompt 设置…") { public void actionPerformed(@NotNull AnActionEvent event) { ShowSettingsUtil.getInstance().showSettingsDialog(e.getProject(), AiCommitConfigurable.class); }});
        JBPopupFactory.getInstance().createActionGroupPopup("模型与 Prompt", group, e.getDataContext(), JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true).showInBestPositionFor(e.getDataContext());
    }
}

