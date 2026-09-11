package gg.fotia.fotiavillage.database;

import gg.fotia.fotiavillage.trade.ScalingRecord;

import java.util.HashMap;
import java.util.Map;

/** 在线玩家的交易状态；事务仅修改副本，提交入队后才发布。 */
final class PlayerTradeState {
    final String resetKey;
    final Map<LimitKey, Integer> limits;
    final Map<CooldownKey, Long> cooldowns;
    final Map<String, ScalingRecord> scaling;

    PlayerTradeState(String resetKey) {
        this(resetKey, new HashMap<>(), new HashMap<>(), new HashMap<>());
    }

    private PlayerTradeState(String resetKey, Map<LimitKey, Integer> limits,
                             Map<CooldownKey, Long> cooldowns, Map<String, ScalingRecord> scaling) {
        this.resetKey = resetKey;
        this.limits = limits;
        this.cooldowns = cooldowns;
        this.scaling = scaling;
    }

    PlayerTradeState copy() {
        return new PlayerTradeState(resetKey, new HashMap<>(limits), new HashMap<>(cooldowns), new HashMap<>(scaling));
    }

    record LimitKey(String type, String key) {}
    record CooldownKey(String profession, String item) {}
}
