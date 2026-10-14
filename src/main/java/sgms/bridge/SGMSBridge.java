package sgms.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * SGMSBridge: мост Minecraft <-> Telegram (чат, события) + команды со статистикой онлайна.
 * Заменяет tgbridge.
 */
public class SGMSBridge extends JavaPlugin implements Listener {
    private Telegram tg;
    private Stats stats;
    private TgCommands commands;
    private long chatId;
    private Integer topicId;
    private String botUsername = "";
    private volatile boolean running;
    private Thread poller;
    private final ExecutorService out = Executors.newSingleThreadExecutor(r -> daemon(r, "SGMSBridge-out"));
    private final ExecutorService io = Executors.newFixedThreadPool(3, r -> daemon(r, "SGMSBridge-io"));
    private final Map<String, Long> warned = new ConcurrentHashMap<>();

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    // ---------------------------------------------------------------- запуск и остановка

    @Override
    public void onEnable() {
        saveDefaultConfig();
        String token = getConfig().getString("bot-token", "");
        if (token == null || token.isBlank() || token.startsWith("PUT_")) {
            getLogger().warning("В config.yml не указан bot-token. Мост с Telegram выключен.");
            return;
        }
        chatId = getConfig().getLong("chat-id");
        topicId = getConfig().isSet("topic-id") ? getConfig().getInt("topic-id") : null;
        tg = new Telegram(this, getConfig().getString("bot-api-url", "https://api.telegram.org"), token, chatId, topicId);
        stats = new Stats(this);
        commands = new TgCommands(this, tg, stats);
        Bukkit.getPluginManager().registerEvents(new McListener(this), this);
        Bukkit.getPluginManager().registerEvents(this, this);

        running = true;
        poller = daemon(this::pollLoop, "SGMSBridge-poll");
        poller.start();
        getLogger().info("SGMSBridge запущен, чат " + chatId + (topicId != null ? ", тема " + topicId : ""));
    }

    @Override
    public void onDisable() {
        running = false;
        if (tg != null && getConfig().getBoolean("events.server-stop", true)) {
            try {
                tg.sendMessageOrThrow(msg("server-stopped"), 5);
            } catch (Exception ignored) {
                // сервер всё равно останавливается
            }
        }
        if (poller != null) poller.interrupt();
        out.shutdown();
        io.shutdown();
    }

    @EventHandler
    public void onServerLoad(ServerLoadEvent e) {
        if (e.getType() != ServerLoadEvent.LoadType.STARTUP || tg == null) return;
        if (getConfig().getBoolean("events.server-start", true)) send(msg("server-started"));
    }

    // ---------------------------------------------------------------- Telegram -> Minecraft

