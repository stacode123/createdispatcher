package net.Dispatcher.web.auth;

/** Web permission tiers, ordered. Allowlist entries map Discord user ids to one of these. */
public enum Tier {
    NONE, VIEWER, PLANNER, DEPLOYER;

    public boolean atLeast(Tier other) {
        return ordinal() >= other.ordinal();
    }

    public static Tier parse(String value) {
        if (value == null) return NONE;
        for (Tier tier : values())
            if (tier.name().equalsIgnoreCase(value)) return tier;
        return NONE;
    }
}
