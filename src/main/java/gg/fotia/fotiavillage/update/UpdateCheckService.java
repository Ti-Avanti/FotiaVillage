package gg.fotia.fotiavillage.update;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import gg.fotia.fotiavillage.FotiaVillagePlugin;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

public final class UpdateCheckService {
    private static final String REPOSITORY = "Ti-Avanti/FotiaVillage";
    private static final String LATEST_RELEASE_API = "https://api.github.com/repos/" + REPOSITORY + "/releases/latest";
    private static final String RELEASES_URL = "https://github.com/" + REPOSITORY + "/releases";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final FotiaVillagePlugin plugin;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    public UpdateCheckService(FotiaVillagePlugin plugin) {
        this.plugin = plugin;
    }

    public void checkAsync() {
        if (!plugin.settings().updateChecker().enabled()) {
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, this::check);
    }

    private void check() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(LATEST_RELEASE_API))
                .timeout(TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "FotiaVillage/" + plugin.getPluginMeta().getVersion())
                .GET()
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            runSync(() -> handleResponse(response.statusCode(), response.body()));
        } catch (Exception ex) {
            runSync(() ->
                plugin.getLogger().warning(plugin.language().plain("update.check-failed", Map.of("error", ex.getMessage())))
            );
        }
    }

    private void runSync(Runnable task) {
        try {
            if (plugin.isEnabled()) {
                plugin.getServer().getScheduler().runTask(plugin, task);
            }
        } catch (IllegalPluginAccessException ignored) {
        }
    }

    private void handleResponse(int statusCode, String body) {
        if (statusCode == 404) {
            plugin.getLogger().info(plugin.language().plain("update.no-release", Map.of("url", RELEASES_URL)));
            return;
        }
        if (statusCode != 200) {
            plugin.getLogger().warning(plugin.language().plain("update.check-failed", Map.of("error", "HTTP " + statusCode)));
            return;
        }
        try {
            ReleaseInfo latest = parse(body);
            String currentVersion = plugin.getPluginMeta().getVersion();
            if (compareVersions(currentVersion, latest.version()) < 0) {
                plugin.getLogger().warning(plugin.language().plain("update.available-console", Map.of(
                    "current", currentVersion,
                    "latest", latest.version(),
                    "url", latest.url()
                )));
                notifyAdmins(currentVersion, latest);
            } else if (plugin.settings().debug()) {
                plugin.getLogger().info(plugin.language().plain("update.latest", Map.of("version", currentVersion)));
            }
        } catch (Exception ex) {
            plugin.getLogger().warning(plugin.language().plain("update.check-failed", Map.of("error", ex.getMessage())));
        }
    }

    private ReleaseInfo parse(String body) {
        JsonObject json = JsonParser.parseString(body).getAsJsonObject();
        String tag = json.has("tag_name") && !json.get("tag_name").isJsonNull() ? json.get("tag_name").getAsString() : "";
        String url = json.has("html_url") && !json.get("html_url").isJsonNull() ? json.get("html_url").getAsString() : RELEASES_URL;
        return new ReleaseInfo(normalizeVersion(tag), url);
    }

    private void notifyAdmins(String currentVersion, ReleaseInfo latest) {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.hasPermission("fotiavillage.admin")) {
                plugin.language().prefixed(player, "update.available-player", Map.of(
                    "current", currentVersion,
                    "latest", latest.version(),
                    "url", latest.url()
                ));
            }
        }
    }

    private int compareVersions(String current, String latest) {
        int[] currentParts = versionParts(current);
        int[] latestParts = versionParts(latest);
        int length = Math.max(currentParts.length, latestParts.length);
        for (int i = 0; i < length; i++) {
            int currentPart = i < currentParts.length ? currentParts[i] : 0;
            int latestPart = i < latestParts.length ? latestParts[i] : 0;
            if (currentPart != latestPart) {
                return Integer.compare(currentPart, latestPart);
            }
        }
        return 0;
    }

    private int[] versionParts(String version) {
        String normalized = normalizeVersion(version);
        if (normalized.isBlank()) {
            return new int[] {0};
        }
        String[] rawParts = normalized.split("\\.");
        int[] parts = new int[rawParts.length];
        for (int i = 0; i < rawParts.length; i++) {
            parts[i] = parsePart(rawParts[i]);
        }
        return parts;
    }

    private int parsePart(String raw) {
        String digits = raw.replaceAll("[^0-9].*$", "");
        if (digits.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private String normalizeVersion(String version) {
        String normalized = version == null ? "" : version.trim();
        if (normalized.startsWith("v") || normalized.startsWith("V")) {
            normalized = normalized.substring(1);
        }
        int metadataStart = normalized.indexOf('+');
        if (metadataStart >= 0) {
            normalized = normalized.substring(0, metadataStart);
        }
        return normalized;
    }

    private record ReleaseInfo(String version, String url) {}
}
