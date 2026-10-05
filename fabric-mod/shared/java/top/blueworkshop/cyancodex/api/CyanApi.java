package top.blueworkshop.cyancodex.api;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import top.blueworkshop.cyancodex.CyanLog;
import top.blueworkshop.cyancodex.config.CyanConfig;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * CyanCodex 平台接口客户端。
 *
 * - 指令库走 /api/commands（公开读接口）
 * - 生成器与 AI 走开放 API /api/v1/*（X-API-Key 鉴权，配额与网页端一致）
 */
public final class CyanApi {
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private CyanApi() {
    }

    /** 接口返回：ok=false 时 error 为中文提示，可直接显示给玩家 */
    public static final class Result<T> {
        public final boolean ok;
        public final T data;
        public final String error;

        Result(boolean ok, T data, String error) {
            this.ok = ok;
            this.data = data;
            this.error = error;
        }

        public static <T> Result<T> ok(T data) {
            return new Result<>(true, data, null);
        }

        public static <T> Result<T> fail(String error) {
            return new Result<>(false, null, error);
        }
    }

    /** 指令库条目 */
    public static final class CommandItem {
        public long id;
        public String title = "";
        public String description = "";
        public String command = "";
        public String category = "";
        public String author = "";
        public int likes;
        public int views;

        public String displayTitle() {
            return title.isBlank() ? command : title;
        }
    }

    /** 指令库分页 */
    public static final class CommandPage {
        public final List<CommandItem> list = new ArrayList<>();
        public int total;
    }

    /** 生成器返回 */
    public static final class GenResult {
        public final List<String> commands = new ArrayList<>();
        public String error;
    }

    private static String base() {
        return CyanConfig.get().apiBase;
    }

    private static HttpRequest.Builder openApi(String pathAndQuery) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + pathAndQuery))
                .timeout(Duration.ofSeconds(25))
                .header("Accept", "application/json")
                .header("User-Agent", "CyanCodex-Fabric/1.0");
        String key = CyanConfig.get().apiKey;
        if (key != null && !key.isBlank()) {
            b.header("X-API-Key", key.trim());
        }
        return b;
    }

    private static Result<JsonObject> send(HttpRequest req) {
        try {
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String body = resp.body() == null ? "" : resp.body();
            JsonObject obj;
            try {
                JsonElement el = JsonParser.parseString(body);
                obj = el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
            } catch (Exception e) {
                return Result.fail("服务器返回异常（HTTP " + resp.statusCode() + "）");
            }
            boolean ok = obj.has("ok") && obj.get("ok").getAsBoolean();
            if (!ok) {
                String err = obj.has("error") ? obj.get("error").getAsString() : ("请求失败（HTTP " + resp.statusCode() + "）");
                return Result.fail(err);
            }
            return Result.ok(obj);
        } catch (Exception e) {
            CyanLog.LOGGER.warn("[CyanCodex] 请求失败 {}：{}", req.uri(), e.toString());
            return Result.fail("无法连接 CyanCodex 服务器，请检查网络或接口地址");
        }
    }

    // ==================== 指令库 ====================

    public static Result<CommandPage> commands(String query, int page, int size) {
        StringBuilder q = new StringBuilder("/api/commands?page=").append(page).append("&size=").append(size);
        if (query != null && !query.isBlank()) {
            q.append("&q=").append(URLEncoder.encode(query.trim(), StandardCharsets.UTF_8));
        }
        Result<JsonObject> r = send(openApi(q.toString()).GET().build());
        if (!r.ok) return Result.fail(r.error);

        CommandPage out = new CommandPage();
        JsonObject data = r.data.getAsJsonObject("data");
        if (data != null) {
            if (data.has("total")) out.total = data.get("total").getAsInt();
            JsonArray list = data.has("list") ? data.getAsJsonArray("list") : new JsonArray();
            for (JsonElement el : list) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                CommandItem c = new CommandItem();
                c.id = optLong(o, "id");
                c.title = optStr(o, "title");
                c.description = optStr(o, "description");
                c.command = optStr(o, "command");
                c.category = optStr(o, "category");
                c.author = optStr(o, "author");
                c.likes = (int) optLong(o, "like_count");
                c.views = (int) optLong(o, "view_count");
                out.list.add(c);
            }
        }
        return Result.ok(out);
    }

    // ==================== 生成器（Java 版语法） ====================

    public static Result<GenResult> generate(String type, JsonObject args, String edition) {
        JsonObject body = args == null ? new JsonObject() : args.deepCopy();
        body.addProperty("edition", edition == null ? "java" : edition);
        HttpRequest req = openApi("/api/v1/gen/" + type)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                .build();
        Result<JsonObject> r = send(req);
        GenResult out = new GenResult();
        if (!r.ok) {
            out.error = r.error;
            return Result.fail(r.error);
        }
        JsonObject data = r.data.getAsJsonObject("data");
        if (data != null && data.has("commands")) {
            for (JsonElement el : data.getAsJsonArray("commands")) {
                out.commands.add(el.getAsString());
            }
        }
        return Result.ok(out);
    }

    // ==================== AI 助手 ====================

    /** messages: [{role, content}] */
    public static Result<String> aiChat(JsonArray messages) {
        JsonObject body = new JsonObject();
        body.add("messages", messages);
        body.addProperty("edition", "java");
        HttpRequest req = openApi("/api/v1/ai/chat")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                .build();
        Result<JsonObject> r = send(req);
        if (!r.ok) return Result.fail(r.error);
        JsonObject data = r.data.getAsJsonObject("data");
        String content = data != null && data.has("content") ? data.get("content").getAsString() : "";
        if (content.isBlank()) return Result.fail("AI 没有返回内容，请稍后再试");
        return Result.ok(content);
    }

    // ==================== 密钥信息 ====================

    /** 返回 "剩余 1993 / 2000" 之类的可读文本 */
    public static Result<String> keyQuota() {
        Result<JsonObject> r = send(openApi("/api/v1/me").GET().build());
        if (!r.ok) return Result.fail(r.error);
        JsonObject data = r.data.getAsJsonObject("data");
        if (data == null || !data.has("quota")) return Result.ok("已连接");
        JsonObject quota = data.getAsJsonObject("quota");
        long used = quota.has("used") ? quota.get("used").getAsLong() : 0;
        long limit = quota.has("limit") ? quota.get("limit").getAsLong() : 0;
        return Result.ok("今日剩余 " + Math.max(0, limit - used) + " / " + limit);
    }

    // ==================== 工具 ====================

    private static String optStr(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    private static long optLong(JsonObject o, String k) {
        try {
            return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsLong() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    public static JsonObject obj(Object... kv) {
        JsonObject o = new JsonObject();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            String k = String.valueOf(kv[i]);
            Object v = kv[i + 1];
            if (v == null) continue;
            if (v instanceof Number n) o.addProperty(k, n);
            else if (v instanceof Boolean b) o.addProperty(k, b);
            else o.addProperty(k, String.valueOf(v));
        }
        return o;
    }
}
