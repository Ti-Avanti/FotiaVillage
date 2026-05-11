package gg.fotia.fotiavillage.stats;

import java.util.Map;
import java.util.UUID;

public record PlayerTradeStats(UUID uuid, String playerName, int totalTrades, int totalExpSpent, long lastTradeTime, Map<String, Integer> itemCounts) {
}
