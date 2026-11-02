package dev.gitcommitforai.core;

import java.util.UUID;
import com.intellij.util.xmlb.annotations.OptionTag;

public final class ModelProfile {
    public String id = UUID.randomUUID().toString();
    @OptionTag(converter = UnicodeXmlConverter.class) public String name = "自定义模型";
    public String protocol = "OPENAI";
    public String endpoint = "https://your-provider.example/v1/chat/completions";
    @OptionTag(converter = UnicodeXmlConverter.class) public String model = "";
    public int timeoutSeconds = 120;
    public String authMode = "BEARER";
    @OptionTag(converter = UnicodeXmlConverter.class) public String extraBodyJson = "{}";
    public boolean stream = true;
    public boolean credentialConfigured = false;

    public ModelProfile() {}
    public ModelProfile copy() {
        ModelProfile p = new ModelProfile();
        p.id = id; p.name = name; p.protocol = protocol; p.endpoint = endpoint;
        p.model = model; p.timeoutSeconds = timeoutSeconds; p.credentialConfigured = credentialConfigured;
        p.authMode = authMode; p.extraBodyJson = extraBodyJson; p.stream = stream;
        return p;
    }
    @Override public String toString() { return name; }
}

