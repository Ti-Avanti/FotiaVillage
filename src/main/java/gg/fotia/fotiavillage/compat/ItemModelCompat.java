package gg.fotia.fotiavillage.compat;

import org.bukkit.NamespacedKey;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.configuration.serialization.ConfigurationSerialization;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 通过 Bukkit 公共序列化接口兼容旧整数模型和新版物品组件。 */
public final class ItemModelCompat {
    private static final String ITEM_MODEL = "item-model";
    private static final String CUSTOM_MODEL_DATA = "custom-model-data";

    private ItemModelCompat() {
    }

    public static Optional<ItemMeta> withItemModel(ItemMeta meta, String model) {
        NamespacedKey key = VillagerRegistry.key(model);
        if (key == null) {
            return Optional.empty();
        }
        Map<String, Object> serialized = new HashMap<>(meta.serialize());
        serialized.put(ConfigurationSerialization.SERIALIZED_TYPE_KEY, "ItemMeta");
        serialized.put(ITEM_MODEL, key.toString());
        ConfigurationSerializable restored = ConfigurationSerialization.deserializeObject(serialized);
        // 旧服务端会忽略未知字段；不能把未写入模型的物品当成成功生成。
        if (restored instanceof ItemMeta result && matchesItemModel(result, key.toString())) {
            return Optional.of(result);
        }
        return Optional.empty();
    }

    public static boolean matchesItemModel(ItemMeta meta, String model) {
        NamespacedKey expected = VillagerRegistry.key(model);
        Object actual = meta.serialize().get(ITEM_MODEL);
        return expected != null && actual != null
            && expected.equals(VillagerRegistry.key(actual.toString()));
    }

    public static boolean matchesItemModel(ItemStack stack, ItemMeta meta, String model) {
        Object explicit = meta.serialize().get(ITEM_MODEL);
        NamespacedKey expected = VillagerRegistry.key(model);
        // 新版会从组件补丁中省略与物品默认值相同的模型。
        // 只有通过 withItemModel 能力检查的配置才会进入匹配流程。
        NamespacedKey actual = explicit == null ? stack.getType().getKey() : VillagerRegistry.key(explicit.toString());
        return expected != null && expected.equals(actual);
    }

    public static boolean matchesCustomModelData(ItemMeta meta, int expected) {
        Object model = meta.serialize().get(CUSTOM_MODEL_DATA);
        if (model instanceof Number number) {
            return number.intValue() == expected;
        }
        if (model instanceof ConfigurationSerializable component) {
            model = component.serialize();
        }
        if (model instanceof Map<?, ?> component
            && component.get("floats") instanceof List<?> floats
            && !floats.isEmpty() && floats.get(0) instanceof Number number) {
            // getCustomModelData() 会截断小数，1001.5 不能误认成配置的 1001。
            return Float.compare(number.floatValue(), (float) expected) == 0;
        }
        return false;
    }
}
