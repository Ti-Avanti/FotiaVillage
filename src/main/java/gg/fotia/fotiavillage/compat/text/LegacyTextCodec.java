package gg.fotia.fotiavillage.compat.text;

import gg.fotia.fotiavillage.legacytext.LegacyMiniMessage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;

final class LegacyTextCodec implements TextCodec {
    private final LegacyMiniMessage miniMessage = new LegacyMiniMessage();
    private final GsonComponentSerializer serverJson = GsonComponentSerializer.gson();

    @Override
    public Component deserialize(String input) {
        return serverJson.deserialize(miniMessage.toJson(input));
    }

    @Override
    public String serialize(Component input) {
        return miniMessage.fromJson(serverJson.serialize(input));
    }

    @Override
    public String escapeTags(String input) {
        return miniMessage.escapeTags(input);
    }
}
