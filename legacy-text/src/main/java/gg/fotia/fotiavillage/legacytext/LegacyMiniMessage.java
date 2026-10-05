package gg.fotia.fotiavillage.legacytext;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;

/** 只通过 JSON 字符串跨越库边界，不向主插件暴露隔离后的 Adventure 类型。 */
public final class LegacyMiniMessage {
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final GsonComponentSerializer json = GsonComponentSerializer.gson();

    public String toJson(String input) {
        return json.serialize(miniMessage.deserialize(input));
    }

    public String fromJson(String input) {
        return miniMessage.serialize(json.deserialize(input));
    }

    public String escapeTags(String input) {
        return miniMessage.escapeTags(input);
    }
}
