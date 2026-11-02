package dev.gitcommitforai.core;

import com.intellij.util.xmlb.Converter;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Preserve supplementary Unicode characters through the IDE's XML sanitizer. Not encryption. */
public final class UnicodeXmlConverter extends Converter<String> {
    private static final String PREFIX = "utf8-base64:";
    @Override public String fromString(String value) {
        if (!value.startsWith(PREFIX)) return value;
        return new String(Base64.getDecoder().decode(value.substring(PREFIX.length())), StandardCharsets.UTF_8);
    }
    @Override public String toString(String value) {
        return PREFIX + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
