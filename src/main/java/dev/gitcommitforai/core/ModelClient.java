package dev.gitcommitforai.core;

import com.google.gson.*;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow.*;
import java.util.function.Consumer;

public final class ModelClient {
    private static final Gson GSON = new Gson();
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    public static URI endpoint(ModelProfile p) {
        String value = p.endpoint == null ? "" : p.endpoint.strip();
        URI uri;
        try { uri = URI.create(value); } catch (Exception e) { throw new IllegalArgumentException("接口地址格式不正确。"); }
        if (!Set.of("http", "https").contains(Objects.toString(uri.getScheme(), "")) || uri.getHost() == null || uri.getUserInfo() != null)
            throw new IllegalArgumentException("请输入完整的 http:// 或 https:// 接口地址，不要把 Key 放进 URL。");
        if (uri.getHost().endsWith(".example")) throw new IllegalArgumentException("请先把示例接口地址替换为你的模型服务地址。");
        if (uri.getFragment() != null) throw new IllegalArgumentException("接口地址不能包含 #fragment。");
        String path = Objects.toString(uri.getPath(), "").replaceAll("/+$", "");
        if (p.protocol.equals("OLLAMA")) {
            if (path.isEmpty()) path = "/api/generate";
            else if (path.equals("/api")) path += "/generate";
        } else {
            if (path.endsWith("/responses")) throw new IllegalArgumentException("当前支持 Chat Completions；请使用 /v1/chat/completions 接口。");
            if (path.isEmpty()) path = "/v1/chat/completions";
            else if (!path.endsWith("/chat/completions")) path += "/chat/completions";
        }
        try { return new URI(uri.getScheme(), uri.getAuthority(), path, uri.getQuery(), null); }
        catch (URISyntaxException e) { throw new IllegalArgumentException("接口地址格式不正确。"); }
    }
    public static void validate(ModelProfile p) {
        endpoint(p);
        if (p.model == null || p.model.isBlank()) throw new IllegalArgumentException("请填写模型 ID。");
        if (p.timeoutSeconds < 10 || p.timeoutSeconds > 600) throw new IllegalArgumentException("超时时间应为 10–600 秒。");
        extraBody(p);
    }
    public static JsonObject extraBody(ModelProfile p) {
        try {
            JsonElement element = JsonParser.parseString(p.extraBodyJson == null || p.extraBodyJson.isBlank() ? "{}" : p.extraBodyJson);
            if (!element.isJsonObject()) throw new IllegalArgumentException("附加请求参数必须是 JSON 对象。");
            JsonObject extra = element.getAsJsonObject();
            for (String reserved : List.of("model", "messages", "prompt", "stream"))
                if (extra.has(reserved)) throw new IllegalArgumentException("附加参数不能覆盖 " + reserved + "。");
            return extra;
        } catch (JsonParseException | IllegalStateException e) { throw new IllegalArgumentException("附加请求参数必须是有效的 JSON 对象。"); }
    }
    public String generate(ModelProfile p, String apiKey, String prompt, boolean useIdeProxy, Consumer<String> onText, Cancellation cancel) throws Exception {
        validate(p);
        JsonObject body = extraBody(p).deepCopy();
        body.addProperty("model", p.model.strip()); body.addProperty("stream", p.stream);
        if (p.protocol.equals("OLLAMA")) body.addProperty("prompt", prompt);
        else {
            JsonObject message = new JsonObject(); message.addProperty("role", "user"); message.addProperty("content", prompt);
            JsonArray messages = new JsonArray(); messages.add(message); body.add("messages", messages);
        }
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(Math.min(p.timeoutSeconds, 30)));
        if (!useIdeProxy) builder.proxy(new ProxySelector() {
            public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
            public void connectFailed(URI uri, SocketAddress sa, java.io.IOException e) {}
        });
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint(p)).timeout(Duration.ofSeconds(p.timeoutSeconds))
            .header("Content-Type", "application/json; charset=utf-8").header("Accept", "text/event-stream, application/json")
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8));
        if (apiKey != null && !apiKey.isBlank() && !p.authMode.equals("NONE"))
            request.header(p.authMode.equals("API_KEY") ? "api-key" : "Authorization", p.authMode.equals("API_KEY") ? apiKey.strip() : "Bearer " + apiKey.strip());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(p.timeoutSeconds);
        try (HttpClient client = builder.build()) {
            CompletableFuture<HttpResponse<Publisher<List<ByteBuffer>>>> future = client.sendAsync(request.build(), HttpResponse.BodyHandlers.ofPublisher());
            BodyQueue queue = new BodyQueue();
            try {
                HttpResponse<Publisher<List<ByteBuffer>>> response;
                while (true) {
                    check(cancel, deadline);
                    try { response = future.get(100, TimeUnit.MILLISECONDS); break; } catch (TimeoutException ignored) {}
                }
                response.body().subscribe(queue);
                String contentType = response.headers().firstValue("content-type").orElse("");
                boolean ok = response.statusCode() >= 200 && response.statusCode() < 300;
                boolean eventStream = contentType.contains("text/event-stream");
                boolean ndjson = p.protocol.equals("OLLAMA") && p.stream && !contentType.contains("text/event-stream");
                StringBuilder collected = new StringBuilder();
                ReasoningFilter filter = new ReasoningFilter(text -> { collected.append(text); onText.accept(text); });
                boolean[] done = {false};
                Consumer<String> event = data -> {
                    if (data.strip().equals("[DONE]")) { done[0] = true; return; }
                    if (!done[0]) {
                        JsonObject json = parse(data);
                        String text = extract(json, p.protocol.equals("OLLAMA"), true, apiKey);
                        if (!text.isEmpty()) filter.accept(text);
                        if (json.has("done") && json.get("done").getAsBoolean()) done[0] = true;
                    }
                };
                StreamParser framing = new StreamParser(eventStream, event);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                java.nio.charset.CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
                ByteBuffer undecoded = ByteBuffer.allocate(64 * 1024);
                int total = 0;
                while (!done[0]) {
                    check(cancel, deadline);
                    Object next = queue.events.poll(100, TimeUnit.MILLISECONDS);
                    if (next == null) continue;
                    if (next instanceof Throwable failure) throw new java.io.IOException("读取模型响应失败。", failure);
                    if (next == BodyQueue.END) break;
                    byte[] chunk = (byte[]) next; total += chunk.length;
                    if (total > MAX_RESPONSE_BYTES) throw new IllegalArgumentException("模型响应超过安全长度，请调整输出参数或换用提交信息专用模板。");
                    if (!ok || (!eventStream && !ndjson)) bytes.write(chunk);
                    else {
                        int offset = 0;
                        while (offset < chunk.length) {
                            int count = Math.min(undecoded.remaining(), chunk.length - offset);
                            undecoded.put(chunk, offset, count); offset += count;
                            undecoded.flip();
                            java.nio.CharBuffer chars = java.nio.CharBuffer.allocate(undecoded.remaining() + 4);
                            var decodeResult = decoder.decode(undecoded, chars, false);
                            if (decodeResult.isError()) decodeResult.throwException();
                            chars.flip(); framing.accept(chars.toString()); undecoded.compact();
                        }
                    }
                    queue.request();
                }
                if (!ok) {
                    String error = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
                    error = redact(error, apiKey);
                    throw new IllegalArgumentException("模型服务返回 HTTP " + response.statusCode() + "：\n" + PromptEngine.truncate(error, 1500, "…"));
                }
                if (eventStream || ndjson) {
                    if (undecoded.position() > 0) {
                        undecoded.flip();
                        java.nio.CharBuffer tail = java.nio.CharBuffer.allocate(16);
                        var result = decoder.decode(undecoded, tail, true); if (result.isError()) result.throwException();
                        tail.flip(); framing.accept(tail.toString());
                    }
                    framing.finish();
                } else filter.accept(extract(parse(new String(bytes.toByteArray(), StandardCharsets.UTF_8)), p.protocol.equals("OLLAMA"), false, apiKey));
                filter.finish();
                return PromptEngine.cleanMessage(collected.toString());
            } finally { queue.cancel(); future.cancel(true); }
        } catch (ExecutionException e) {
            throw new java.io.IOException("连接模型服务失败：" + redact(Objects.toString(e.getCause().getMessage(), "请检查地址、代理或网络"), apiKey), e.getCause());
        }
    }
    private static void check(Cancellation cancel, long deadline) throws TimeoutException {
        cancel.check();
        if (System.nanoTime() > deadline) throw new TimeoutException("模型请求超时，请检查服务或增加超时时间。");
    }
    private static JsonObject parse(String text) {
        try { return JsonParser.parseString(text).getAsJsonObject(); }
        catch (RuntimeException e) { throw new IllegalArgumentException("服务未返回有效 JSON，请检查接口是否为 Chat Completions 或 Ollama Generate。"); }
    }
    private static String redact(String text, String key) { return key == null || key.isBlank() ? text : text.replace(key.strip(), "[REDACTED]"); }
    private static String extract(JsonObject json, boolean ollama, boolean streaming, String apiKey) {
        if (json.has("error")) {
            JsonElement error = json.get("error");
            throw new IllegalArgumentException("模型服务错误：" + PromptEngine.truncate(redact(error.toString(), apiKey), 1000, "…"));
        }
        if (ollama) return text(json.get("response"));
        JsonArray choices = json.has("choices") && json.get("choices").isJsonArray() ? json.getAsJsonArray("choices") : null;
        if (choices == null || choices.isEmpty()) return "";
        JsonObject choice = choices.get(0).getAsJsonObject();
        JsonElement payload = choice.get(streaming ? "delta" : "message");
        if (payload == null && streaming) payload = choice.get("message");
        return payload != null && payload.isJsonObject() ? text(payload.getAsJsonObject().get("content")) : "";
    }
    private static String text(JsonElement e) {
        if (e == null || e.isJsonNull()) return "";
        if (e.isJsonPrimitive()) return e.getAsString();
        if (e.isJsonArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonElement part : e.getAsJsonArray()) if (part.isJsonObject()) text.append(text(part.getAsJsonObject().get("text")));
            return text.toString();
        }
        return "";
    }
    private static final class BodyQueue implements Subscriber<List<ByteBuffer>> {
        static final Object END = new Object();
        final BlockingQueue<Object> events = new LinkedBlockingQueue<>(8);
        volatile Subscription subscription;
        public void onSubscribe(Subscription s) { subscription = s; s.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            int length = buffers.stream().mapToInt(ByteBuffer::remaining).sum();
            if (length > MAX_RESPONSE_BYTES) { events.offer(new IllegalArgumentException("响应片段过大。")); cancel(); return; }
            byte[] data = new byte[length]; int offset = 0;
            for (ByteBuffer buffer : buffers) { int n = buffer.remaining(); buffer.get(data, offset, n); offset += n; }
            if (!events.offer(data)) { cancel(); events.offer(new IllegalArgumentException("响应缓冲区已满。")); }
        }
        public void onError(Throwable failure) { events.offer(failure); }
        public void onComplete() { events.offer(END); }
        void request() { Subscription s = subscription; if (s != null) s.request(1); }
        void cancel() { Subscription s = subscription; if (s != null) s.cancel(); }
    }
}

