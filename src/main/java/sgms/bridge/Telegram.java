package sgms.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Тонкая обёртка над Telegram Bot API (JSON поверх HTTP). */
public class Telegram {
    private final SGMSBridge plugin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Gson gson = new Gson();
    private final String base;
    private final long chatId;
    private final Integer topicId;

    public Telegram(SGMSBridge plugin, String apiUrl, String token, long chatId, Integer topicId) {
        this.plugin = plugin;
        String url = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        this.base = url + "/bot" + token + "/";
        this.chatId = chatId;
        this.topicId = topicId;
    }

    /** Вызов метода Bot API. Бросает исключение при сетевой ошибке или ok=false. */
    public JsonObject call(String method, JsonObject body, int timeoutSec) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + method))
                .timeout(Duration.ofSeconds(timeoutSec))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonObject o;
        try {
            o = JsonParser.parseString(r.body()).getAsJsonObject();
        } catch (Exception e) {
            throw new IllegalStateException("Telegram HTTP " + r.statusCode());
        }
        if (!o.has("ok") || !o.get("ok").getAsBoolean()) {
            String d = o.has("description") ? o.get("description").getAsString() : ("HTTP " + r.statusCode());
            throw new IllegalStateException("Telegram: " + d);
        }
        return o;
    }

    private JsonObject target() {
        JsonObject b = new JsonObject();
        b.addProperty("chat_id", chatId);
        if (topicId != null) b.addProperty("message_thread_id", topicId);
        return b;
    }

    /** Отправка текста (HTML). Ошибки не пробрасываются, пишутся в лог без шума. */
    public void sendMessage(String html) {
        try {
            sendMessageOrThrow(html, 15);
        } catch (Exception e) {
            plugin.warnThrottled("send", "Не удалось отправить сообщение в Telegram: " + e.getMessage());
        }
    }

    public void sendMessageOrThrow(String html, int timeoutSec) throws Exception {
        JsonObject b = target();
        b.addProperty("text", html);
        b.addProperty("parse_mode", "HTML");
        b.addProperty("disable_web_page_preview", true);
        call("sendMessage", b, timeoutSec);
    }

    /** Отправка картинки по ссылке с подписью. Возвращает false, если не получилось. */
    public boolean sendPhoto(String url, String captionHtml) {
        try {
            JsonObject b = target();
            b.addProperty("photo", url);
            if (captionHtml != null && !captionHtml.isEmpty()) {
                b.addProperty("caption", captionHtml);
                b.addProperty("parse_mode", "HTML");
            }
            call("sendPhoto", b, 30);
            return true;
        } catch (Exception e) {
            plugin.warnThrottled("photo", "Не удалось отправить картинку в Telegram: " + e.getMessage());
            return false;
        }
    }

    public String getMyUsername() {
        try {
            JsonObject r = call("getMe", new JsonObject(), 15).getAsJsonObject("result");
            return r.has("username") ? r.get("username").getAsString() : "";
        } catch (Exception e) {
            plugin.warnThrottled("getMe", "Не удалось связаться с Telegram: " + e.getMessage());
            return "";
        }
    }

    public void setCommands(String[][] cmds) {
        try {
            JsonArray arr = new JsonArray();
            for (String[] c : cmds) {
                JsonObject o = new JsonObject();
                o.addProperty("command", c[0]);
                o.addProperty("description", c[1]);
                arr.add(o);
            }
            JsonObject b = new JsonObject();
            b.add("commands", arr);
            call("setMyCommands", b, 15);
        } catch (Exception e) {
            plugin.warnThrottled("setcmds", "Не удалось обновить список команд бота: " + e.getMessage());
        }
    }

    public JsonArray getUpdates(long offset) throws Exception {
        JsonObject b = new JsonObject();
        b.addProperty("offset", offset);
        b.addProperty("timeout", 30);
        JsonArray allowed = new JsonArray();
        allowed.add("message");
        b.add("allowed_updates", allowed);
        return call("getUpdates", b, 45).getAsJsonArray("result");
    }
}
