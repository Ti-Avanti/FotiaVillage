package gg.fotia.fotiavillage.trade.lore;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Immutable values and applicable message sections for one player's trade preview. */
public record TradeLoreContext(Map<String, Object> values, Map<String, Section> sections, String statusKey) {
    public static final Set<String> VARIABLES = Set.of(
        "exp-amount", "exp-unit", "exp-multiplier", "scaling", "group", "cooldown",
        "global-used", "global-limit", "jobs-used", "jobs-limit", "items-used", "items-limit",
        "extra-emeralds-amount", "emeralds-available", "emeralds-missing"
    );

    public TradeLoreContext {
        values = Map.copyOf(values);
        sections = Map.copyOf(sections);
    }

    public record Section(String key, Map<String, ?> replacements) {
        public Section {
            replacements = Map.copyOf(replacements);
        }
    }

    public static final class Builder {
        private final Map<String, Object> values = new LinkedHashMap<>();
        private final Map<String, Section> sections = new LinkedHashMap<>();

        public Builder values(Map<String, ?> additions) {
            values.putAll(additions);
            return this;
        }

        public Builder section(String name, String message, Map<String, ?> replacements) {
            sections.put(name, new Section("trade-gui." + message, replacements));
            return this;
        }

        public TradeLoreContext build() {
            return new TradeLoreContext(values, sections, null);
        }
    }
}
