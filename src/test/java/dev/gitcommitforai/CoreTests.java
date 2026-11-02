package dev.gitcommitforai;

import com.google.gson.*;
import com.intellij.util.xmlb.XmlSerializer;
import com.intellij.openapi.util.JDOMUtil;
import com.sun.net.httpserver.*;
import dev.gitcommitforai.core.*;
import dev.gitcommitforai.settings.PluginSettings;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

public final class CoreTests {
    private static int passed;
    private static final AtomicReference<String> requestBody = new AtomicReference<>();
    private static final AtomicReference<String> auth = new AtomicReference<>();
    private static final AtomicReference<String> rawUri = new AtomicReference<>();
    private static HttpServer server;
    private static ModelProfile profile;
    private static String mode;

    public static void main(String[] args) throws Exception {
        test("strict prompt budget and nonrecursive placeholders", CoreTests::promptBudget);
        test("unicode truncation", CoreTests::unicodeCut);
        test("path exclusion at root and nested folders", CoreTests::paths);
        test("incremental SSE multiline framing and EOF", CoreTests::sseFraming);
        test("reasoning tags split at every boundary", CoreTests::reasoning);
        test("XML configuration survives round-trip and rename", CoreTests::xmlRoundTrip);
        test("legacy and null settings normalized safely", CoreTests::normalization);
        test("blank key preserves saved credentials and explicit clear removes them", CoreTests::keyEditing);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", CoreTests::handle);
        ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(workers); server.start();
        profile = new ModelProfile(); profile.endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"; profile.model = "custom-model";
        try {
            test("OpenAI streaming UTF-8 and missing DONE", CoreTests::openAiStream);
            test("OpenAI auth and exact request body", CoreTests::requestFields);
            test("non-stream JSON response", CoreTests::jsonResponse);
            test("JSON response to a streaming request", CoreTests::jsonFallback);
            test("Ollama NDJSON response and EOF", CoreTests::ollama);
            test("HTTP error does not expose the API key", CoreTests::httpError);
            test("streaming service errors redact the API key", CoreTests::streamError);
            test("custom JSON parameters and reserved fields", CoreTests::extraBody);
            test("cancellation while body is idle", CoreTests::cancellation);
            test("endpoint normalization and query preservation", CoreTests::endpoints);
            test("api-key header authentication", CoreTests::apiKeyHeader);
        } finally { server.stop(0); workers.shutdownNow(); }
        System.out.println("RESULT: " + passed + " tests passed.");
    }
    private static void promptBudget() {
        String diff = "{language} " + "change".repeat(3000);
        String result = PromptEngine.render("语言:{language}\n{diff}\n再次:{diff}", diff, "中文", "test", "main", 1200);
        check(result.length() <= 1200, "hard final limit");
        check(result.contains("{language}"), "diff must not undergo placeholder substitution");
        expect(IllegalArgumentException.class, () -> PromptEngine.render("x".repeat(2000) + "{diff}", diff, "en", "p", "b", 1000));
    }
    private static void unicodeCut() {
        String s = "🚀".repeat(1000);
        String value = PromptEngine.truncate(s, 27, "...");
        check(value.length() <= 27, "unicode limit"); check(!Character.isHighSurrogate(value.charAt(value.length() - 4)), "no split surrogate");
    }
    private static void paths() {
        PathRules rules = new PathRules(".env\n**/node_modules/**\n*.min.js\n# comment\n**/target/**");
        for (String s : List.of(".env", "config/.env", "node_modules/a.js", "packages/a/node_modules/b.js", "app.min.js", "target/output")) check(rules.excludes(s), s);
        check(!rules.excludes("src/Main.java"), "source kept"); check(!rules.excludes("src/min.js"), "similar name kept");
    }
    private static void sseFraming() {
        List<String> events = new ArrayList<>(); StreamParser parser = new StreamParser(true, events::add);
        String data = ": comment\r\ndata:first\r\ndata: second\r\n\r\ndata:tail";
        for (int i = 0; i < data.length(); i++) parser.accept(data.substring(i, i + 1));
        parser.finish(); check(events.equals(List.of("first\nsecond", "tail")), "SSE frame recovery");
    }
    private static void reasoning() {
        String input = "feat: <think>private reasoning</think>添加缓存 🚀";
        for (int split = 0; split <= input.length(); split++) {
            StringBuilder out = new StringBuilder(); ReasoningFilter f = new ReasoningFilter(out::append);
            f.accept(input.substring(0, split)); f.accept(input.substring(split)); f.finish();
            check(out.toString().equals("feat: 添加缓存 🚀"), "tag split " + split);
        }
    }
    private static void xmlRoundTrip() throws Exception {
        SettingsState state = new SettingsState();
        ModelProfile a = new ModelProfile(); a.name = "Private endpoint"; a.model = "my-model"; a.endpoint = "https://models.example.org/v1";
        ModelProfile b = new ModelProfile(); b.name = "本地 🚀"; b.model = "local"; b.protocol = "OLLAMA";
        state.models.addAll(List.of(a, b)); state.activeModelId = b.id; state.activeTemplateId = "detail"; state.useProjectPrompt = true;
        state.templates.add(new PromptTemplate("my-template", "我的样式", "仅用 {language} 总结：{diff}"));
        String stableId = b.id; b.name = "本地模型改名后 🚀";
        org.jdom.Element serialized = XmlSerializer.serialize(state);
        String xml = JDOMUtil.writeElement(serialized);
        SettingsState restored = PluginSettings.normalize(XmlSerializer.deserialize(JDOMUtil.load(xml), SettingsState.class));
        check(restored.models.size() == 2, "two profiles persisted");
        check(restored.activeModel().id.equals(stableId), "stable credential identity after rename");
        check(restored.activeModel().name.equals("本地模型改名后 🚀"), "profile name and emoji");
        check(restored.templates.size() == 5 && restored.activeTemplateId.equals("detail"), "templates persisted");
        check(restored.useProjectPrompt, "project template option");
        check(!xml.contains("apiKey") && !xml.contains("secret-test-value"), "no secret field in state");
        check(PluginSettings.fingerprint(restored).equals(PluginSettings.fingerprint(state)), "all preferences round-trip\nexpected: " + PluginSettings.fingerprint(state) + "\nactual: " + PluginSettings.fingerprint(restored));
    }
    private static void normalization() {
        SettingsState old = new SettingsState(); old.templates = null; old.activeModelId = null; old.language = null;
        ModelProfile a = new ModelProfile(); a.protocol = null; a.authMode = null; a.timeoutSeconds = -1;
        old.models.add(a); old.models.add(a.copy()); old.models.get(1).id = a.id;
        SettingsState normalized = PluginSettings.normalize(old);
        check(normalized.templates.size() == 4, "defaults migrated");
        check(!normalized.models.get(0).id.equals(normalized.models.get(1).id), "duplicate IDs repaired");
        check(normalized.models.getFirst().timeoutSeconds == 10, "timeout bounded");
    }
    private static void keyEditing() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            dev.gitcommitforai.ui.SettingsPanel panel = null;
            try {
                SettingsState state = new SettingsState(); ModelProfile m = new ModelProfile(); m.credentialConfigured = true;
                state.models.add(m); state.activeModelId = m.id;
                panel = new dev.gitcommitforai.ui.SettingsPanel(state, (p,k,proxy,result) -> {});
                check(panel.keyChanges().isEmpty(), "untouched blank retains key");
                var field = panel.getClass().getDeclaredField("apiKey"); field.setAccessible(true);
                javax.swing.JPasswordField key = (javax.swing.JPasswordField) field.get(panel);
                key.setText("replacement-key"); check(panel.keyChanges().get(m.id).equals("replacement-key"), "new key staged");
                key.setText(""); check(!panel.keyChanges().containsKey(m.id), "blank reverts to saved key");
                var clear = panel.getClass().getDeclaredMethod("clearKey"); clear.setAccessible(true); clear.invoke(panel);
                check(panel.keyChanges().containsKey(m.id) && panel.keyChanges().get(m.id).isEmpty(), "explicit clear staged");
                check(!panel.draft().models.getFirst().credentialConfigured, "clear reflected in draft");
            } catch (Throwable e) { failure.set(e); } finally { if (panel != null) panel.dispose(); }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
    private static void openAiStream() throws Exception {
        mode = "sse"; profile.protocol = "OPENAI"; profile.stream = true; profile.authMode = "BEARER";
        StringBuilder observed = new StringBuilder();
        String result = new ModelClient().generate(profile, "secret-test-value", "test prompt", false, observed::append, new Cancellation());
        check(result.equals("feat(cache): 添加缓存 🚀"), "complete output and tail flush: " + result);
        check(observed.toString().equals(result), "stream complete"); check(!result.contains("secret thought"), "reasoning stripped");
    }
    private static void requestFields() {
        JsonObject body = JsonParser.parseString(requestBody.get()).getAsJsonObject();
        check(auth.get().equals("Bearer secret-test-value"), "Bearer header");
        check(body.get("model").getAsString().equals("custom-model"), "custom model ID");
        check(body.get("stream").getAsBoolean(), "stream flag");
        check(body.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString().equals("test prompt"), "prompt field");
        check(rawUri.get().equals("/v1/chat/completions"), "endpoint only appended once");
    }
    private static void jsonResponse() throws Exception {
        mode = "json"; profile.stream = false;
        check(new ModelClient().generate(profile, "", "x", false, ignored -> {}, new Cancellation()).equals("fix: handle empty state"), "non-stream response");
    }
    private static void jsonFallback() throws Exception { profile.stream = true; jsonResponse(); profile.stream = true; mode = "json"; check(new ModelClient().generate(profile,"","x",false,ignored -> {},new Cancellation()).equals("fix: handle empty state"), "JSON fallback"); }
    private static void ollama() throws Exception {
        mode = "ollama"; profile.protocol = "OLLAMA"; profile.stream = true;
        String result = new ModelClient().generate(profile, "", "x", false, ignored -> {}, new Cancellation());
        check(result.equals("feat: 本地模型"), "Ollama output");
        check(JsonParser.parseString(requestBody.get()).getAsJsonObject().get("prompt").getAsString().equals("x"), "Ollama prompt");
        profile.protocol = "OPENAI";
    }
    private static void httpError() throws Exception {
        mode = "error";
        try { new ModelClient().generate(profile, "secret-test-value", "x", false, ignored -> {}, new Cancellation()); throw new AssertionError("expected HTTP error"); }
        catch (IllegalArgumentException e) { check(e.getMessage().contains("401") && !e.getMessage().contains("secret-test-value"), "error redaction"); }
    }
    private static void streamError() throws Exception {
        mode = "stream-error";
        try { new ModelClient().generate(profile, "secret-test-value", "x", false, ignored -> {}, new Cancellation()); throw new AssertionError("expected service error"); }
        catch (IllegalArgumentException e) { check(e.getMessage().contains("REDACTED") && !e.getMessage().contains("secret-test-value"), "SSE service error redaction"); }
    }
    private static void extraBody() throws Exception {
        profile.extraBodyJson = "{\"temperature\":0.25,\"max_completion_tokens\":128}"; mode = "json";
        new ModelClient().generate(profile, "", "x", false, ignored -> {}, new Cancellation());
        JsonObject sent = JsonParser.parseString(requestBody.get()).getAsJsonObject(); check(sent.get("temperature").getAsDouble() == 0.25, "extra parameter");
        profile.extraBodyJson = "{\"messages\":[]}"; expect(IllegalArgumentException.class, () -> ModelClient.extraBody(profile)); profile.extraBodyJson = "{}";
    }
    private static void cancellation() throws Exception {
        mode = "idle"; Cancellation cancel = new Cancellation(); ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> request = executor.submit(() -> { try { new ModelClient().generate(profile, "", "x", false, ignored -> {}, cancel); } catch (CancellationException e) { return; } catch (Exception e) { throw new RuntimeException(e); } throw new AssertionError("request should cancel"); });
            Thread.sleep(150); long start = System.nanoTime(); cancel.cancel(); request.get(3, TimeUnit.SECONDS);
            check(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(3), "prompt cancellation");
        } finally { executor.shutdownNow(); }
    }
    private static void endpoints() {
        ModelProfile p = profile.copy(); p.endpoint = "https://provider.example.org/v1/?api-version=2025-01-01";
        check(ModelClient.endpoint(p).toString().equals("https://provider.example.org/v1/chat/completions?api-version=2025-01-01"), "query preserved");
        p.endpoint = "https://provider.example.org/v1/chat/completions"; check(ModelClient.endpoint(p).getPath().equals("/v1/chat/completions"), "full URL unchanged");
        p.endpoint = "file:///tmp/a"; expect(IllegalArgumentException.class, () -> ModelClient.endpoint(p));
    }
    private static void apiKeyHeader() throws Exception {
        profile.authMode = "API_KEY"; mode = "json"; new ModelClient().generate(profile,"azure-key","x",false,ignored -> {},new Cancellation());
        check(auth.get().equals("azure-key"), "api-key header"); profile.authMode = "BEARER";
    }
    private static void handle(HttpExchange exchange) {
        try {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.set(Objects.toString(exchange.getRequestHeaders().getFirst("Authorization"), exchange.getRequestHeaders().getFirst("api-key")));
            rawUri.set(exchange.getRequestURI().toString());
            String selected = mode;
            if (selected.equals("idle")) {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream"); exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(": waiting\n\n".getBytes(StandardCharsets.UTF_8)); exchange.getResponseBody().flush();
                new CountDownLatch(1).await(5, TimeUnit.SECONDS); return;
            }
            String response;
            if (selected.equals("stream-error")) {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                response = "data: {\"error\":\"invalid secret-test-value\"}\n\n";
            } else if (selected.equals("sse")) {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
                response = "data: " + delta("feat(cache): <thi") + "\n\ndata:" + delta("nk>secret thought</think>添加缓存 ") + "\n\ndata: " + delta("🚀");
            } else if (selected.equals("ollama")) {
                exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson");
                response = "{\"response\":\"feat: 本地\",\"done\":false}\n{\"response\":\"模型\",\"done\":true}";
            } else if (selected.equals("error")) {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                response = "{\"error\":\"invalid secret-test-value\"}";
            } else {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                response = "{\"choices\":[{\"message\":{\"content\":\"fix: handle empty state\"}}]}";
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(selected.equals("error") ? 401 : 200, bytes.length);
            for (byte b : bytes) { exchange.getResponseBody().write(new byte[]{b}); exchange.getResponseBody().flush(); }
        } catch (Exception ignored) {} finally { exchange.close(); }
    }
    private static String delta(String text) { return "{\"choices\":[{\"delta\":{\"content\":" + new Gson().toJson(text) + "}}]}"; }
    private interface Checked { void run() throws Exception; }
    private static void test(String name, Checked task) throws Exception { task.run(); passed++; System.out.println("PASS " + name); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void expect(Class<? extends Throwable> type, Runnable action) {
        try { action.run(); } catch (Throwable e) { if (type.isInstance(e)) return; throw e; }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
}

