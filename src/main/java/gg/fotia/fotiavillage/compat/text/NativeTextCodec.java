package gg.fotia.fotiavillage.compat.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

final class NativeTextCodec implements TextCodec {
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    @Override
    public Component deserialize(String input) {
        return miniMessage.deserialize(input);
    }

    @Override
    public String serialize(Component input) {
        return miniMessage.serialize(input);
    }

    @Override
    public String escapeTags(String input) {
        return miniMessage.escapeTags(input);
    }
}
