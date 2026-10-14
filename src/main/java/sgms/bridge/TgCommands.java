package sgms.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Команды Telegram: /list, /tps, /topX, /peakX, /stats, /graph. */
public class TgCommands {
    /** Список для меню команд бота. */
    public static final String[][] MENU = {
            {"list", "Кто сейчас на сервере"},
            {"tps", "TPS и нагрузка сервера"},
            {"topday", "Топ по времени игры за день"},
            {"topweek", "Топ по времени игры за неделю"},
            {"topmonth", "Топ по времени игры за месяц"},
            {"topyear", "Топ по времени игры за год"},
            {"peakday", "Пик онлайна за день"},
            {"peakweek", "Пик онлайна за неделю"},
            {"peakmonth", "Пик онлайна за месяц"},
            {"peakyear", "Пик онлайна за год"},
            {"stats", "Статистика игрока: /stats ник"},
            {"graph", "График онлайна: /graph day|week|month|year"},
    };

    private record Info(String name, String uuid, boolean online, long first, long last, long deaths, long kills) {}

    private final SGMSBridge plugin;
    private final Telegram tg;
    private final Stats stats;

    public TgCommands(SGMSBridge plugin, Telegram tg, Stats stats) {
        this.plugin = plugin;
        this.tg = tg;
        this.stats = stats;
    }

    public void handle(String cmd, String args) {
        try {
            if (cmd.equals("start") || cmd.equals("help")) {
                help();
            } else if (cmd.equals("list")) {
                list();
            } else if (cmd.equals("tps")) {
                tps();
            } else if (cmd.startsWith("top") && Period.parse(cmd.substring(3)) != null) {
                top(Period.parse(cmd.substring(3)));
            } else if (cmd.startsWith("peak") && Period.parse(cmd.substring(4)) != null) {
                peak(Period.parse(cmd.substring(4)));
            } else if (cmd.equals("stats")) {
                playerStats(args);
            } else if (cmd.equals("graph")) {
                graph(args);
            }
        } catch (Exception e) {
            plugin.warnThrottled("cmd-" + cmd, "Ошибка команды /" + cmd + ": " + e);
            tg.sendMessage("⚠️ Не удалось выполнить команду: " + Fmt.esc(String.valueOf(e.getMessage())));
        }
    }

    private void help() {
        StringBuilder sb = new StringBuilder("🤖 <b>Команды</b>\n");
        for (String[] c : MENU) sb.append("/").append(c[0]).append(" — ").append(Fmt.esc(c[1])).append("\n");
        tg.sendMessage(sb.toString());
    }

    // ---------------------------------------------------------------- /list, /tps

