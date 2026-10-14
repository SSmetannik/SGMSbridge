package sgms.bridge;

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;

/** Периоды для статистики. */
public enum Period {
    DAY("день", "День", 1),
    WEEK("неделю", "Неделя", 7),
    MONTH("месяц", "Месяц", 30),
    YEAR("год", "Год", 365);

    /** Для фраз «за день», «за неделю» и т. д. */
    public final String ru;
    /** Подпись: «День», «Неделя» и т. д. */
    public final String label;
    public final int days;

    Period(String ru, String label, int days) {
        this.ru = ru;
        this.label = label;
        this.days = days;
    }

    /** Начало периода в миллисекундах. */
    public long startMillis(boolean calendar, ZoneId zone) {
        ZonedDateTime now = ZonedDateTime.now(zone);
        if (!calendar) return now.minusDays(days).toInstant().toEpochMilli();
        ZonedDateTime s;
        switch (this) {
            case DAY:
                s = now.toLocalDate().atStartOfDay(zone);
                break;
            case WEEK:
                s = now.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(zone);
                break;
            case MONTH:
                s = now.toLocalDate().withDayOfMonth(1).atStartOfDay(zone);
                break;
            default:
                s = now.toLocalDate().withDayOfYear(1).atStartOfDay(zone);
                break;
        }
        return s.toInstant().toEpochMilli();
    }

    public static Period parse(String s) {
        if (s == null) return null;
        String t = s.trim().toLowerCase();
        for (Period p : values()) {
            if (p.name().toLowerCase().equals(t)) return p;
        }
        return null;
    }
}
