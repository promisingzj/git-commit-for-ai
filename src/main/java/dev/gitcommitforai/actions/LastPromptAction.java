package dev.gitcommitforai.actions;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.ui.*;
import dev.gitcommitforai.git.AiCommitSession;
import org.jetbrains.annotations.NotNull;
import javax.swing.*;
import java.awt.*;

public final class LastPromptAction extends AnAction {
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
    @Override public void update(@NotNull AnActionEvent e) { e.getPresentation().setEnabled(e.getProject() != null); }
    @Override public void actionPerformed(@NotNull AnActionEvent e) {
        if (e.getProject() == null) return;
        String text = AiCommitSession.get(e.getProject()).getLastPrompt();
        if (text.isEmpty()) { Messages.showInfoMessage(e.getProject(), "本项目还没有生成过提交信息。", "上次 Prompt"); return; }
        new DialogWrapper(e.getProject()) {
            { setTitle("上次发送的 Prompt"); setOKButtonText("关闭"); init(); }
            @Override protected JComponent createCenterPanel() {
                JTextArea area = new JTextArea(text); area.setEditable(false); area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13)); area.setCaretPosition(0);
                JScrollPane pane = new JScrollPane(area); pane.setPreferredSize(new Dimension(820, 550)); return pane;
            }
            @Override protected Action[] createActions() { return new Action[]{getOKAction()}; }
        }.show();
    }
}

