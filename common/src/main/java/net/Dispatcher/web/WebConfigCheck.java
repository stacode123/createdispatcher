package net.Dispatcher.web;

import net.Dispatcher.DispatcherMod;
import net.Dispatcher.config.DispatcherConfig;
import net.Dispatcher.web.auth.Allowlist;
import net.Dispatcher.web.auth.Secrets;
import net.Dispatcher.web.auth.Tier;

import java.util.ArrayList;
import java.util.List;

/**
 * Startup sanity pass over the web configuration. This feature is reachable from a browser
 * and the deployer tier can drive every train on the server, so a misconfiguration deserves
 * a loud, specific log line rather than silent acceptance. Nothing here refuses to start —
 * the operator's settings stand — but every risky combination is named, with the fix.
 *
 * <p>The same list backs {@code /dispatcher web status}, so warnings stay visible after the
 * startup log has scrolled away.
 */
public final class WebConfigCheck {

    /** @param fix what to change; empty when the note is purely informational */
    public record Note(boolean warning, String message, String fix) {}

    private WebConfigCheck() {}

    /** Evaluates the live config; safe to call at any time. */
    public static List<Note> check(Secrets secrets, Allowlist allowlist) {
        DispatcherConfig.Common config = DispatcherConfig.COMMON;
        List<Note> notes = new ArrayList<>();
        String bind = config.WebBindAddress.get().trim();
        String publicUrl = config.WebPublicUrl.get().trim();
        boolean loopback = bind.equals("127.0.0.1") || bind.equals("::1")
                || bind.equalsIgnoreCase("localhost");
        Tier defaultTier = Tier.parse(config.WebDefaultTier.get());

        if (!publicUrl.isBlank() && !publicUrl.startsWith("http://") && !publicUrl.startsWith("https://"))
            notes.add(new Note(true, "Web Public Url '" + publicUrl + "' has no scheme — Discord login will fail",
                    "write the full address, e.g. https://trains.example.com"));

        if (!loopback) {
            if (publicUrl.isBlank())
                notes.add(new Note(true, "the web server is bound to " + bind
                        + " (reachable from the network) but Web Public Url is empty",
                        "set Web Public Url to the address players reach the site at, so Discord login and the Origin check work"));
            else if (publicUrl.startsWith("http://"))
                notes.add(new Note(true, "the site is served over plain http on " + bind
                        + " — session cookies and Discord logins travel unencrypted",
                        "terminate TLS in a reverse proxy and set Web Public Url to the https address (see docs/web-interface.md)"));
        }

        if (defaultTier.atLeast(Tier.PLANNER))
            notes.add(new Note(true, "Web Default Tier is " + defaultTier.name().toLowerCase()
                    + " — ANY Discord account that logs in is auto-enrolled with "
                    + (defaultTier == Tier.DEPLOYER ? "full control over every train" : "planning rights"),
                    "set it to none (allowlist only) or viewer unless this server is private"));

        if (!secrets.discordConfigured()) {
            notes.add(new Note(false, "Discord login is not configured",
                    "add discordClientId/discordClientSecret to config/createdispatcher/secrets.json, or hand out one-time links with /dispatcher web session <tier>"));
        } else if (publicUrl.isBlank()) {
            notes.add(new Note(true, "Discord credentials are set but Web Public Url is empty — the OAuth redirect has no address",
                    "set Web Public Url to the site's public address and add <that>/auth/callback to the Discord app's redirects"));
        }

        if (allowlist.size() == 0 && defaultTier == Tier.NONE)
            notes.add(new Note(true, "the allowlist is empty and Web Default Tier is none — nobody can get in yet",
                    "run /dispatcher web allow <discordId> <tier>, or /dispatcher web session <tier> for a one-time link"));

        if (config.WebSimWallCapSeconds.get() > 0)
            notes.add(new Note(true, "Web Sim Wall Cap Seconds is "
                    + config.WebSimWallCapSeconds.get() + " — simulations may stop early and stop being reproducible",
                    "set it to 0 unless runaway sims are an actual problem here; truncated results are flagged in the UI"));

        if (config.WebHistoryHours.get() == 0)
            notes.add(new Note(false, "Web History Hours is 0 — no observed-movement history, so diagrams show the plan only",
                    ""));
        if (config.WebReplayKept.get() == 0)
            notes.add(new Note(false, "Web Replay Kept is 0 — notification replays are disabled", ""));
        if (!config.WebBackgroundProjections.get())
            notes.add(new Note(false, "Web Background Projections is off — plan overlays only refresh while a browser is watching, and drift calibration learns slowly",
                    ""));

        return notes;
    }

    /** Logs the notes once at startup; warnings as WARN, the rest as INFO. */
    public static void log(List<Note> notes) {
        for (Note note : notes) {
            String text = "Dispatcher web: " + note.message()
                    + (note.fix().isBlank() ? "" : " — " + note.fix());
            if (note.warning()) DispatcherMod.LOGGER.warn(text);
            else DispatcherMod.LOGGER.info(text);
        }
    }
}
