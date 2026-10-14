package sgms.bridge;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Статистика онлайна. Источник данных — таблицы co_session и co_user базы CoreProtect:
 * action = 1 — вход игрока, action = 0 — выход, time — секунды Unix.
 */
public class Stats {

    /** Одна игровая сессия, миллисекунды Unix. */
    public record Session(String name, String uuid, long start, long end) {}

    /** Пик одновременного онлайна. */
    public record Peak(int count, long time, List<String> names) {}

    private final SGMSBridge plugin;

    public Stats(SGMSBridge plugin) {
        this.plugin = plugin;
    }

    public static String norm(String uuid) {
        return uuid == null ? "" : uuid.replace("-", "").toLowerCase();
    }

    /**
     * Загружает сессии, пересекающиеся с периодом, начиная с fromMs.
     * onlineUuids — игроки, которые сейчас на сервере (их текущая сессия считается до «сейчас»).
     */
    public List<Session> load(long fromMs, Set<String> onlineUuids) throws Exception {
        File db = plugin.dbFile();
        if (!db.isFile()) throw new IllegalStateException("не найдена база CoreProtect: " + db.getPath());
        Class.forName("org.sqlite.JDBC");
        String url = "jdbc:sqlite:file:" + db.getAbsolutePath().replace('\\', '/') + "?mode=ro";
        long nowMs = System.currentTimeMillis();
        List<Session> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(url)) {
            try (Statement st = c.createStatement()) {
                st.execute("PRAGMA busy_timeout = 8000");
            }
            String sql = "SELECT s.user, s.time, s.action, u.user, u.uuid "
                    + "FROM co_session s JOIN co_user u ON u.rowid = s.user "
                    + "WHERE s.time >= ? ORDER BY s.user, s.time";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                // берём запас в 7 дней назад, чтобы поймать вход, случившийся до начала периода
                ps.setLong(1, Math.max(0L, fromMs / 1000L - 7L * 86400L));
                try (ResultSet rs = ps.executeQuery()) {
                    int curUser = Integer.MIN_VALUE;
                    long login = -1;
                    String name = "";
                    String uuid = "";
                    while (rs.next()) {
                        int user = rs.getInt(1);
                        long t = rs.getLong(2) * 1000L;
                        int action = rs.getInt(3);
                        if (user != curUser) {
                            // предыдущий игрок: незакрытая сессия считается только если он сейчас онлайн
                            if (login >= 0 && onlineUuids.contains(norm(uuid))) {
                                out.add(new Session(name, uuid, login, nowMs));
                            }
                            curUser = user;
                            login = -1;
                            name = rs.getString(4) == null ? "" : rs.getString(4);
                            uuid = rs.getString(5) == null ? "" : rs.getString(5);
                        }
                        if (action == 1) {
                            login = t; // повторный вход без выхода (сбой сервера): прошлая сессия отбрасывается
                        } else if (login >= 0) {
                            if (t > login) out.add(new Session(name, uuid, login, t));
                            login = -1;
                        }
                    }
                    if (login >= 0 && onlineUuids.contains(norm(uuid))) {
                        out.add(new Session(name, uuid, login, nowMs));
                    }
                }
            }
        }
        return out;
    }

    public static long overlap(Session s, long from, long to) {
        long a = Math.max(s.start(), from);
        long b = Math.min(s.end(), to);
        return Math.max(0L, b - a);
    }

    /** Суммарное время по игрокам (имя -> мс) за период, по убыванию. */
    public LinkedHashMap<String, Long> totals(List<Session> all, long from, long to) {
        Map<String, Long> sum = new HashMap<>();
        for (Session s : all) {
            long d = overlap(s, from, to);
            if (d > 0) sum.merge(s.name(), d, Long::sum);
        }
        List<Map.Entry<String, Long>> list = new ArrayList<>(sum.entrySet());
        list.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        LinkedHashMap<String, Long> res = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : list) res.put(e.getKey(), e.getValue());
        return res;
    }

    /** Время одного игрока за период. */
    public long playerTotal(List<Session> all, String uuid, String name, long from, long to) {
        long sum = 0;
        String nu = norm(uuid);
        for (Session s : all) {
            boolean mine = (!nu.isEmpty() && nu.equals(norm(s.uuid()))) || s.name().equalsIgnoreCase(name);
            if (mine) sum += overlap(s, from, to);
        }
        return sum;
    }

    /** Пик одновременного онлайна за период (и кто тогда играл). */
    public Peak peak(List<Session> all, long from, long to) {
        List<long[]> ev = new ArrayList<>(); // {время, +1/-1, индекс сессии}
        List<Session> used = new ArrayList<>();
        for (Session s : all) {
            long a = Math.max(s.start(), from);
            long b = Math.min(s.end(), to);
            if (b <= a) continue;
            int idx = used.size();
            used.add(s);
            ev.add(new long[]{a, 1, idx});
            ev.add(new long[]{b, -1, idx});
        }
        if (ev.isEmpty()) return null;
        // при равном времени сначала выходы, потом входы
        ev.sort((x, y) -> x[0] != y[0] ? Long.compare(x[0], y[0]) : Long.compare(x[1], y[1]));
        Map<String, Integer> active = new HashMap<>();
        int best = 0;
        long bestTime = 0;
        List<String> bestNames = new ArrayList<>();
        for (long[] e : ev) {
            String n = used.get((int) e[2]).name();
            if (e[1] > 0) {
                active.merge(n, 1, Integer::sum);
                if (active.size() > best) {
                    best = active.size();
                    bestTime = e[0];
                    bestNames = new ArrayList<>(new TreeSet<>(active.keySet()));
                }
            } else {
                Integer v = active.get(n);
                if (v != null) {
                    if (v <= 1) active.remove(n);
                    else active.put(n, v - 1);
                }
            }
        }
        return new Peak(best, bestTime, bestNames);
    }

    /** Часы онлайна по корзинам: границы edges (n+1 штук) -> n значений. */
    public double[] bucketHours(List<Session> all, long[] edges) {
        double[] res = new double[edges.length - 1];
        for (Session s : all) {
            for (int i = 0; i < res.length; i++) {
                long d = overlap(s, edges[i], edges[i + 1]);
                if (d > 0) res[i] += d / 3600000.0;
            }
        }
        return res;
    }

    /** Границы корзин и подписи для графика выбранного периода. Возвращает {edges, labels}. */
    public Object[] chartBuckets(Period p, ZoneId zone) {
        ZonedDateTime now = ZonedDateTime.now(zone);
        List<Long> edges = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        switch (p) {
            case DAY: {
                ZonedDateTime start = now.withMinute(0).withSecond(0).withNano(0).minusHours(23);
                DateTimeFormatter f = DateTimeFormatter.ofPattern("HH");
                for (int i = 0; i < 24; i++) {
                    edges.add(start.plusHours(i).toInstant().toEpochMilli());
                    labels.add(f.format(start.plusHours(i)));
                }
                edges.add(start.plusHours(24).toInstant().toEpochMilli());
                break;
            }
            case WEEK:
            case MONTH: {
                int n = p == Period.WEEK ? 7 : 30;
                ZonedDateTime start = now.toLocalDate().atStartOfDay(zone).minusDays(n - 1);
                DateTimeFormatter f = DateTimeFormatter.ofPattern("dd.MM");
                for (int i = 0; i < n; i++) {
                    edges.add(start.plusDays(i).toInstant().toEpochMilli());
                    labels.add(f.format(start.plusDays(i)));
                }
                edges.add(start.plusDays(n).toInstant().toEpochMilli());
                break;
            }
            default: {
                ZonedDateTime start = now.toLocalDate().withDayOfMonth(1).atStartOfDay(zone).minusMonths(11);
                DateTimeFormatter f = DateTimeFormatter.ofPattern("MM.yy");
                for (int i = 0; i < 12; i++) {
                    edges.add(start.plusMonths(i).toInstant().toEpochMilli());
                    labels.add(f.format(start.plusMonths(i)));
                }
                edges.add(start.plusMonths(12).toInstant().toEpochMilli());
                break;
            }
        }
        long[] e = new long[edges.size()];
        for (int i = 0; i < e.length; i++) e[i] = edges.get(i);
        return new Object[]{e, labels};
    }
}
