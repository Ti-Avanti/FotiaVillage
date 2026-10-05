package gg.fotia.fotiavillage.compat;

import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Villager;

import java.util.Locale;

/** 使用各版本共有的注册表接口，避免依赖职业和类型是否为枚举。 */
public final class VillagerRegistry {
    private VillagerRegistry() {
    }

    public static Villager.Profession profession(String value) {
        NamespacedKey key = key(value);
        return key == null ? null : Registry.VILLAGER_PROFESSION.get(key);
    }

    public static Villager.Type type(String value) {
        NamespacedKey key = key(value);
        return key == null ? null : Registry.VILLAGER_TYPE.get(key);
    }

    public static NamespacedKey key(String value) {
        return value == null ? null : NamespacedKey.fromString(value.trim().toLowerCase(Locale.ROOT));
    }

    public static String legacyName(Keyed value) {
        // 保留已有配置和数据库中的 FARMER 等键，升级时不重置限制或统计。
        NamespacedKey key = value.getKey();
        String name = NamespacedKey.MINECRAFT.equals(key.getNamespace()) ? key.getKey() : key.toString();
        return name.toUpperCase(Locale.ROOT);
    }
}
