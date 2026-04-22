package com.saunhardy.crfp.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.saunhardy.crfp.CRFP;
import com.saunhardy.crfp.Config;
import com.saunhardy.crfp.core.Chunkloader;
import com.saunhardy.crfp.core.ChunkloaderRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.text.SimpleDateFormat;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

public final class CRFPCommands {
    private static final SimpleCommandExceptionType ERR_NOT_PLAYER =
            new SimpleCommandExceptionType(Component.literal("/crfp add must be run by a player"));
    private static final SimpleCommandExceptionType ERR_NO_REGISTRY =
            new SimpleCommandExceptionType(Component.literal("Registry not ready"));
    private static final SimpleCommandExceptionType ERR_NOT_FOUND =
            new SimpleCommandExceptionType(Component.literal("No such chunkloader"));

    private CRFPCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("crfp")
                .requires(src -> src.hasPermission(Config.PERMISSION_LEVEL.get()));

        root.then(Commands.literal("add")
                .then(Commands.argument("minutes", IntegerArgumentType.integer(1, 1440))
                        .executes(ctx -> doAdd(ctx, ""))
                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                .executes(ctx -> doAdd(ctx, StringArgumentType.getString(ctx, "reason"))))));

        root.then(Commands.literal("list")
                .executes(CRFPCommands::doList));

        root.then(Commands.literal("remove")
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(activeNames(), b))
                        .executes(CRFPCommands::doRemove)));

        root.then(Commands.literal("extend")
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(activeNames(), b))
                        .then(Commands.argument("minutes", IntegerArgumentType.integer(1, 1440))
                                .executes(CRFPCommands::doExtend))));

        root.then(Commands.literal("info")
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(activeNames(), b))
                        .executes(CRFPCommands::doInfo)));

        root.then(Commands.literal("history")
                .executes(ctx -> doHistory(ctx, 10))
                .then(Commands.argument("limit", IntegerArgumentType.integer(1, 100))
                        .executes(ctx -> doHistory(ctx, IntegerArgumentType.getInteger(ctx, "limit")))));

        dispatcher.register(root);
    }

    private static Iterable<String> activeNames() {
        ChunkloaderRegistry r = CRFP.registry();
        if (r == null) return List.of();
        return r.all().stream().map(Chunkloader::name).toList();
    }

    private static ChunkloaderRegistry requireRegistry() throws CommandSyntaxException {
        ChunkloaderRegistry r = CRFP.registry();
        if (r == null) throw ERR_NO_REGISTRY.create();
        return r;
    }

    // ---------- add ----------

    private static int doAdd(CommandContext<CommandSourceStack> ctx, String reason) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayer();
        if (player == null) throw ERR_NOT_PLAYER.create();

        ChunkloaderRegistry reg = requireRegistry();
        int minutes = IntegerArgumentType.getInteger(ctx, "minutes");

        ChunkloaderRegistry.AddResult result = reg.add(minutes, reason, player);
        if (!result.success) {
            src.sendFailure(Component.literal(result.message));
            return 0;
        }
        Chunkloader c = result.chunkloader;
        src.sendSuccess(() -> Component.literal("Chunkloader '" + c.name() + "' active for " + minutes + "m").withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    // ---------- list ----------

    private static int doList(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ChunkloaderRegistry reg = requireRegistry();

        List<Chunkloader> sorted = reg.all().stream()
                .sorted(Comparator.comparing(Chunkloader::name))
                .toList();

        if (sorted.isEmpty()) {
            src.sendSuccess(() -> Component.literal("No active chunkloaders").withStyle(ChatFormatting.GRAY), false);
            return 0;
        }

        src.sendSuccess(() -> Component.literal("Active chunkloaders (" + sorted.size() + "):").withStyle(ChatFormatting.YELLOW), false);
        for (Chunkloader c : sorted) {
            BlockPos p = c.pos();
            String reason = c.reason().isEmpty() ? "" : " · " + c.reason();
            MutableComponent line = Component.literal("  • ")
                    .append(Component.literal(c.name()).withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(" · " + shortDim(c.dimension())
                            + " [" + p.getX() + " " + p.getY() + " " + p.getZ() + "]"
                            + " · by " + c.creatorName()
                            + " · " + formatDuration(c.remainingMs()) + " left"
                            + reason).withStyle(ChatFormatting.GRAY));
            src.sendSuccess(() -> line, false);
        }
        return sorted.size();
    }

    // ---------- remove ----------

    private static int doRemove(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ChunkloaderRegistry reg = requireRegistry();
        String name = StringArgumentType.getString(ctx, "name");

        if (!reg.remove(name, src.getTextName())) throw ERR_NOT_FOUND.create();
        src.sendSuccess(() -> Component.literal("Removed chunkloader '" + name + "'").withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    // ---------- extend ----------

    private static int doExtend(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ChunkloaderRegistry reg = requireRegistry();
        String name = StringArgumentType.getString(ctx, "name");
        int minutes = IntegerArgumentType.getInteger(ctx, "minutes");

        if (!reg.extend(name, minutes, src.getTextName())) throw ERR_NOT_FOUND.create();
        Chunkloader c = reg.get(name);
        long remain = c != null ? c.remainingMs() : 0;
        src.sendSuccess(() -> Component.literal("Extended '" + name + "' · now " + formatDuration(remain) + " left").withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    // ---------- info ----------

    private static int doInfo(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ChunkloaderRegistry reg = requireRegistry();
        String name = StringArgumentType.getString(ctx, "name");
        Chunkloader c = reg.get(name);
        if (c == null) throw ERR_NOT_FOUND.create();

        BlockPos p = c.pos();
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        src.sendSuccess(() -> Component.literal("Chunkloader: " + c.name()).withStyle(ChatFormatting.YELLOW), false);
        line(src, "Creator",   c.creatorName());
        line(src, "Dimension", c.dimension());
        line(src, "Position",  p.getX() + " " + p.getY() + " " + p.getZ());
        line(src, "Reason",    c.reason().isEmpty() ? "(none)" : c.reason());
        line(src, "Created",   fmt.format(new Date(c.createdAtEpochMs())));
        line(src, "Remaining", formatDuration(c.remainingMs()));
        return 1;
    }

    private static void line(CommandSourceStack src, String key, String value) {
        src.sendSuccess(() -> Component.literal("  " + key + ": ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(value).withStyle(ChatFormatting.WHITE)), false);
    }

    private static String shortDim(String dim) {
        int i = dim.indexOf(':');
        return i >= 0 ? dim.substring(i + 1) : dim;
    }

    // ---------- history ----------

    private static int doHistory(CommandContext<CommandSourceStack> ctx, int limit) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ChunkloaderRegistry reg = requireRegistry();
        java.util.List<String> lines = reg.history().tail(limit);
        if (lines.isEmpty()) {
            src.sendSuccess(() -> Component.literal("No history yet").withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        src.sendSuccess(() -> Component.literal("Last " + lines.size() + " event(s):").withStyle(ChatFormatting.YELLOW), false);
        for (String raw : lines) {
            try {
                com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(raw).getAsJsonObject();
                String ts = o.has("timestamp") ? o.get("timestamp").getAsString() : "?";
                String event = o.has("event") ? o.get("event").getAsString() : "?";
                String name = o.has("name") ? o.get("name").getAsString() : "?";
                String creator = o.has("creator") ? o.get("creator").getAsString() : "?";
                String reason = o.has("reason") ? " · " + o.get("reason").getAsString() : "";
                String executor = o.has("executor") ? " by " + o.get("executor").getAsString() : "";

                ChatFormatting eventColor = switch (event) {
                    case "create" -> ChatFormatting.GREEN;
                    case "extend" -> ChatFormatting.BLUE;
                    case "expire" -> ChatFormatting.GOLD;
                    case "remove" -> ChatFormatting.RED;
                    case "restore" -> ChatFormatting.DARK_AQUA;
                    default -> ChatFormatting.WHITE;
                };
                MutableComponent line = Component.literal("  " + shortTs(ts) + " ").withStyle(ChatFormatting.DARK_GRAY)
                        .append(Component.literal(event).withStyle(eventColor))
                        .append(Component.literal(" " + name + " ").withStyle(ChatFormatting.AQUA))
                        .append(Component.literal("by " + creator + executor + reason).withStyle(ChatFormatting.GRAY));
                src.sendSuccess(() -> line, false);
            } catch (Exception e) {
                src.sendSuccess(() -> Component.literal("  (unparseable) " + raw).withStyle(ChatFormatting.DARK_RED), false);
            }
        }
        return lines.size();
    }

    private static String shortTs(String iso) {
        // 2026-04-22T09:57:50.123Z -> 04-22 09:57:50
        if (iso.length() < 19) return iso;
        return iso.substring(5, 10) + " " + iso.substring(11, 19);
    }

    private static String formatDuration(long ms) {
        if (ms < 0) ms = 0;
        long totalSec = ms / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m " + s + "s";
        return s + "s";
    }
}
