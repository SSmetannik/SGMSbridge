package sgms.bridge;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Мелкие помощники для форматирования текста. */
public final class Fmt {
    private Fmt() {}

    /** Экранирование для Telegram HTML. */
    public static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Подстановка {ключ} и угловых скобок с ключом в шаблон: fill(tpl, "username", "Steve", ...). */
    public static String fill(String tpl, String... kv) {
        String r = tpl == null ? "" : tpl;
        for (int i = 0; i + 1 < kv.length; i += 2) {
            r = r.replace("{" + kv[i] + "}", kv[i + 1]).replace("<" + kv[i] + ">", kv[i + 1]);
        }
        return r;
    }

    /** 3 600 000 мс -> "1 ч 0 мин". */
    public static String dur(long ms) {
        long totalMin = Math.max(0, ms) / 60000L;
        long h = totalMin / 60;
        long m = totalMin % 60;
        return h + " ч " + m + " мин";
    }

    public static String date(long ms, ZoneId zone) {
        if (ms <= 0) return "—";
        return DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(zone).format(Instant.ofEpochMilli(ms));
    }
}
