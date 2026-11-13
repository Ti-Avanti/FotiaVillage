package gg.fotia.fotiavillage.trade.lore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TradeLoreRendererTest {
    private final Map<String, List<String>> messages = new HashMap<>();
    private final TradeLoreRenderer renderer = new TradeLoreRenderer(
        key -> messages.getOrDefault(key, List.of(key)),
        (template, values) -> {
            String text = template;
            for (var entry : values.entrySet()) {
                text = text.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
            }
            return Component.text(text);
        }
    );

    @Test
    void customLayoutOwnsOrderAndPreservesExplicitBlankLines() {
        messages.put("trade-gui.lore", List.of("末尾信息放最前", "", "费用 {exp-amount} {exp-unit}", "自定义结束"));
        messages.put("trade-gui.header", List.of("不能自动追加的标题"));
        var context = new TradeLoreContext.Builder()
            .values(Map.of("exp-amount", 12, "exp-unit", "点"))
            .section("exp-cost", "exp-cost-points", Map.of("amount", 12)).build();
        assertEquals(List.of("末尾信息放最前", "", "费用 12 点", "自定义结束"), text(renderer.render(context, true)));
    }

    @Test
    void hiddenVariablesSuppressTheWholeLineWithoutRemovingStaticText() {
        messages.put("trade-gui.lore", List.of("固定说明", "物品 {items-used}/{items-limit}",
            "全局 {global-used}/{global-limit}", "混合 {global-used} {jobs-used}", "", "额外 {extra-emeralds-amount}"));
        var context = new TradeLoreContext.Builder().values(Map.of("global-used", 2, "global-limit", 10)).build();
        assertEquals(List.of("固定说明", "全局 2/10", ""), text(renderer.render(context, true)));
    }

    @Test
    void statusBlocksExpandMultipleLinesInPlaceWithLocalAndSharedVariables() {
        messages.put("trade-gui.lore", List.of("<emeralds-stats>", "中间", "<cooldown>"));
        messages.put("trade-gui.extra-emeralds-missing", List.of("可用 {available}", "", "缺少 {missing}，需要 {extra-emeralds-amount}"));
        messages.put("trade-gui.cooldown-remaining", List.of("冷却", "剩余 {time}"));
        var context = new TradeLoreContext.Builder().values(Map.of("extra-emeralds-amount", 5))
            .section("emeralds-stats", "extra-emeralds-missing", Map.of("available", 2, "missing", 3))
            .section("cooldown", "cooldown-remaining", Map.of("time", "10秒")).build();
        assertEquals(List.of("可用 2", "", "缺少 3，需要 5", "中间", "冷却", "剩余 10秒"), text(renderer.render(context, true)));
    }

    @Test
    void inactiveBlocksDoNotLeaveTheirMarkersInTheTooltip() {
        messages.put("trade-gui.lore", List.of("<cooldown>", "保留", "<emeralds-stats>"));
        assertEquals(List.of("保留"), text(renderer.render(new TradeLoreContext.Builder().build(), true)));
    }

    @Test
    void legacyLayoutSupportsMultilineFragmentsAndLocalAmounts() {
        messages.put("trade-gui.header", List.of("第一行", "", "共有 {global-limit} 次"));
        messages.put("trade-gui.exp-cost-levels", List.of("花费 {amount}", "第二条费用说明"));
        messages.put("trade-gui.limit-global", List.of("{used}/{limit}"));
        var context = new TradeLoreContext.Builder().values(Map.of("global-limit", 20))
            .section("limit-global", "limit-global", Map.of("used", 2, "limit", 20))
            .section("exp-cost", "exp-cost-levels", Map.of("amount", 3)).build();
        assertEquals(List.of("第一行", "", "共有 20 次", "花费 3", "第二条费用说明", "2/20"), text(renderer.render(context, false)));
    }

    @Test
    void explicitEmptyLayoutAlsoHidesDisabledStatusMessages() {
        messages.put("trade-gui.lore", List.of());
        messages.put("trade-gui.disabled", List.of("已关闭", "说明"));
        var context = new TradeLoreContext(Map.of(), Map.of(), "trade-gui.disabled");
        assertEquals(List.of(), renderer.render(context, true));
        assertEquals(List.of("已关闭", "说明"), text(renderer.render(context, false)));
    }

    @Test
    void emptyLegacySectionsDoNotProduceAnOrphanHeader() {
        messages.put("trade-gui.exp-cost-levels", List.of());
        var context = new TradeLoreContext.Builder().section("exp-cost", "exp-cost-levels", Map.of("amount", 3)).build();
        assertEquals(List.of(), renderer.render(context, false));
    }

    private static List<String> text(List<Component> components) {
        return components.stream().map(component -> ((TextComponent) component).content()).toList();
    }
}
