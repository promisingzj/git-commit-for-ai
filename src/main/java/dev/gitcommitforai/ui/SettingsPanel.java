package dev.gitcommitforai.ui;

import com.google.gson.GsonBuilder;
import dev.gitcommitforai.core.*;
import dev.gitcommitforai.settings.PluginSettings;
import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.event.*;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

public final class SettingsPanel extends JPanel {
    public interface ConnectionTest { void run(ModelProfile profile, String keyOverride, boolean useProxy, Consumer<String> result); }
    private SettingsState state;
    private final ConnectionTest test;
    private final Map<String, String> keyChanges = new HashMap<>();
    private final DefaultListModel<ModelProfile> models = new DefaultListModel<>();
    private final DefaultListModel<PromptTemplate> templates = new DefaultListModel<>();
    private final JList<ModelProfile> modelList = new JList<>(models);
    private final JList<PromptTemplate> templateList = new JList<>(templates);
    private final JTextField modelName = new JTextField(), endpoint = new JTextField(), modelId = new JTextField(), templateName = new JTextField();
    private final JPasswordField apiKey = new JPasswordField();
    private final JComboBox<String> protocol = new JComboBox<>(new String[]{"OpenAI 兼容", "Ollama 原生"});
    private final JComboBox<String> auth = new JComboBox<>(new String[]{"Bearer Token", "api-key 请求头", "不发送鉴权"});
    private final JSpinner timeout = new JSpinner(new SpinnerNumberModel(120, 10, 600, 10));
    private final JCheckBox stream = new JCheckBox("流式生成，逐步显示结果", true);
    private final JTextArea extra = area(3, false), prompt = area(14, true), preview = area(7, true), excludes = area(8, true);
    private final JLabel keyHint = hint("API Key 单独保存在 IDE PasswordSafe 中。");
    private final JLabel testStatus = hint("点击测试会发送一条很短的测试请求。");
    private final JLabel previewInfo = hint("渲染预览 · 示例数据");
    private final JComboBox<String> language = new JComboBox<>(new String[]{"简体中文", "English", "日本語", "한국어", "Deutsch"});
    private final JSpinner maxChars = new JSpinner(new SpinnerNumberModel(24000, 1000, 200000, 1000));
    private final JCheckBox useProxy = new JCheckBox("使用 IDE / 系统代理");
    private final JCheckBox enableExcludes = new JCheckBox("按下列规则排除文件");
    private final JCheckBox projectPrompt = new JCheckBox("项目存在 commit-prompt.txt 时优先使用该模板");
    private final JButton testButton = new JButton("测试连接");
    private final javax.swing.Timer previewTimer;
    private ModelProfile editingModel;
    private PromptTemplate editingTemplate;
    private boolean loading, keyDirty;
    private final JTabbedPane tabs = new JTabbedPane();

