package dev.gitcommitforai.core;

import java.util.function.Consumer;

/** Incremental SSE/NDJSON framing, including a final event without a newline. */
public final class StreamParser {
    private final boolean sse;
    private final Consumer<String> consumer;
    private final StringBuilder pending = new StringBuilder(), data = new StringBuilder();
    public StreamParser(boolean sse, Consumer<String> consumer) { this.sse = sse; this.consumer = consumer; }
    public void accept(String text) {
        pending.append(text);
        int newline;
        while ((newline = pending.indexOf("\n")) >= 0) {
            String line = pending.substring(0, newline);
            pending.delete(0, newline + 1);
            line(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
        }
        if (pending.length() > 1024 * 1024) throw new IllegalArgumentException("模型返回的单个事件过大。");
    }
    private void line(String line) {
        if (!sse) { if (!line.isBlank()) consumer.accept(line); return; }
        if (line.isEmpty()) { dispatch(); return; }
        if (line.startsWith("data:")) {
            if (!data.isEmpty()) data.append('\n');
            String value = line.substring(5);
            data.append(value.startsWith(" ") ? value.substring(1) : value);
        }
    }
    private void dispatch() { if (!data.isEmpty()) { String value = data.toString(); data.setLength(0); consumer.accept(value); } }
    public void finish() { if (!pending.isEmpty()) { line(pending.toString()); pending.setLength(0); } dispatch(); }
}