    private void list() throws Exception {
        List<String> names = plugin.sync(() -> {
            List<String> l = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!plugin.isVanished(p)) l.add(p.getName());
            }
            Collections.sort(l, String.CASE_INSENSITIVE_ORDER);
            return l;
        });
        if (names.isEmpty()) {
            tg.sendMessage(plugin.msg("player-list-zero"));
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(Fmt.esc(names.get(i)));
        }
        tg.sendMessage(Fmt.fill(plugin.msg("player-list"), "count", String.valueOf(names.size()), "usernames", sb.toString()));
    }

    private void tps() {
        double[] t = Bukkit.getTPS();
        double mspt = Bukkit.getAverageTickTime();
        String t1 = String.format(Locale.ROOT, "%.1f", Math.min(20.0, t[0]));
        String t5 = String.format(Locale.ROOT, "%.1f", Math.min(20.0, t[1]));
        String t15 = String.format(Locale.ROOT, "%.1f", Math.min(20.0, t[2]));
        String ms = String.format(Locale.ROOT, "%.1f", mspt);
        tg.sendMessage(Fmt.fill(plugin.msg("tps"),
                "tps1m", t1, "tps5m", t5, "tps15m", t15, "mspt", ms,
                "tps10s", t1, "mspt10sAvg", ms));
    }

    // ---------------------------------------------------------------- топ и пик

    private Set<String> onlineUuids() throws Exception {
        return plugin.sync(() -> {
            Set<String> s = new HashSet<>();
            for (Player p : Bukkit.getOnlinePlayers()) s.add(Stats.norm(p.getUniqueId().toString()));
            return s;
        });
    }

    private void top(Period p) throws Exception {
        long to = System.currentTimeMillis();
        long from = p.startMillis(plugin.calendar(), plugin.zone());
        List<Stats.Session> ss = stats.load(from, onlineUuids());
        LinkedHashMap<String, Long> t = stats.totals(ss, from, to);
        if (t.isEmpty()) {
            tg.sendMessage("🏆 <b>Топ за " + p.ru + "</b>\nНет данных за этот период.");
            return;
        }
        int limit = plugin.getConfig().getInt("top-size", 10);
        StringBuilder sb = new StringBuilder("🏆 <b>Топ по времени игры за " + p.ru + "</b>\n");
        int i = 1;
        for (Map.Entry<String, Long> e : t.entrySet()) {
            if (i > limit) break;
            sb.append(i).append(". ").append(Fmt.esc(e.getKey())).append(" — ").append(Fmt.dur(e.getValue())).append("\n");
            i++;
        }
        tg.sendMessage(sb.toString());
    }

    private void peak(Period p) throws Exception {
        long to = System.currentTimeMillis();
        long from = p.startMillis(plugin.calendar(), plugin.zone());
        List<Stats.Session> ss = stats.load(from, onlineUuids());
        Stats.Peak pk = stats.peak(ss, from, to);
        if (pk == null) {
            tg.sendMessage("📈 <b>Пик онлайна за " + p.ru + "</b>\nНет данных за этот период.");
            return;
        }
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < pk.names().size(); i++) {
            if (i > 0) names.append(", ");
            names.append(Fmt.esc(pk.names().get(i)));
        }
        tg.sendMessage("📈 <b>Пик онлайна за " + p.ru + ":</b> " + pk.count() + "\n"
                + "🕒 " + Fmt.date(pk.time(), plugin.zone()) + "\n"
                + "👥 " + names);
    }

    // ---------------------------------------------------------------- /stats

    private long readStat(File dir, String uuid, String key) {
        File f = new File(dir, uuid + ".json");
        if (!f.isFile()) return 0;
        try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
            JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
            if (!o.has("stats")) return 0;
            JsonObject st = o.getAsJsonObject("stats");
            if (!st.has("minecraft:custom")) return 0;
            JsonObject custom = st.getAsJsonObject("minecraft:custom");
            return custom.has(key) ? custom.get(key).getAsLong() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private void playerStats(String args) throws Exception {
        final String nick = args == null ? "" : args.trim();
        if (nick.isEmpty()) {
            tg.sendMessage("Использование: <code>/stats ник</code>");
            return;
        }
        Info info = plugin.sync(() -> {
            OfflinePlayer found = null;
            for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
                if (op.getName() != null && op.getName().equalsIgnoreCase(nick)) {
                    found = op;
                    break;
                }
            }
            if (found == null) return null;
            String uuid = found.getUniqueId().toString();
            long deaths;
            long kills;
            Player online = found.getPlayer();
            if (online != null) {
                deaths = online.getStatistic(Statistic.DEATHS);
                kills = online.getStatistic(Statistic.PLAYER_KILLS);
            } else {
                File dir = plugin.statsDir();
                deaths = readStat(dir, uuid, "minecraft:deaths");
                kills = readStat(dir, uuid, "minecraft:player_kills");
            }
            return new Info(found.getName(), uuid, online != null, found.getFirstPlayed(), found.getLastPlayed(), deaths, kills);
        });
        if (info == null) {
            tg.sendMessage("Игрок <b>" + Fmt.esc(nick) + "</b> не найден на сервере.");
            return;
        }
        long now = System.currentTimeMillis();
        ZoneId zone = plugin.zone();
        List<Stats.Session> ss = stats.load(0, onlineUuids());
        StringBuilder sb = new StringBuilder();
        sb.append("📊 <b>").append(Fmt.esc(info.name())).append("</b>\n");
        sb.append("🆔 <code>").append(info.uuid()).append("</code>\n");
        sb.append("📅 Первый вход: ").append(Fmt.date(info.first(), zone)).append("\n");
        if (info.online()) sb.append("🟢 Сейчас на сервере\n");
        else sb.append("🕒 Последний вход: ").append(Fmt.date(info.last(), zone)).append("\n");
        sb.append("💀 Смертей: ").append(info.deaths()).append("\n");
        sb.append("⚔️ Убийств игроков: ").append(info.kills()).append("\n\n");
        sb.append("⏱ <b>Наиграно</b>\n");
        for (Period p : Period.values()) {
            long from = p.startMillis(plugin.calendar(), zone);
            sb.append("• ").append(p.label).append(": ")
                    .append(Fmt.dur(stats.playerTotal(ss, info.uuid(), info.name(), from, now))).append("\n");
        }
        sb.append("• Всего: ").append(Fmt.dur(stats.playerTotal(ss, info.uuid(), info.name(), 0, now)));
        String photo = "https://minotar.net/armor/bust/" + URLEncoder.encode(info.name(), StandardCharsets.UTF_8) + "/256.png";
        if (!tg.sendPhoto(photo, sb.toString())) tg.sendMessage(sb.toString());
    }

    // ---------------------------------------------------------------- /graph

    @SuppressWarnings("unchecked")
    private void graph(String args) throws Exception {
        Period p = Period.parse(args);
        if (p == null) p = Period.MONTH;
        Object[] b = stats.chartBuckets(p, plugin.zone());
        long[] edges = (long[]) b[0];
        List<String> labels = (List<String>) b[1];
        List<Stats.Session> ss = stats.load(edges[0], onlineUuids());
        double[] hours = stats.bucketHours(ss, edges);

        JsonArray labs = new JsonArray();
        for (String l : labels) labs.add(l);
        JsonArray vals = new JsonArray();
        double sum = 0;
        for (double h : hours) {
            vals.add(Math.round(h * 10.0) / 10.0);
            sum += h;
        }
        JsonObject ds = new JsonObject();
        ds.addProperty("label", "Часы онлайна (все игроки вместе)");
        ds.add("data", vals);
        JsonArray dss = new JsonArray();
        dss.add(ds);
        JsonObject data = new JsonObject();
        data.add("labels", labs);
        data.add("datasets", dss);
        JsonObject title = new JsonObject();
        title.addProperty("display", true);
        title.addProperty("text", "Онлайн за " + p.ru);
        JsonObject options = new JsonObject();
        options.add("title", title);
        JsonObject cfg = new JsonObject();
        cfg.addProperty("type", "bar");
        cfg.add("data", data);
        cfg.add("options", options);

        String url = "https://quickchart.io/chart?bkg=white&w=900&h=450&c="
                + URLEncoder.encode(cfg.toString(), StandardCharsets.UTF_8);
        String caption = "📈 <b>Онлайн за " + p.ru + "</b>\nВсего: " + Fmt.dur((long) (sum * 3600000.0));
        if (!tg.sendPhoto(url, caption)) {
            tg.sendMessage(caption + "\n(не удалось построить картинку графика)");
        }
    }
}
