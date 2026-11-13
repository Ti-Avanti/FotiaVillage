package gg.fotia.fotiavillage.trade.lore;

import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Expands configured lines and standalone status blocks without accessing Bukkit state. */
public final class TradeLoreRenderer {
    private static final Pattern VARIABLE = Pattern.compile("\\{([^{}]+)}");
    private static final List<String> LEGACY_ORDER = List.of(
        "exp-cost", "exp-multiplier", "cooldown", "limit-global", "limit-profession", "limit-item",
        "extra-emeralds", "emeralds-stats"
    );
    private final Function<String, List<String>> templates;
    private final BiFunction<String, Map<String, ?>, Component> formatter;

    public TradeLoreRenderer(Function<String, List<String>> templates,
                             BiFunction<String, Map<String, ?>, Component> formatter) {
        this.templates = templates;
        this.formatter = formatter;
    }

    public List<Component> render(TradeLoreContext context, boolean customLayout) {
        List<String> layout = customLayout ? templates.apply("trade-gui.lore") : null;
        if (layout != null && layout.isEmpty()) {
            return List.of();
        }
        if (context.statusKey() != null) {
            return lines(templates.apply(context.statusKey()), context, Map.of(), false);
        }
        if (customLayout) {
            return lines(layout, context, Map.of(), true);
        }
        List<Component> result = new ArrayList<>();
        for (String name : LEGACY_ORDER) {
            result.addAll(section(name, context));
        }
        if (!result.isEmpty()) {
            result.addAll(0, lines(templates.apply("trade-gui.header"), context, Map.of(), false));
        }
        return result;
    }

    private List<Component> section(String name, TradeLoreContext context) {
        TradeLoreContext.Section section = context.sections().get(name);
        return section == null ? List.of()
            : lines(templates.apply(section.key()), context, section.replacements(), false);
    }

    private List<Component> lines(List<String> templates, TradeLoreContext context,
                                  Map<String, ?> localValues, boolean expandBlocks) {
        Map<String, Object> replacements = new HashMap<>(context.values());
        replacements.putAll(localValues);
        List<Component> result = new ArrayList<>();
        for (String template : templates) {
            String block = template.trim();
            if (expandBlocks && (block.equals("<cooldown>") || block.equals("<emeralds-stats>"))) {
                result.addAll(section(block.substring(1, block.length() - 1), context));
                continue;
            }
            if (visible(template, replacements)) {
                result.add(formatter.apply(template, replacements));
            }
        }
        return result;
    }

    private boolean visible(String template, Map<String, ?> values) {
        Matcher matcher = VARIABLE.matcher(template);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (TradeLoreContext.VARIABLES.contains(name) && !values.containsKey(name)) {
                return false;
            }
        }
        return true;
    }
}