    private void pollLoop() {
        botUsername = tg.getMyUsername();
        tg.setCommands(TgCommands.MENU);
        long offset = 0;
        long delay = 1000;
        while (running) {
            try {
                JsonArray arr = tg.getUpdates(offset);
                delay = 1000;
                for (JsonElement el : arr) {
                    JsonObject u = el.getAsJsonObject();
                    offset = Math.max(offset, u.get("update_id").getAsLong() + 1);
                    try {
                        handleUpdate(u);
                    } catch (Exception e) {
                        warnThrottled("update", "Ошибка обработки сообщения из Telegram: " + e);
                    }
                }
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                String m = String.valueOf(e.getMessage());
                if (m.contains("Conflict")) {
                    warnThrottled("conflict", "Telegram: этот токен уже использует другой бот (tgbridge ещё включён?).");
                } else {
                    warnThrottled("poll", "Нет связи с Telegram: " + m);
                }
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    return;
                }
                delay = Math.min(delay * 2, 60000);
            }
        }
    }

    private void handleUpdate(JsonObject u) {
        if (!u.has("message")) return;
        JsonObject m = u.getAsJsonObject("message");
        JsonObject chat = m.getAsJsonObject("chat");
        if (chat == null || chat.get("id").getAsLong() != chatId) return;
        if (topicId != null) {
            if (!m.has("message_thread_id") || m.get("message_thread_id").getAsInt() != topicId) return;
        }
        JsonObject from = m.has("from") ? m.getAsJsonObject("from") : null;
        if (from != null && from.has("is_bot") && from.get("is_bot").getAsBoolean()) return;

        String text = null;
        if (m.has("text")) text = m.get("text").getAsString();
        else if (m.has("caption")) text = m.get("caption").getAsString();
        else text = mediaLabel(m);
        if (text == null || text.isEmpty()) return;

        // команды бота
        if (text.startsWith("/")) {
            String[] parts = text.split("\\s+", 2);
            String cmd = parts[0].substring(1).toLowerCase();
            String args = parts.length > 1 ? parts[1] : "";
            int at = cmd.indexOf('@');
            if (at >= 0) {
                String target = cmd.substring(at + 1);
                cmd = cmd.substring(0, at);
                if (!botUsername.isEmpty() && !target.equalsIgnoreCase(botUsername)) return;
            }
            final String fcmd = cmd;
            final String fargs = args;
            io.submit(() -> commands.handle(fcmd, fargs));
            return;
        }

        // обычное сообщение: показываем игрокам в Minecraft
        String sender = senderName(from);
        String fmt = getConfig().getString("minecraft-format", "<aqua>\\<<sender>></aqua> <text>");
        final Component c = MiniMessage.miniMessage().deserialize(fmt,
                Placeholder.unparsed("sender", sender),
                Placeholder.unparsed("text", text));
        Bukkit.getScheduler().runTask(this, () -> Bukkit.broadcast(c));
    }

    private static String senderName(JsonObject from) {
        if (from == null) return "Telegram";
        String first = from.has("first_name") ? from.get("first_name").getAsString() : "";
        String last = from.has("last_name") ? from.get("last_name").getAsString() : "";
        String name = (first + " " + last).trim();
        if (name.isEmpty() && from.has("username")) name = from.get("username").getAsString();
        return name.isEmpty() ? "Telegram" : name;
    }

    private static String mediaLabel(JsonObject m) {
        if (m.has("photo")) return "[Фото]";
        if (m.has("sticker")) return "[Стикер]";
        if (m.has("animation")) return "[GIF]";
        if (m.has("video")) return "[Видео]";
        if (m.has("video_note")) return "[Видеосообщение]";
        if (m.has("voice")) return "[Голосовое]";
        if (m.has("audio")) return "[Аудио]";
        if (m.has("document")) return "[Файл]";
        if (m.has("poll")) return "[Опрос]";
        return null;
    }

    // ---------------------------------------------------------------- помощники

    /** Отправить сообщение в Telegram (по очереди, чтобы не путался порядок). */
    public void send(String html) {
        if (tg == null) return;
        out.submit(() -> tg.sendMessage(html));
    }

    public String msg(String key) {
        return getConfig().getString("messages." + key, "");
    }

    /** Выполнить действие в основном потоке сервера и дождаться результата. */
    public <T> T sync(Callable<T> c) throws Exception {
        return Bukkit.getScheduler().callSyncMethod(this, c).get(10, TimeUnit.SECONDS);
    }

    public boolean isVanished(Player p) {
        for (MetadataValue v : p.getMetadata("vanished")) {
            if (v.asBoolean()) return true;
        }
        return false;
    }

    public ZoneId zone() {
        try {
            return ZoneId.of(getConfig().getString("timezone", "UTC"));
        } catch (Exception e) {
            return ZoneId.of("UTC");
        }
    }

    public boolean calendar() {
        return "calendar".equalsIgnoreCase(getConfig().getString("period-mode", "rolling"));
    }

    public File dbFile() {
        return new File(getConfig().getString("coreprotect-db", "plugins/CoreProtect/database.db"));
    }

    /** Папка со статистикой игроков (world/stats). Вызывать из основного потока. */
    public File statsDir() {
        for (World w : Bukkit.getWorlds()) {
            File f = new File(w.getWorldFolder(), "stats");
            if (f.isDirectory()) return f;
            File parent = w.getWorldFolder().getParentFile();
            if (parent != null) {
                f = new File(parent, "stats");
                if (f.isDirectory()) return f;
            }
        }
        return new File(Bukkit.getWorldContainer(), "world/stats");
    }

    /** Предупреждение в лог не чаще раза в 5 минут для одного ключа (без стек-трейсов). */
    public void warnThrottled(String key, String text) {
        long now = System.currentTimeMillis();
        Long last = warned.get(key);
        if (last != null && now - last < 300000L) return;
        warned.put(key, now);
        getLogger().warning(text);
    }
}
