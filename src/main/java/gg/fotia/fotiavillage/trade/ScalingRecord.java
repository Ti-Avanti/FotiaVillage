package gg.fotia.fotiavillage.trade;

public record ScalingRecord(double multiplier, int tradeCount, long lastTradeTime) {
    public static ScalingRecord empty() {
        return new ScalingRecord(1.0, 0, 0L);
    }
}
