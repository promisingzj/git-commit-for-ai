package dev.gitcommitforai.core;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PromptEngine {
    private static final Pattern TOKENS = Pattern.compile("\\{(diff|language|project|branch)\\}");
    private static final String CUT = "\n[其余变更因长度限制省略]";
    private PromptEngine() {}

    public static String render(String template, String diff, String language, String project, String branch, int limit) {
        if (template == null || !template.contains("{diff}"))
            throw new IllegalArgumentException("Prompt 模板必须包含 {diff} 占位符。");
        if (limit < 1000) throw new IllegalArgumentException("Prompt 长度上限至少为 1000。");
        long occurrences = TOKENS.matcher(template).results().filter(m -> m.group(1).equals("diff")).count();
        Map<String, String> values = Map.of("diff", "", "language", language, "project", project, "branch", branch);
        int fixedLength = replace(template, values).length();
        int available = (int) Math.max(0L, ((long) limit - fixedLength) / occurrences);
        if (available < 256) throw new IllegalArgumentException("模板过长，留给代码变更的空间不足。请精简模板或增大长度上限。");
        String bounded = truncate(diff, available, CUT);
        return replace(template, Map.of("diff", bounded, "language", language, "project", project, "branch", branch));
    }
    private static String replace(String template, Map<String, String> values) {
        return TOKENS.matcher(template).replaceAll(m -> Matcher.quoteReplacement(values.get(m.group(1))));
    }
    public static String truncate(String text, int max, String suffix) {
        if (text.length() <= max) return text;
        if (max <= suffix.length()) return suffix.substring(0, Math.max(0, max));
        int end = max - suffix.length();
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end) + suffix;
    }
    public static String cleanMessage(String text) {
        String result = text.strip();
        if (result.startsWith("```") && result.endsWith("```") && result.indexOf('\n') > 0)
            result = result.substring(result.indexOf('\n') + 1, result.length() - 3).strip();
        if (result.isBlank()) throw new IllegalArgumentException("模型没有返回可用的提交信息，请检查模型和接口配置。");
        return result;
    }
}

