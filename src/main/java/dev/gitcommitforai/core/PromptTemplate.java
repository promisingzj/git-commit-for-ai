package dev.gitcommitforai.core;

import java.util.UUID;
import com.intellij.util.xmlb.annotations.OptionTag;

public final class PromptTemplate {
    public String id = UUID.randomUUID().toString();
    @OptionTag(converter = UnicodeXmlConverter.class) public String name = "新模板";
    @OptionTag(converter = UnicodeXmlConverter.class) public String content = "";
    public PromptTemplate() {}
    public PromptTemplate(String id, String name, String content) { this.id = id; this.name = name; this.content = content; }
    public PromptTemplate copy() { return new PromptTemplate(id, name, content); }
    @Override public String toString() { return name; }
}

