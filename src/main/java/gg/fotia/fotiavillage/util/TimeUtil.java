package gg.fotia.fotiavillage.util;

import gg.fotia.fotiavillage.config.FotiaSettings;

import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.Locale;

public final class TimeUtil {
    private TimeUtil() {}

    public static String resetKey(FotiaSettings.ResetPeriod period) {
        LocalDate now = LocalDate.now();
        return switch (period) {
            case DAILY -> now.toString();
            case WEEKLY -> now.getYear() + "-W" + now.get(WeekFields.of(Locale.getDefault()).weekOfWeekBasedYear());
            case MONTHLY -> now.getYear() + "-" + String.format("%02d", now.getMonthValue());
        };
    }
}
