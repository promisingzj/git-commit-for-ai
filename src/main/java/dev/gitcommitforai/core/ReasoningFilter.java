package dev.gitcommitforai.core;

import java.util.function.Consumer;

/** Removes think blocks across arbitrary chunk boundaries without retaining hidden reasoning. */
public final class ReasoningFilter {
    private final StringBuilder buffer = new StringBuilder();
    private final Consumer<String> output;
    private boolean hidden;
    public ReasoningFilter(Consumer<String> output) { this.output = output; }
    public void accept(String text) {
        buffer.append(text);
        while (true) {
            String tag = hidden ? "</think>" : "<think>";
            int at = buffer.indexOf(tag);
            if (at >= 0) {
                if (!hidden && at > 0) output.accept(buffer.substring(0, at));
                buffer.delete(0, at + tag.length());
                hidden = !hidden;
            } else {
                int safe = Math.max(0, buffer.length() - tag.length() + 1);
                if (safe > 0) {
                    if (!hidden) output.accept(buffer.substring(0, safe));
                    buffer.delete(0, safe);
                }
                return;
            }
        }
    }
    public void finish() { if (!hidden && !buffer.isEmpty()) output.accept(buffer.toString()); buffer.setLength(0); }
}

