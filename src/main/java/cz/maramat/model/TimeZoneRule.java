package cz.maramat.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Portable timezone + DST rule model shared by ModulePropertiesDialog, SimulationEngine, and MflwEmitter.
 * Encodes standard UTC offset, DST delta, and Northern/Southern hemisphere transition rules.
 */
public record TimeZoneRule(
        String id,
        String displayName,
        int stdOffsetMin,
        int dstOffsetMin,
        int startMonth, // 0 = no DST, 1..12
        int startWeek,  // 1..4 = nth weekday, -1 = last weekday, 0 = fixed day-of-month
        int startDow,   // 0=Sun..6=Sat (or day 1..31 when week==0)
        int startHour,  // local wall-clock hour (0..23)
        int endMonth,
        int endWeek,
        int endDow,
        int endHour
) {
    public static final TimeZoneRule UTC = new TimeZoneRule(
            "UTC", "UTC (UTC+00:00, No DST)", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

    public static final Map<String, TimeZoneRule> PRESETS = new LinkedHashMap<>();

    static {
        reg(UTC);
        reg(new TimeZoneRule("Europe/Prague", "Europe/Prague, Berlin, Paris (UTC+01:00, EU DST)",
                60, 60, 3, -1, 0, 2, 10, -1, 0, 3));
        reg(new TimeZoneRule("Europe/London", "Europe/London (UTC+00:00, UK DST)",
                0, 60, 3, -1, 0, 1, 10, -1, 0, 2));
        reg(new TimeZoneRule("Europe/Athens", "Europe/Athens, Helsinki (UTC+02:00, EU DST)",
                120, 60, 3, -1, 0, 3, 10, -1, 0, 4));
        reg(new TimeZoneRule("Europe/Moscow", "Europe/Moscow, Istanbul (UTC+03:00, No DST)",
                180, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        reg(new TimeZoneRule("America/New_York", "America/New_York (UTC-05:00, US DST)",
                -300, 60, 3, 2, 0, 2, 11, 1, 0, 2));
        reg(new TimeZoneRule("America/Chicago", "America/Chicago (UTC-06:00, US DST)",
                -360, 60, 3, 2, 0, 2, 11, 1, 0, 2));
        reg(new TimeZoneRule("America/Denver", "America/Denver (UTC-07:00, US DST)",
                -420, 60, 3, 2, 0, 2, 11, 1, 0, 2));
        reg(new TimeZoneRule("America/Phoenix", "America/Phoenix (UTC-07:00, No DST)",
                -420, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        reg(new TimeZoneRule("America/Los_Angeles", "America/Los_Angeles (UTC-08:00, US DST)",
                -480, 60, 3, 2, 0, 2, 11, 1, 0, 2));
        reg(new TimeZoneRule("America/Anchorage", "America/Anchorage (UTC-09:00, US DST)",
                -540, 60, 3, 2, 0, 2, 11, 1, 0, 2));
        reg(new TimeZoneRule("Pacific/Honolulu", "Pacific/Honolulu (UTC-10:00, No DST)",
                -600, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        reg(new TimeZoneRule("America/St_Johns", "America/St_Johns - Newfoundland (UTC-03:30, US DST)",
                -210, 60, 3, 2, 0, 2, 11, 1, 0, 2));
        reg(new TimeZoneRule("America/Sao_Paulo", "America/Sao_Paulo (UTC-03:00, No DST)",
                -180, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        reg(new TimeZoneRule("America/Santiago", "America/Santiago - Chile (UTC-04:00, Southern DST)",
                -240, 60, 9, 1, 6, 24, 4, 1, 6, 24));
        reg(new TimeZoneRule("Asia/Dubai", "Asia/Dubai (UTC+04:00, No DST)",
                240, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        reg(new TimeZoneRule("Asia/Kolkata", "Asia/Kolkata - India (UTC+05:30, No DST)",
                330, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        reg(new TimeZoneRule("Asia/Kathmandu", "Asia/Kathmandu - Nepal (UTC+05:45, No DST)",
                345, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        reg(new TimeZoneRule("Asia/Shanghai", "Asia/Shanghai, Singapore (UTC+08:00, No DST)",
                480, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        reg(new TimeZoneRule("Asia/Tokyo", "Asia/Tokyo, Seoul (UTC+09:00, No DST)",
                540, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        reg(new TimeZoneRule("Australia/Adelaide", "Australia/Adelaide (UTC+09:30, AU DST)",
                570, 60, 10, 1, 0, 2, 4, 1, 0, 3));
        reg(new TimeZoneRule("Australia/Sydney", "Australia/Sydney, Melbourne (UTC+10:00, AU DST)",
                600, 60, 10, 1, 0, 2, 4, 1, 0, 3));
        reg(new TimeZoneRule("Australia/Lord_Howe", "Australia/Lord_Howe (UTC+10:30, +00:30 DST)",
                630, 30, 10, 1, 0, 2, 4, 1, 0, 2));
        reg(new TimeZoneRule("Pacific/Auckland", "Pacific/Auckland (UTC+12:00, NZ DST)",
                720, 60, 9, -1, 0, 2, 4, 1, 0, 3));
        reg(new TimeZoneRule("Pacific/Chatham", "Pacific/Chatham (UTC+12:45, NZ DST)",
                765, 60, 9, -1, 0, 2, 4, 1, 0, 3));
        reg(new TimeZoneRule("Pacific/Kiritimati", "Pacific/Kiritimati (UTC+14:00, No DST)",
                840, 0, 0, 0, 0, 0, 0, 0, 0, 0));
    }

    private static void reg(TimeZoneRule r) {
        PRESETS.put(r.id(), r);
    }

    public static TimeZoneRule fromBlock(BlockInstance b) {
        Map<String, String> props = b.getCustomProperties();
        String tzId = props.getOrDefault("TimeZone", "UTC");
        if (!"Custom".equals(tzId) && PRESETS.containsKey(tzId)) {
            return PRESETS.get(tzId);
        }
        return new TimeZoneRule(
                tzId,
                "Custom",
                parseInt(props.get("TzStdOffsetMin"), 0),
                parseInt(props.get("TzDstOffsetMin"), 0),
                parseInt(props.get("TzDstStartMonth"), 0),
                parseInt(props.get("TzDstStartWeek"), 0),
                parseInt(props.get("TzDstStartDow"), 0),
                parseInt(props.get("TzDstStartHour"), 2),
                parseInt(props.get("TzDstEndMonth"), 0),
                parseInt(props.get("TzDstEndWeek"), 0),
                parseInt(props.get("TzDstEndDow"), 0),
                parseInt(props.get("TzDstEndHour"), 3)
        );
    }

    private static int parseInt(String s, int def) {
        if (s == null || s.isBlank()) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public int totalOffsetSecondsAtUtc(long utcSec) {
        if (startMonth <= 0 || endMonth <= 0 || dstOffsetMin == 0) {
            return stdOffsetMin * 60;
        }
        long stdDays = Math.floorDiv(utcSec + (long) stdOffsetMin * 60L, 86400L);
        int year = civilYearFromDays(stdDays);
        long startUtc = transitionUtc(year, startMonth, startWeek, startDow, startHour, stdOffsetMin);
        long endUtc = transitionUtc(year, endMonth, endWeek, endDow, endHour, stdOffsetMin + dstOffsetMin);
        boolean isDst = (startMonth < endMonth)
                ? (utcSec >= startUtc && utcSec < endUtc)
                : (utcSec >= startUtc || utcSec < endUtc);
        return (stdOffsetMin + (isDst ? dstOffsetMin : 0)) * 60;
    }

    public long localToUtc(int yr, int mon, int day, int hr, int min, int sec) {
        long localSec = daysFromCivil(yr, mon, day) * 86400L + hr * 3600L + min * 60L + sec;
        if (startMonth <= 0 || endMonth <= 0 || dstOffsetMin == 0) {
            return localSec - (long) stdOffsetMin * 60L;
        }
        long startWall = transitionLocalSec(yr, startMonth, startWeek, startDow, startHour);
        long endWall = transitionLocalSec(yr, endMonth, endWeek, endDow, endHour);
        boolean isDst = (startMonth < endMonth)
                ? (localSec >= startWall && localSec < endWall)
                : (localSec >= startWall || localSec < endWall);
        return localSec - (long) (stdOffsetMin + (isDst ? dstOffsetMin : 0)) * 60L;
    }

    private static long transitionUtc(int yr, int month, int week, int dow, int hour, int activeOffsetMin) {
        return transitionLocalSec(yr, month, week, dow, hour) - (long) activeOffsetMin * 60L;
    }

    private static long transitionLocalSec(int yr, int month, int week, int dow, int hour) {
        int day;
        if (week == 0) {
            day = Math.max(1, Math.min(daysInMonth(yr, month), dow));
        } else if (week > 0) {
            int firstDow = dowFromDays(daysFromCivil(yr, month, 1));
            int diff = (dow - firstDow + 7) % 7;
            day = 1 + diff + (week - 1) * 7;
            int dim = daysInMonth(yr, month);
            while (day > dim) day -= 7;
        } else {
            int dim = daysInMonth(yr, month);
            int lastDow = dowFromDays(daysFromCivil(yr, month, dim));
            int diff = (lastDow - dow + 7) % 7;
            day = dim - diff;
        }
        return daysFromCivil(yr, month, day) * 86400L + (long) hour * 3600L;
    }

    private static int daysInMonth(int yr, int month) {
        return switch (month) {
            case 2 -> (yr % 4 == 0 && (yr % 100 != 0 || yr % 400 == 0)) ? 29 : 28;
            case 4, 6, 9, 11 -> 30;
            default -> 31;
        };
    }

    private static int dowFromDays(long days) {
        return (int) Math.floorMod(days + 4L, 7L); // 1970-01-01 was Thursday (4); 0=Sun..6=Sat
    }

    private static long daysFromCivil(int y, int m, int d) {
        y -= (m <= 2) ? 1 : 0;
        long era = Math.floorDiv(y, 400);
        int yoe = (int) (y - era * 400);
        int doy = (153 * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
        int doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146097L + doe - 719468L;
    }

    private static int civilYearFromDays(long z) {
        z += 719468L;
        long era = Math.floorDiv(z, 146097L);
        int doe = (int) (z - era * 146097L);
        int yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
        int y = (int) (yoe + era * 400);
        int doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
        int mp = (5 * doy + 2) / 153;
        int m = mp + (mp < 10 ? 3 : -9);
        return y + (m <= 2 ? 1 : 0);
    }
}
