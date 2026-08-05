package net.Dispatcher.web;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.Dispatcher.config.DispatcherConfig;
import net.Dispatcher.web.auth.Allowlist;
import net.Dispatcher.web.auth.Tier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

import java.io.IOException;

/** {@code /dispatcher web ...} admin commands (op level 3). */
public final class WebCommands {
    private WebCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("dispatcher")
                .then(Commands.literal("web")
                        .requires(source -> source.hasPermission(3))
                        .then(Commands.literal("status").executes(WebCommands::status))
                        .then(Commands.literal("refresh").executes(WebCommands::refresh))
                        .then(Commands.literal("reload").executes(WebCommands::reload))
                        .then(Commands.literal("list").executes(WebCommands::list))
                        .then(Commands.literal("allow")
                                .then(Commands.argument("discordId", StringArgumentType.word())
                                        .then(Commands.argument("tier", StringArgumentType.word())
                                                .executes(WebCommands::allow))))
                        .then(Commands.literal("deny")
                                .then(Commands.argument("discordId", StringArgumentType.word())
                                        .executes(WebCommands::deny)))
                        .then(Commands.literal("session")
                                .then(Commands.argument("tier", StringArgumentType.word())
                                        .executes(WebCommands::session)))));
    }

    /** 'Web Default Tier' — NONE when first-login auto-enrolment is off. */
    private static Tier defaultTier() {
        return Tier.parse(DispatcherConfig.COMMON.WebDefaultTier.get());
    }

    private static WebServer running(CommandContext<CommandSourceStack> context) {
        WebServer server = WebBootstrap.server();
        if (server == null) {
            context.getSource().sendFailure(Component.literal(
                    "Dispatcher web interface is not running (enable 'Web Enabled' in the createdispatcher common config and restart, or check the log)"));
        }
        return server;
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        WebServer server = WebBootstrap.server();
        if (server == null) {
            context.getSource().sendSuccess(() -> Component.literal("Dispatcher web: not running"), false);
            return 1;
        }
        String discord = server.auth().secrets().discordConfigured() ? "configured" : "not configured";
        String publicUrl = server.auth().publicUrl().isBlank() ? "(not set)" : server.auth().publicUrl();
        Tier defaultTier = defaultTier();
        String newUsers = defaultTier == Tier.NONE
                ? "allowlist only" : "auto-enrolled as " + defaultTier.name().toLowerCase();
        context.getSource().sendSuccess(() -> Component.literal(
                "Dispatcher web: listening on " + server.bindDescription()
                        + " | public url " + publicUrl
                        + " | Discord login " + discord
                        + " | " + server.auth().allowlist().size() + " allowlisted user(s)"
                        + " | new users " + newUsers), false);
        // The startup warnings scroll away; this is where an operator comes looking for them.
        for (WebConfigCheck.Note note : WebConfigCheck.check(server.auth().secrets(),
                server.auth().allowlist())) {
            String text = (note.warning() ? "⚠ " : "· ") + note.message()
                    + (note.fix().isBlank() ? "" : "\n    → " + note.fix());
            context.getSource().sendSuccess(() -> Component.literal(text)
                    .withStyle(style -> note.warning()
                            ? style.withColor(net.minecraft.ChatFormatting.GOLD)
                            : style.withColor(net.minecraft.ChatFormatting.GRAY)), false);
        }
        return 1;
    }

    private static int refresh(CommandContext<CommandSourceStack> context) {
        WebServer server = running(context);
        if (server == null) return 0;
        server.store().forceRefresh(null);
        context.getSource().sendSuccess(() -> Component.literal(
                "Dispatcher web: graph snapshots marked stale — rebuilding on the next poll (a few seconds)"), false);
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> context) {
        WebServer server = running(context);
        if (server == null) return 0;
        try {
            server.auth().allowlist().load();
            context.getSource().sendSuccess(() -> Component.literal(
                    "Dispatcher web: allowlist reloaded (" + server.auth().allowlist().size() + " user(s))"), false);
            return 1;
        } catch (IOException e) {
            context.getSource().sendFailure(Component.literal("Reload failed: " + e.getMessage()));
            return 0;
        }
    }

    private static int list(CommandContext<CommandSourceStack> context) {
        WebServer server = running(context);
        if (server == null) return 0;
        var entries = server.auth().allowlist().entries();
        if (entries.isEmpty()) {
            context.getSource().sendSuccess(() -> Component.literal(
                    "Allowlist is empty. Add users with /dispatcher web allow <discordId> <tier>"), false);
            return 1;
        }
        StringBuilder text = new StringBuilder("Allowlist (" + entries.size() + "):");
        for (Allowlist.Entry entry : entries) {
            text.append("\n  ").append(entry.discordId()).append(" — ").append(entry.tier().name().toLowerCase());
            if (!entry.note().isBlank()) text.append(" (").append(entry.note()).append(')');
        }
        String message = text.toString();
        context.getSource().sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private static int allow(CommandContext<CommandSourceStack> context) {
        WebServer server = running(context);
        if (server == null) return 0;
        String discordId = StringArgumentType.getString(context, "discordId");
        String tierName = StringArgumentType.getString(context, "tier");
        Tier tier = Tier.parse(tierName);
        if (!discordId.matches("[0-9]{5,25}")) {
            context.getSource().sendFailure(Component.literal(
                    "That does not look like a Discord user ID (it is a long number; enable Developer Mode in Discord and right-click the user)"));
            return 0;
        }
        // "none" is a real setting, not a typo: it is an entry, so first-login auto-enrolment
        // skips it — the way to keep somebody out on a server that enrols new users.
        if (tier == Tier.NONE && !tierName.equalsIgnoreCase("none")) {
            context.getSource().sendFailure(Component.literal(
                    "Unknown tier — use viewer, planner, deployer, or none to block"));
            return 0;
        }
        try {
            server.auth().allowlist().put(discordId, tier, "");
            String outcome = tier == Tier.NONE
                    ? "Blocked " + discordId + " (tier none — auto-enrolment will not touch them)"
                    : "Allowed " + discordId + " as " + tier.name().toLowerCase();
            context.getSource().sendSuccess(() -> Component.literal(outcome), true);
            return 1;
        } catch (IOException e) {
            context.getSource().sendFailure(Component.literal("Failed to save allowlist: " + e.getMessage()));
            return 0;
        }
    }

    private static int deny(CommandContext<CommandSourceStack> context) {
        WebServer server = running(context);
        if (server == null) return 0;
        String discordId = StringArgumentType.getString(context, "discordId");
        try {
            if (server.auth().allowlist().remove(discordId)) {
                // Removal forgets them, so auto-enrolment would take them back at the next login.
                String hint = defaultTier() == Tier.NONE ? ""
                        : " — but new users are auto-enrolled as " + defaultTier().name().toLowerCase()
                                + ", so they return on their next login; use /dispatcher web allow "
                                + discordId + " none to block them for good";
                context.getSource().sendSuccess(() -> Component.literal(
                        "Removed " + discordId + " from the allowlist" + hint), true);
                return 1;
            }
            context.getSource().sendFailure(Component.literal(discordId + " is not on the allowlist"));
            return 0;
        } catch (IOException e) {
            context.getSource().sendFailure(Component.literal("Failed to save allowlist: " + e.getMessage()));
            return 0;
        }
    }

    private static int session(CommandContext<CommandSourceStack> context) {
        WebServer server = running(context);
        if (server == null) return 0;
        Tier tier = Tier.parse(StringArgumentType.getString(context, "tier"));
        if (tier == Tier.NONE) {
            context.getSource().sendFailure(Component.literal("Unknown tier — use viewer, planner, or deployer"));
            return 0;
        }
        String url = server.baseUrl() + "/auth/token/" + server.auth().mintLoginToken(tier);
        Component link = Component.literal(url).withStyle(style -> style
                .withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, url)));
        context.getSource().sendSuccess(() -> Component.literal(
                        "One-time " + tier.name().toLowerCase() + " login (valid 5 min, single use — click to copy):\n")
                .append(link), false);
        return 1;
    }
}
