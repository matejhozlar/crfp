package com.saunhardy.crfp;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue MAX_DURATION_MINUTES = BUILDER
            .comment("Hard cap on how long a single chunkloader may be active, in minutes.",
                    "Enforced on /cl add and /cl extend.")
            .defineInRange("maxDurationMinutes", 1440, 1, 1440);

    public static final ModConfigSpec.IntValue DEFAULT_DURATION_MINUTES = BUILDER
            .comment("Default duration in minutes when the user omits the argument.")
            .defineInRange("defaultDurationMinutes", 60, 1, 1440);

    public static final ModConfigSpec.BooleanValue ALLOW_PERMANENT = BUILDER
            .comment("Whether '/crfp add permanent' is allowed. A permanent chunkloader has no timer",
                    "and survives restarts until an admin runs '/crfp remove'. maxDurationMinutes does not apply to it.")
            .define("allowPermanentLoaders", true);

    public static final ModConfigSpec.IntValue WARN_BEFORE_EXPIRY_SECONDS = BUILDER
            .comment("Seconds before expiry to send an action-bar warning to the creator (0 disables).")
            .defineInRange("warnBeforeExpirySeconds", 30, 0, 3600);

    public static final ModConfigSpec.BooleanValue SHOW_TAB_LIST_PREFIX = BUILDER
            .comment("Show a '[CRFP]' prefix in front of fake players in the tab list.",
                    "With it off a loader appears under its bare name, like a regular player.")
            .define("showTabListPrefix", true);

    public static final ModConfigSpec.IntValue PERMISSION_LEVEL = BUILDER
            .comment("Required OP level to use /cl commands.")
            .defineInRange("permissionLevel", 2, 0, 4);

    static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {}
}
