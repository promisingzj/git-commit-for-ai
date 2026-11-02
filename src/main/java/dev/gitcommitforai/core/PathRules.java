package dev.gitcommitforai.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class PathRules {
    private record Rule(Pattern pattern, boolean fileNameOnly) {}
    private final List<Rule> rules = new ArrayList<>();
    public PathRules(String input) {
        for (String line : input.split("\\R")) {
            String glob = line.strip().replace('\\', '/');
            if (glob.isEmpty() || glob.startsWith("#")) continue;
            StringBuilder regex = new StringBuilder("^");
            for (int i = 0; i < glob.length(); i++) {
                char c = glob.charAt(i);
                if (c == '*' && i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    i++;
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') { regex.append("(?:.*/)?"); i++; }
                    else regex.append(".*");
                } else if (c == '*') regex.append("[^/]*");
                else if (c == '?') regex.append("[^/]");
                else regex.append(Pattern.quote(String.valueOf(c)));
            }
            regex.append('$');
            rules.add(new Rule(Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE), !glob.contains("/")));
        }
    }
    public boolean excludes(String path) {
        String normalized = path.replace('\\', '/');
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        for (Rule rule : rules) if (rule.pattern.matcher(rule.fileNameOnly ? name : normalized).matches()) return true;
        return false;
    }
}

