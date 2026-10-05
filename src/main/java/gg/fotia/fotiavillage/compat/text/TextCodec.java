package gg.fotia.fotiavillage.compat.text;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;

public interface TextCodec {
    Component deserialize(String input);

    String serialize(Component input);

    String escapeTags(String input);

    static TextCodec create() {
        String version = Bukkit.getBukkitVersion().split("-", 2)[0];
        // Paper 从 1.18.2 开始提供 MiniMessage；早期版本使用隔离解析器。
        return version.equals("1.18") || version.equals("1.18.1")
            ? new LegacyTextCodec() : new NativeTextCodec();
    }
}