    public SettingsPanel(SettingsState initial, ConnectionTest test) {
        super(new BorderLayout(0, 18));
        this.test = test; this.state = initial.copy();
        setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        setPreferredSize(new Dimension(900, 660));
        setMinimumSize(new Dimension(720, 520));
        previewTimer = new javax.swing.Timer(120, e -> updatePreview()); previewTimer.setRepeats(false);
        add(header(), BorderLayout.NORTH);
        tabs.addTab("模型配置", buildModels()); tabs.addTab("Prompt 模板", buildPrompts()); tabs.addTab("生成选项", buildOptions());
        add(tabs, BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout(12, 0));
        footer.add(hint("配置在应用后长期保存 · API Key 不写入配置导出文件"), BorderLayout.WEST);
        footer.add(buttons(button("导入配置", this::importConfig), button("导出配置", this::exportConfig)), BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);
        modelList.addListSelectionListener(e -> { if (!e.getValueIsAdjusting() && !loading) { storeModel(); loadModel(modelList.getSelectedValue()); } });
        templateList.addListSelectionListener(e -> { if (!e.getValueIsAdjusting() && !loading) { storeTemplate(); loadTemplate(templateList.getSelectedValue()); } });
        listen(apiKey, () -> { if (!loading) keyDirty = true; });
        listen(prompt, () -> { if (!loading) previewTimer.restart(); });
        listen(templateName, () -> { if (!loading) templateList.repaint(); });
        protocol.addActionListener(e -> {
            if (!loading && editingModel != null && endpoint.getText().contains("your-provider.example") && protocol.getSelectedIndex() == 1)
                endpoint.setText("http://localhost:11434/api/generate");
        });
        reload(initial);
    }
    private JPanel header() {
        JPanel p = new JPanel(new BorderLayout());
        JPanel text = vertical();
        JLabel title = new JLabel("git commit for ai"); title.setFont(title.getFont().deriveFont(Font.BOLD, 23f));
        text.add(title); text.add(Box.createVerticalStrut(7)); text.add(hint("让每一次变更，都有清晰的表达。"));
        p.add(text, BorderLayout.CENTER);
        JLabel badge = new JLabel(" AI  /  COMMIT "); badge.setForeground(accent()); badge.setFont(badge.getFont().deriveFont(Font.BOLD, 11f));
        badge.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(lineColor()), BorderFactory.createEmptyBorder(7, 9, 7, 9)));
        JPanel side = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 5)); side.add(badge); p.add(side, BorderLayout.EAST);
        return p;
    }
    private JComponent buildModels() {
        modelList.setCellRenderer(new ModelRenderer());
        JPanel left = listPanel(modelList, buttons(button("＋ 添加", this::addModel), button("复制", this::duplicateModel), button("删除", this::removeModel)));
        JPanel form = vertical();
        form.setBorder(BorderFactory.createEmptyBorder(14, 16, 10, 10));
        form.add(section("连接配置", "可保存多个服务和模型，按需切换。"));
        form.add(pair(field("配置名称", modelName), field("接口协议", protocol)));
        form.add(field("接口地址", endpoint));
        form.add(hint("兼容接口可填完整地址；本地 Ollama 请切换到原生协议。"));
        form.add(Box.createVerticalStrut(10));
        form.add(pair(field("模型 ID", modelId), field("鉴权方式", auth)));
        JPanel keyRow = new JPanel(new BorderLayout(8, 0)); keyRow.add(apiKey, BorderLayout.CENTER); keyRow.add(button("清除已保存 Key", this::clearKey), BorderLayout.EAST);
        form.add(field("API Key", keyRow)); form.add(keyHint); form.add(Box.createVerticalStrut(10));
        JPanel misc = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0)); misc.add(new JLabel("超时（秒）  ")); misc.add(timeout); misc.add(Box.createHorizontalStrut(15)); misc.add(stream);
        form.add(misc); form.add(Box.createVerticalStrut(12));
        form.add(field("附加请求参数 · JSON，可选", scroll(extra)));
        form.add(hint("例如 {\"temperature\":0.2}；不填写则使用服务默认参数。"));
        form.add(Box.createVerticalStrut(14));
        testButton.addActionListener(e -> testConnection());
        form.add(buttons(testButton, button("设为默认模型", () -> { storeModel(); if (editingModel != null) state.activeModelId = editingModel.id; modelList.repaint(); })));
        form.add(Box.createVerticalStrut(6)); form.add(testStatus); form.add(Box.createVerticalGlue());
        return split(left, scroll(form));
    }
    private JComponent buildPrompts() {
        templateList.setCellRenderer(new TemplateRenderer());
        JPanel left = listPanel(templateList, buttons(button("＋ 添加", this::addTemplate), button("复制", this::duplicateTemplate), button("删除", this::removeTemplate)));
        JPanel right = new JPanel(new BorderLayout(0, 10)); right.setBorder(BorderFactory.createEmptyBorder(14, 16, 10, 10));
        JPanel top = vertical(); top.add(section("Prompt 工作台", "模板保持简洁，把表达风格留给你。")); top.add(field("模板名称", templateName));
        JPanel chips = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        for (String token : List.of("{diff}", "{language}", "{project}", "{branch}")) {
            JButton chip = button(token, () -> { prompt.replaceSelection(token); prompt.requestFocusInWindow(); });
            chip.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11)); chip.setMargin(new Insets(3, 7, 3, 7)); chips.add(chip);
        }
        top.add(chips); top.add(Box.createVerticalStrut(5)); top.add(hint("点击占位符插入 · {diff} 必填 · 预览只使用内置示例"));
        right.add(top, BorderLayout.NORTH);
        JPanel edit = new JPanel(new BorderLayout(0, 5)); edit.add(hint("模板内容"), BorderLayout.NORTH); edit.add(scroll(prompt), BorderLayout.CENTER);
        preview.setEditable(false);
        JPanel rendered = new JPanel(new BorderLayout(0, 5)); rendered.add(previewInfo, BorderLayout.NORTH); rendered.add(scroll(preview), BorderLayout.CENTER);
        JSplitPane editors = new JSplitPane(JSplitPane.VERTICAL_SPLIT, edit, rendered);
        editors.setBorder(null); editors.setResizeWeight(0.64); editors.setDividerSize(5); right.add(editors, BorderLayout.CENTER);
        right.add(buttons(button("设为默认模板", () -> { storeTemplate(); if (editingTemplate != null) state.activeTemplateId = editingTemplate.id; templateList.repaint(); }),
            button("补充内置模板", this::restoreTemplates)), BorderLayout.SOUTH);
        return split(left, right);
    }
    private JComponent buildOptions() {
        JPanel p = vertical(); p.setBorder(BorderFactory.createEmptyBorder(18, 20, 18, 20));
        p.add(section("生成偏好", "控制输出语言、上下文与网络行为。"));
        language.setEditable(true); p.add(field("输出语言", language)); p.add(field("Prompt 字符上限", maxChars));
        p.add(hint("最终请求严格限制字符数；这是字符预算，不是 token 预算。")); p.add(Box.createVerticalStrut(16));
        p.add(useProxy); p.add(Box.createVerticalStrut(10)); p.add(projectPrompt); p.add(Box.createVerticalStrut(20));
        p.add(section("文件排除", "仅处理提交窗口勾选的变更；二进制文件自动跳过。"));
        p.add(enableExcludes); p.add(Box.createVerticalStrut(8)); p.add(scroll(excludes));
        p.add(Box.createVerticalStrut(6)); p.add(hint("每行一个规则，支持 *、**、?；以 # 开头的行作为注释。"));
        p.add(Box.createVerticalStrut(16)); p.add(hint("生成只填写提交信息，不自动提交、不改写 Git 历史。")); p.add(Box.createVerticalGlue());
        return scroll(p);
    }
    private void storeModel() {
        if (loading || editingModel == null) return;
        editingModel.name = modelName.getText().strip(); editingModel.endpoint = endpoint.getText().strip(); editingModel.model = modelId.getText().strip();
        editingModel.protocol = protocol.getSelectedIndex() == 1 ? "OLLAMA" : "OPENAI";
        editingModel.authMode = switch (auth.getSelectedIndex()) { case 1 -> "API_KEY"; case 2 -> "NONE"; default -> "BEARER"; };
        editingModel.timeoutSeconds = (Integer) timeout.getValue(); editingModel.stream = stream.isSelected(); editingModel.extraBodyJson = extra.getText();
        if (keyDirty) {
            char[] password = apiKey.getPassword();
            String entered = new String(password).strip(); java.util.Arrays.fill(password, '\0');
            if (!entered.isEmpty()) keyChanges.put(editingModel.id, entered);
            else if (!"".equals(keyChanges.get(editingModel.id))) keyChanges.remove(editingModel.id);
            keyDirty = false;
        }
        modelList.repaint();
    }
    private void loadModel(ModelProfile p) {
        loading = true; editingModel = p;
        modelName.setText(p == null ? "" : p.name); endpoint.setText(p == null ? "" : p.endpoint); modelId.setText(p == null ? "" : p.model);
        protocol.setSelectedIndex(p != null && p.protocol.equals("OLLAMA") ? 1 : 0);
        auth.setSelectedIndex(p == null ? 0 : switch (p.authMode) { case "API_KEY" -> 1; case "NONE" -> 2; default -> 0; });
        timeout.setValue(p == null ? 120 : p.timeoutSeconds); stream.setSelected(p == null || p.stream); extra.setText(p == null ? "{}" : p.extraBodyJson);
        apiKey.setText(p != null && keyChanges.containsKey(p.id) ? keyChanges.get(p.id) : "");
        keyHint.setText(p != null && p.credentialConfigured ? "已保存 Key · 此处留空会保留原 Key，修改后点击应用更新。" : "Key 可选；本地服务通常无需填写，已输入的 Key 会在应用后安全保存。");
        for (JComponent c : List.of(modelName, endpoint, modelId, protocol, auth, timeout, stream, extra, apiKey, testButton)) c.setEnabled(p != null);
        keyDirty = false; loading = false;
    }
    private void storeTemplate() {
        if (loading || editingTemplate == null) return;
        editingTemplate.name = templateName.getText().strip(); editingTemplate.content = prompt.getText(); templateList.repaint();
    }
    private void loadTemplate(PromptTemplate t) {
        loading = true; editingTemplate = t; templateName.setText(t == null ? "" : t.name); prompt.setText(t == null ? "" : t.content);
        templateName.setEnabled(t != null); prompt.setEnabled(t != null); prompt.setCaretPosition(0); loading = false; updatePreview();
    }
    private void addModel() {
        storeModel(); ModelProfile p = new ModelProfile(); p.name = "模型 " + (state.models.size() + 1);
        state.models.add(p); models.addElement(p); modelList.setSelectedValue(p, true);
        if (state.activeModelId.isEmpty()) state.activeModelId = p.id;
    }
    private void duplicateModel() {
        storeModel(); if (editingModel == null) return;
        ModelProfile p = editingModel.copy(); p.id = UUID.randomUUID().toString(); p.name += " 副本"; p.credentialConfigured = false;
        state.models.add(p); models.addElement(p); modelList.setSelectedValue(p, true);
    }
    private void removeModel() {
        if (editingModel == null) return; String id = editingModel.id; int index = modelList.getSelectedIndex();
        loading = true; state.models.removeIf(m -> m.id.equals(id)); models.remove(index); keyChanges.remove(id); editingModel = null; loading = false;
        if (id.equals(state.activeModelId)) state.activeModelId = state.models.isEmpty() ? "" : state.models.getFirst().id;
        modelList.setSelectedIndex(models.isEmpty() ? -1 : Math.min(index, models.size() - 1)); loadModel(modelList.getSelectedValue());
    }
    private void clearKey() {
        if (editingModel == null) return; loading = true; apiKey.setText(""); keyDirty = false;
        keyChanges.put(editingModel.id, ""); editingModel.credentialConfigured = false; keyHint.setText("此 Key 将在点击应用后移除。"); loading = false;
    }
    private void addTemplate() {
        storeTemplate(); PromptTemplate t = new PromptTemplate(); t.name = "我的模板 " + (state.templates.size() + 1); t.content = "请用 {language} 根据以下变更生成简洁提交信息，仅输出结果。\n\n{diff}";
        state.templates.add(t); templates.addElement(t); templateList.setSelectedValue(t, true);
    }
    private void duplicateTemplate() {
        storeTemplate(); if (editingTemplate == null) return; PromptTemplate t = editingTemplate.copy(); t.id = UUID.randomUUID().toString(); t.name += " 副本";
        state.templates.add(t); templates.addElement(t); templateList.setSelectedValue(t, true);
    }
    private void removeTemplate() {
        if (editingTemplate == null || templates.size() <= 1) return;
        String id = editingTemplate.id; int index = templateList.getSelectedIndex(); loading = true;
        state.templates.removeIf(t -> t.id.equals(id)); templates.remove(index); editingTemplate = null; loading = false;
        if (id.equals(state.activeTemplateId)) state.activeTemplateId = state.templates.getFirst().id;
        templateList.setSelectedIndex(Math.min(index, templates.size() - 1)); loadTemplate(templateList.getSelectedValue());
    }
    private void restoreTemplates() {
        storeTemplate();
        for (PromptTemplate t : SettingsState.defaults()) if (state.templates.stream().noneMatch(existing -> existing.id.equals(t.id))) { state.templates.add(t); templates.addElement(t); }
    }
    private void testConnection() {
        storeModel(); if (editingModel == null) return;
        try { ModelClient.validate(editingModel); } catch (Exception e) { testStatus.setText(e.getMessage()); return; }
        ModelProfile p = editingModel.copy(); testButton.setEnabled(false); testStatus.setText("正在连接…");
        test.run(p, keyChanges.get(p.id), useProxy.isSelected(), result -> {
            testButton.setEnabled(editingModel != null); testStatus.setText(result); testStatus.setToolTipText(result);
        });
    }
    private void updatePreview() {
        try {
            String text = PromptEngine.render(prompt.getText(), """
                diff --git a/src/SessionService.java b/src/SessionService.java
                @@ -18,1 +18,2 @@
                -return cache.get(userId);
                +return cache.computeIfAbsent(userId, repository::findById);
                """, Objects.toString(language.getSelectedItem(), "简体中文"), "sample-project", "feature/session-cache", (Integer) maxChars.getValue());
            preview.setText(text); previewInfo.setText("渲染预览 · 示例数据 · " + text.length() + " 字符"); preview.setCaretPosition(0);
        } catch (Exception e) { preview.setText(e.getMessage()); previewInfo.setText("请检查占位符与长度设置"); }
    }
    public SettingsState draft() {
        storeModel(); storeTemplate(); state.language = Objects.toString(language.getEditor().getItem(), "简体中文");
        state.maxPromptChars = (Integer) maxChars.getValue(); state.useIdeProxy = useProxy.isSelected();
        state.excludeFiles = enableExcludes.isSelected(); state.excludePatterns = excludes.getText(); state.useProjectPrompt = projectPrompt.isSelected();
        return state.copy();
    }
    public Map<String, String> keyChanges() { storeModel(); return new HashMap<>(keyChanges); }
    public void reload(SettingsState next) {
        loading = true; state = next.copy(); keyChanges.clear(); models.clear(); templates.clear();
        state.models.forEach(models::addElement); state.templates.forEach(templates::addElement);
        language.setSelectedItem(state.language); maxChars.setValue(state.maxPromptChars); useProxy.setSelected(state.useIdeProxy);
        enableExcludes.setSelected(state.excludeFiles); excludes.setText(state.excludePatterns); projectPrompt.setSelected(state.useProjectPrompt);
        modelList.setSelectedValue(state.activeModel(), true); templateList.setSelectedValue(state.activeTemplate(), true); loading = false;
        loadModel(modelList.getSelectedValue()); loadTemplate(templateList.getSelectedValue());
    }
    public void showPage(int index) { tabs.setSelectedIndex(index); }
    public void dispose() { previewTimer.stop(); keyChanges.clear(); apiKey.setText(""); }
    private void exportConfig() {
        JFileChooser chooser = new JFileChooser(); chooser.setSelectedFile(new java.io.File("git-commit-for-ai-config.json"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            if (Files.exists(chooser.getSelectedFile().toPath()) && JOptionPane.showConfirmDialog(this, "覆盖现有文件？", "导出配置", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
            SettingsState exported = draft(); exported.models.forEach(m -> m.credentialConfigured = false);
            Files.writeString(chooser.getSelectedFile().toPath(), new GsonBuilder().setPrettyPrinting().create().toJson(exported), StandardCharsets.UTF_8);
            JOptionPane.showMessageDialog(this, "已导出模型配置和模板。API Key 不包含在文件中。", "导出完成", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception e) { error(e); }
    }
    private void importConfig() {
        JFileChooser chooser = new JFileChooser(); if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            if (Files.size(chooser.getSelectedFile().toPath()) > 2 * 1024 * 1024) throw new IllegalArgumentException("配置文件过大。");
            SettingsState imported = new GsonBuilder().create().fromJson(Files.readString(chooser.getSelectedFile().toPath(), StandardCharsets.UTF_8), SettingsState.class);
            if (imported == null) throw new IllegalArgumentException("配置文件为空。");
            imported = PluginSettings.normalize(imported);
            for (ModelProfile m : imported.models) {
                String old = m.id; m.id = UUID.randomUUID().toString(); m.credentialConfigured = false;
                if (old.equals(imported.activeModelId)) imported.activeModelId = m.id;
            }
            if (JOptionPane.showConfirmDialog(this, "导入会替换当前草稿，点击应用后生效。API Key 需重新填写。", "导入配置", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) reload(imported);
        } catch (Exception e) { error(e); }
    }
    private void error(Exception e) { JOptionPane.showMessageDialog(this, e.getMessage(), "配置错误", JOptionPane.ERROR_MESSAGE); }
    private final class ModelRenderer extends DefaultListCellRenderer {
        @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
            super.getListCellRendererComponent(list, value, index, selected, focus);
            ModelProfile p = (ModelProfile) value; setText((p.id.equals(state.activeModelId) ? "● " : "   ") + (p.name.isBlank() ? "未命名模型" : p.name));
            setBorder(BorderFactory.createEmptyBorder(12, 10, 12, 8)); return this;
        }
    }
    private final class TemplateRenderer extends DefaultListCellRenderer {
        @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
            super.getListCellRendererComponent(list, value, index, selected, focus);
            PromptTemplate t = (PromptTemplate) value; setText((t.id.equals(state.activeTemplateId) ? "● " : "   ") + t.name);
            setBorder(BorderFactory.createEmptyBorder(12, 10, 12, 8)); return this;
        }
    }
    private static JPanel vertical() {
        JPanel p = new JPanel() {
            @Override protected void addImpl(Component component, Object constraints, int index) {
                if (component instanceof JComponent child) child.setAlignmentX(Component.LEFT_ALIGNMENT);
                super.addImpl(component, constraints, index);
            }
        };
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS)); return p;
    }
    private static JPanel pair(JComponent first, JComponent second) {
        JPanel p = new JPanel(new GridLayout(1, 2, 14, 0)); p.add(first); p.add(second);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, Math.max(first.getPreferredSize().height, second.getPreferredSize().height)));
        return p;
    }
    private static JPanel field(String title, JComponent input) {
        JPanel p = new JPanel(new BorderLayout(0, 5)); p.setBorder(BorderFactory.createEmptyBorder(0, 0, 12, 0));
        JLabel label = new JLabel(title); label.setFont(label.getFont().deriveFont(Font.BOLD, 12f)); p.add(label, BorderLayout.NORTH); p.add(input, BorderLayout.CENTER);
        p.setAlignmentX(Component.LEFT_ALIGNMENT); p.setMaximumSize(new Dimension(Integer.MAX_VALUE, input.getPreferredSize().height + 32)); return p;
    }
    private static JPanel section(String title, String subtitle) {
        JPanel p = vertical(); JLabel label = new JLabel(title); label.setFont(label.getFont().deriveFont(Font.BOLD, 15f));
        p.add(label); p.add(Box.createVerticalStrut(5)); p.add(hint(subtitle)); p.add(Box.createVerticalStrut(15)); return p;
    }
    private static JPanel buttons(JButton... buttons) { JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0)); for (JButton b : buttons) p.add(b); return p; }
    private static JButton button(String title, Runnable action) { JButton b = new JButton(title); b.addActionListener(e -> action.run()); return b; }
    private static JTextArea area(int rows, boolean wrap) {
        JTextArea a = new JTextArea(rows, 32); a.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12)); a.setLineWrap(wrap); a.setWrapStyleWord(true);
        a.setBorder(BorderFactory.createEmptyBorder(9, 10, 9, 10)); return a;
    }
    private static JScrollPane scroll(Component component) { JScrollPane s = new JScrollPane(component); s.setBorder(BorderFactory.createLineBorder(lineColor())); s.getVerticalScrollBar().setUnitIncrement(16); return s; }
    private static JPanel listPanel(JList<?> list, JPanel controls) {
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); list.setVisibleRowCount(8);
        JPanel p = new JPanel(new BorderLayout(0, 10)); p.setBorder(BorderFactory.createEmptyBorder(14, 0, 10, 0));
        p.add(scroll(list), BorderLayout.CENTER); p.add(controls, BorderLayout.SOUTH); p.setMinimumSize(new Dimension(210, 250)); return p;
    }
    private static JSplitPane split(Component left, Component right) {
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right); split.setBorder(null); split.setDividerLocation(225); split.setResizeWeight(0); split.setDividerSize(5); return split;
    }
    private static JLabel hint(String text) { JLabel label = new JLabel(text); label.setFont(label.getFont().deriveFont(11f)); label.setForeground(muted()); return label; }
    private static Color lineColor() { Color c = UIManager.getColor("Component.borderColor"); return c != null ? c : new Color(150, 155, 170, 90); }
    private static Color muted() {
        Color foreground = UIManager.getColor("Label.foreground"), background = UIManager.getColor("Panel.background");
        if (foreground == null || background == null) return Color.GRAY;
        return new Color((int)(foreground.getRed() * .7 + background.getRed() * .3), (int)(foreground.getGreen() * .7 + background.getGreen() * .3), (int)(foreground.getBlue() * .7 + background.getBlue() * .3));
    }
    private static Color accent() { Color c = UIManager.getColor("Component.focusColor"); return c != null ? c : new Color(112, 117, 225); }
    private static void listen(JTextComponent component, Runnable action) {
        component.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { action.run(); } public void removeUpdate(DocumentEvent e) { action.run(); } public void changedUpdate(DocumentEvent e) { action.run(); }
        });
    }
}

