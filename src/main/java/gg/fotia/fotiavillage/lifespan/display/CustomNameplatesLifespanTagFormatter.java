package gg.fotia.fotiavillage.lifespan.display;

import gg.fotia.fotiavillage.FotiaVillagePlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.momirealms.customnameplates.api.CustomNameplatesAPI;
import net.momirealms.customnameplates.api.feature.nameplate.Nameplate;
import net.momirealms.customnameplates.api.helper.AdventureHelper;

import java.util.HashSet;
import java.util.Set;

public final class CustomNameplatesLifespanTagFormatter implements LifespanTagFormatter {
    private final FotiaVillagePlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Set<String> warnedMissingNameplates = new HashSet<>();
    private boolean warnedApiFailure;

    public CustomNameplatesLifespanTagFormatter(FotiaVillagePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public LifespanDisplayText format(Component text) {
        try {
            CustomNameplatesAPI api = CustomNameplatesAPI.getInstance();
            String configuredId = plugin.settings().lifespan().customNameplatesNameplateId();
            String nameplateId = configuredId.isBlank() ? api.plugin().getNameplateManager().defaultNameplateId() : configuredId;
            Nameplate nameplate = api.getNameplate(nameplateId).orElse(null);
            if (nameplate == null) {
                warnMissingNameplate(nameplateId);
                return LifespanDisplayText.plain(text);
            }
            String tag = miniMessage.serialize(text);
            float advance = api.getTextAdvance(tag);
            String rawLine = AdventureHelper.surroundWithNameplatesFont(nameplate.createImagePrefix(advance, 0, 0))
                + tag
                + AdventureHelper.surroundWithNameplatesFont(nameplate.createImageSuffix(advance, 0, 0));
            return new LifespanDisplayText(miniMessage.deserialize(rawLine), rawLine);
        } catch (LinkageError | RuntimeException ex) {
            warnApiFailure(ex);
            return LifespanDisplayText.plain(text);
        }
    }

    private void warnMissingNameplate(String nameplateId) {
        if (!warnedMissingNameplates.add(nameplateId)) {
            return;
        }
        plugin.getLogger().warning("CustomNameplates nameplate '" + nameplateId + "' was not found. Falling back to plain lifespan tag text.");
    }

    private void warnApiFailure(Throwable ex) {
        if (warnedApiFailure) {
            return;
        }
        warnedApiFailure = true;
        plugin.getLogger().warning("Failed to format lifespan tag with CustomNameplates: " + ex.getMessage());
    }
}
