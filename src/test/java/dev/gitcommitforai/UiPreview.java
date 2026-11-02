package dev.gitcommitforai;

import dev.gitcommitforai.core.*;
import dev.gitcommitforai.ui.SettingsPanel;
import javax.swing.*;
import javax.swing.plaf.FontUIResource;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;

public final class UiPreview {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]); Files.createDirectories(output);
        SwingUtilities.invokeAndWait(() -> {
            try {
                UIManager.setLookAndFeel("javax.swing.plaf.nimbus.NimbusLookAndFeel");
                UIManager.put("defaultFont", new FontUIResource("Dialog", Font.PLAIN, 12));
                SettingsState state = new SettingsState();
                ModelProfile cloud = new ModelProfile(); cloud.name = "我的云端模型"; cloud.endpoint = "https://models.example.org/v1/chat/completions"; cloud.model = "my-coding-model"; cloud.credentialConfigured = true;
                ModelProfile local = new ModelProfile(); local.name = "本地 Ollama"; local.protocol = "OLLAMA"; local.endpoint = "http://localhost:11434/api/generate"; local.model = "local-model";
                state.models.add(cloud); state.models.add(local); state.activeModelId = cloud.id;
                SettingsPanel panel = new SettingsPanel(state, (p,k,proxy,done) -> done.accept("预览模式"));
                panel.setSize(1020, 800);
                for (int page = 0; page < 3; page++) {
                    panel.showPage(page); layout(panel);
                    ImageIO.write(render(panel), "png", output.resolve("settings-" + page + ".png").toFile());
                }
                panel.dispose();
                System.out.println("UI preview saved: " + output);
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }
    private static void layout(Container c) { c.doLayout(); for (Component child : c.getComponents()) if (child instanceof Container container) layout(container); }
    private static BufferedImage render(JComponent component) {
        BufferedImage image = new BufferedImage(component.getWidth(), component.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON); component.printAll(g); g.dispose(); return image;
    }
}

