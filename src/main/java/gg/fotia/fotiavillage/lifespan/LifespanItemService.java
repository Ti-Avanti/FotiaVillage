package gg.fotia.fotiavillage.lifespan;

import gg.fotia.fotiavillage.FotiaVillagePlugin;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class LifespanItemService {
    private final FotiaVillagePlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final LegacyComponentSerializer legacySection = LegacyComponentSerializer.legacySection();
    private FileConfiguration config;
    private boolean enabled;
    private boolean requireSneaking;
    private List<LifespanItem> items = List.of();

    public LifespanItemService(FotiaVillagePlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        ensureDefault();
        config = loadConfig(new File(plugin.getDataFolder(), "lifespan-items.yml"));
        loadDefaults();
        enabled = config.getBoolean("enabled", true);
        requireSneaking = config.getBoolean("require-sneaking", true);
        items = readItems(config.getConfigurationSection("items"));
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean requireSneaking() {
        return requireSneaking;
    }

    public Optional<LifespanItem> findMatchingItem(ItemStack stack) {
        if (!enabled || stack == null || stack.getType().isAir()) {
            return Optional.empty();
        }
        return items.stream()
            .filter(LifespanItem::enabled)
            .filter(item -> stack.getAmount() >= item.requiredAmount())
            .filter(item -> item.matcher().matches(stack))
            .findFirst();
    }

    public List<String> itemIds() {
        return items.stream()
            .map(LifespanItem::id)
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
    }

    public Optional<LifespanItem> findItem(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return items.stream()
            .filter(item -> item.id().equalsIgnoreCase(id))
            .findFirst();
    }

    @SuppressWarnings("deprecation")
    public Optional<ItemStack> createItem(String id, int amount) {
        Optional<LifespanItem> configured = findItem(id);
        if (configured.isEmpty()) {
            return Optional.empty();
        }
        ItemMatcher matcher = configured.get().matcher();
        if (matcher.material() == null) {
            return Optional.empty();
        }
        ItemStack stack = new ItemStack(matcher.material(), Math.max(1, amount));
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return Optional.of(stack);
        }
        matcher.name().ifPresent(name -> meta.setDisplayName(renderItemText(name)));
        matcher.lore().ifPresent(lore -> meta.setLore(lore.stream().map(this::renderItemText).toList()));
        Optional<Integer> modelData = matcher.customModelData().isPresent() ? matcher.customModelData() : matcher.modelData();
        modelData.ifPresent(meta::setCustomModelData);
        matcher.damage().ifPresent(damage -> {
            if (meta instanceof Damageable damageable) {
                damageable.setDamage(Math.max(0, damage));
            }
        });
        matcher.unbreakable().ifPresent(meta::setUnbreakable);
        matcher.enchantments().forEach((enchantment, level) -> meta.addEnchant(enchantment, level, true));
        if (!matcher.itemFlags().isEmpty()) {
            meta.addItemFlags(matcher.itemFlags().toArray(ItemFlag[]::new));
        }
        stack.setItemMeta(meta);
        return Optional.of(stack);
    }

    private void ensureDefault() {
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }
        File file = new File(plugin.getDataFolder(), "lifespan-items.yml");
        if (!file.exists()) {
            plugin.saveResource("lifespan-items.yml", false);
        }
    }

    private FileConfiguration loadConfig(File file) {
        try (FileInputStream input = new FileInputStream(file); InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to load lifespan-items.yml: " + ex.getMessage());
            return new YamlConfiguration();
        }
    }

    private void loadDefaults() {
        InputStream stream = plugin.getResource("lifespan-items.yml");
        if (stream == null) {
            return;
        }
        try (InputStream input = stream; InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            config.setDefaults(YamlConfiguration.loadConfiguration(reader));
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to load bundled lifespan item defaults: " + ex.getMessage());
        }
    }

    private List<LifespanItem> readItems(ConfigurationSection section) {
        if (section == null) {
            return List.of();
        }
        List<LifespanItem> result = new ArrayList<>();
        for (String id : section.getKeys(false)) {
            ConfigurationSection itemSection = section.getConfigurationSection(id);
            if (itemSection == null) {
                continue;
            }
            ItemMatcher matcher = readMatcher(itemSection.getConfigurationSection("item"));
            if (matcher.material() == null) {
                plugin.getLogger().warning("lifespan-items.yml 中的道具 " + id + " 缺少有效 material，已跳过。");
                continue;
            }
            ConfigurationSection target = itemSection.getConfigurationSection("target");
            result.add(new LifespanItem(
                id,
                itemSection.getBoolean("enabled", true),
                Math.max(1, itemSection.getInt("days", 1)),
                Math.max(1, itemSection.getInt("required-amount", 1)),
                itemSection.getBoolean("consume", true),
                Math.max(1, itemSection.getInt("consume-amount", itemSection.getInt("required-amount", 1))),
                itemSection.getBoolean("consume-in-creative", false),
                itemSection.getString("permission", ""),
                readProfessions(target),
                readTypes(target),
                readNames(target),
                matcher
            ));
        }
        return List.copyOf(result);
    }

    private ItemMatcher readMatcher(ConfigurationSection section) {
        if (section == null) {
            return new ItemMatcher(null, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Map.of(), Set.of());
        }
        Material material = Material.matchMaterial(section.getString("material", ""));
        List<String> lore = section.contains("lore") ? section.getStringList("lore") : null;
        return new ItemMatcher(
            material,
            optionalString(section.getString("name")),
            Optional.ofNullable(lore),
            optionalString(section.getString("item-model")),
            optionalInteger(section, "custom-model-data"),
            optionalInteger(section, "model-data"),
            optionalInteger(section, "damage"),
            optionalBoolean(section, "unbreakable"),
            readEnchantments(section.getConfigurationSection("enchantments")),
            readItemFlags(section.getStringList("item-flags"))
        );
    }

    private Set<Villager.Profession> readProfessions(ConfigurationSection target) {
        if (target == null) {
            return Set.of();
        }
        Set<Villager.Profession> result = new HashSet<>();
        for (String raw : target.getStringList("professions")) {
            if (raw.equals("*")) {
                return Set.of();
            }
            try {
                result.add(Villager.Profession.valueOf(raw.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Invalid villager profession in lifespan-items.yml: " + raw);
            }
        }
        return Set.copyOf(result);
    }

    private Set<Villager.Type> readTypes(ConfigurationSection target) {
        if (target == null) {
            return Set.of();
        }
        Set<Villager.Type> result = new HashSet<>();
        for (String raw : target.getStringList("types")) {
            if (raw.equals("*")) {
                return Set.of();
            }
            try {
                result.add(Villager.Type.valueOf(raw.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Invalid villager type in lifespan-items.yml: " + raw);
            }
        }
        return Set.copyOf(result);
    }

    private List<String> readNames(ConfigurationSection target) {
        if (target == null) {
            return List.of();
        }
        return target.getStringList("names");
    }

    @SuppressWarnings("deprecation")
    private Map<Enchantment, Integer> readEnchantments(ConfigurationSection section) {
        if (section == null) {
            return Map.of();
        }
        Map<Enchantment, Integer> result = new HashMap<>();
        for (String key : section.getKeys(false)) {
            Enchantment enchantment = enchantment(key);
            if (enchantment == null) {
                plugin.getLogger().warning("Invalid enchantment in lifespan-items.yml: " + key);
                continue;
            }
            result.put(enchantment, Math.max(1, section.getInt(key, 1)));
        }
        return Map.copyOf(result);
    }

    @SuppressWarnings("deprecation")
    private Enchantment enchantment(String raw) {
        String value = raw.toLowerCase(Locale.ROOT);
        NamespacedKey key = value.contains(":") ? NamespacedKey.fromString(value) : NamespacedKey.minecraft(value);
        Enchantment enchantment = key == null ? null : Enchantment.getByKey(key);
        if (enchantment != null) {
            return enchantment;
        }
        return Enchantment.getByName(raw.toUpperCase(Locale.ROOT));
    }

    private Set<ItemFlag> readItemFlags(List<String> rawFlags) {
        Set<ItemFlag> result = new HashSet<>();
        for (String raw : rawFlags) {
            try {
                result.add(ItemFlag.valueOf(raw.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Invalid item flag in lifespan-items.yml: " + raw);
            }
        }
        return Set.copyOf(result);
    }

    private Optional<String> optionalString(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value);
    }

    private Optional<Integer> optionalInteger(ConfigurationSection section, String path) {
        return section.contains(path) ? Optional.of(section.getInt(path)) : Optional.empty();
    }

    private Optional<Boolean> optionalBoolean(ConfigurationSection section, String path) {
        return section.contains(path) ? Optional.of(section.getBoolean(path)) : Optional.empty();
    }

    private static boolean textEquals(String actual, String expected) {
        String expectedColored = ChatColor.translateAlternateColorCodes('&', expected);
        return actual.equals(expectedColored) || plainText(actual).equals(plainText(expected));
    }

    private static String plainText(String value) {
        String withoutTags = value.replaceAll("<[^>]+>", "");
        String colored = ChatColor.translateAlternateColorCodes('&', withoutTags);
        String stripped = ChatColor.stripColor(colored);
        return stripped == null ? "" : stripped.trim();
    }

    private String renderItemText(String value) {
        if (value.contains("<")) {
            return legacySection.serialize(miniMessage.deserialize(value));
        }
        return ChatColor.translateAlternateColorCodes('&', value);
    }

    public record LifespanItem(
        String id,
        boolean enabled,
        int days,
        int requiredAmount,
        boolean consume,
        int consumeAmount,
        boolean consumeInCreative,
        String permission,
        Set<Villager.Profession> professions,
        Set<Villager.Type> types,
        List<String> names,
        ItemMatcher matcher
    ) {
        public boolean hasPermission(org.bukkit.entity.Player player) {
            return permission == null || permission.isBlank() || player.hasPermission(permission);
        }

        public boolean matchesTarget(Villager villager) {
            if (!professions.isEmpty() && !professions.contains(villager.getProfession())) {
                return false;
            }
            if (!types.isEmpty() && !types.contains(villager.getVillagerType())) {
                return false;
            }
            if (!names.isEmpty()) {
                String name = villager.getCustomName();
                if (name == null) {
                    return false;
                }
                return names.stream().anyMatch(expected -> textEquals(name, expected));
            }
            return true;
        }
    }

    public record ItemMatcher(
        Material material,
        Optional<String> name,
        Optional<List<String>> lore,
        Optional<String> itemModel,
        Optional<Integer> customModelData,
        Optional<Integer> modelData,
        Optional<Integer> damage,
        Optional<Boolean> unbreakable,
        Map<Enchantment, Integer> enchantments,
        Set<ItemFlag> itemFlags
    ) {
        public boolean matches(ItemStack stack) {
            if (stack.getType() != material) {
                return false;
            }
            ItemMeta meta = stack.getItemMeta();
            if (meta == null) {
                return name.isEmpty() && lore.isEmpty() && itemModel.isEmpty() && customModelData.isEmpty()
                    && modelData.isEmpty() && damage.isEmpty() && unbreakable.isEmpty() && enchantments.isEmpty() && itemFlags.isEmpty();
            }
            if (name.isPresent() && (!meta.hasDisplayName() || !textEquals(meta.getDisplayName(), name.get()))) {
                return false;
            }
            if (lore.isPresent() && !matchesLore(meta, lore.get())) {
                return false;
            }
            Optional<Integer> requiredModelData = customModelData.isPresent() ? customModelData : modelData;
            if (requiredModelData.isPresent() && (!meta.hasCustomModelData() || meta.getCustomModelData() != requiredModelData.get())) {
                return false;
            }
            if (itemModel.isPresent() && !matchesItemModel(meta, itemModel.get())) {
                return false;
            }
            if (damage.isPresent() && (!(meta instanceof Damageable damageable) || !damageable.hasDamage() || damageable.getDamage() != damage.get())) {
                return false;
            }
            if (unbreakable.isPresent() && meta.isUnbreakable() != unbreakable.get()) {
                return false;
            }
            for (Map.Entry<Enchantment, Integer> entry : enchantments.entrySet()) {
                if (meta.getEnchantLevel(entry.getKey()) != entry.getValue()) {
                    return false;
                }
            }
            for (ItemFlag flag : itemFlags) {
                if (!meta.hasItemFlag(flag)) {
                    return false;
                }
            }
            return true;
        }

        private boolean matchesLore(ItemMeta meta, List<String> expectedLore) {
            if (!meta.hasLore()) {
                return expectedLore.isEmpty();
            }
            List<String> actualLore = meta.getLore();
            if (actualLore == null || actualLore.size() != expectedLore.size()) {
                return false;
            }
            for (int i = 0; i < expectedLore.size(); i++) {
                if (!textEquals(actualLore.get(i), expectedLore.get(i))) {
                    return false;
                }
            }
            return true;
        }

        private boolean matchesItemModel(ItemMeta meta, String expected) {
            Object value = findSerializedValue(meta.serialize());
            if (value == null) {
                return false;
            }
            return normalizeItemModel(String.valueOf(value)).equals(normalizeItemModel(expected));
        }

        private Object findSerializedValue(Object value) {
            if (value instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    String key = String.valueOf(entry.getKey());
                    if (isItemModelKey(key)) {
                        return entry.getValue();
                    }
                    Object nested = findSerializedValue(entry.getValue());
                    if (nested != null) {
                        return nested;
                    }
                }
            } else if (value instanceof Iterable<?> iterable) {
                for (Object element : iterable) {
                    Object nested = findSerializedValue(element);
                    if (nested != null) {
                        return nested;
                    }
                }
            }
            return null;
        }

        private boolean isItemModelKey(String key) {
            String normalized = key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
            return normalized.equals("itemmodel");
        }

        private String normalizeItemModel(String value) {
            return value.trim().toLowerCase(Locale.ROOT);
        }
    }
}
