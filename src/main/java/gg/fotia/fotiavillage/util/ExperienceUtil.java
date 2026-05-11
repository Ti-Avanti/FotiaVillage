package gg.fotia.fotiavillage.util;

import org.bukkit.entity.Player;

public final class ExperienceUtil {
    private ExperienceUtil() {}

    public static int getTotalExperience(Player player) {
        int exp = 0;
        int level = player.getLevel();
        for (int i = 0; i < level; i++) {
            exp += getExperienceToLevelUp(i);
        }
        exp += Math.round(getExperienceToLevelUp(level) * player.getExp());
        return exp;
    }

    public static void setTotalExperience(Player player, int exp) {
        int remaining = Math.max(0, exp);
        player.setExp(0.0f);
        player.setLevel(0);
        player.setTotalExperience(0);
        int level = 0;
        int toNext = getExperienceToLevelUp(level);
        while (remaining >= toNext) {
            remaining -= toNext;
            level++;
            toNext = getExperienceToLevelUp(level);
        }
        player.setLevel(level);
        player.setExp((float) remaining / (float) toNext);
        player.setTotalExperience(exp);
    }

    public static int getExperienceForLevel(int targetLevel) {
        int total = 0;
        for (int i = 0; i < targetLevel; i++) {
            total += getExperienceToLevelUp(i);
        }
        return total;
    }

    public static int getExperienceToLevelUp(int level) {
        if (level >= 30) {
            return 112 + (level - 30) * 9;
        }
        if (level >= 15) {
            return 37 + (level - 15) * 5;
        }
        return 7 + level * 2;
    }
}
