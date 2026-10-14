package sgms.bridge;

import io.papermc.paper.advancement.AdvancementDisplay;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** События Minecraft, которые уходят в Telegram. */
public class McListener implements Listener {
    private final SGMSBridge plugin;
    private final PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();

    public McListener(SGMSBridge plugin) {
        this.plugin = plugin;
    }

    private String text(Component c) {
        return c == null ? "" : plain.serialize(c);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        if (!plugin.getConfig().getBoolean("events.chat", true)) return;
        Player p = e.getPlayer();
        String name = text(p.displayName());
        if (name.isEmpty()) name = p.getName();
        String msg = text(e.message());
        if (msg.isEmpty()) return;
        String fmt = plugin.getConfig().getString("telegram-format", "<b>[<username>]</b>: <text>");
        plugin.send(Fmt.fill(fmt, "username", Fmt.esc(name), "text", Fmt.esc(msg)));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        if (!plugin.getConfig().getBoolean("events.join", true)) return;
        Player p = e.getPlayer();
        if (plugin.isVanished(p)) return;
        String key = p.hasPlayedBefore() ? "player-joined" : "player-joined-first";
        plugin.send(Fmt.fill(plugin.msg(key), "username", Fmt.esc(p.getName())));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        if (!plugin.getConfig().getBoolean("events.leave", true)) return;
        Player p = e.getPlayer();
        if (plugin.isVanished(p)) return;
        plugin.send(Fmt.fill(plugin.msg("player-left"), "username", Fmt.esc(p.getName())));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        if (!plugin.getConfig().getBoolean("events.death", true)) return;
        String m = text(e.deathMessage());
        if (m.isEmpty()) return;
        plugin.send(Fmt.fill(plugin.msg("player-died"), "death_message", Fmt.esc(m), "username", Fmt.esc(e.getPlayer().getName())));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent e) {
        if (!plugin.getConfig().getBoolean("events.advancement", true)) return;
        AdvancementDisplay d = e.getAdvancement().getDisplay();
        if (d == null || !d.doesAnnounceToChat()) return;
        String frame = d.frame().name();
        String key = "advancement-regular";
        if (frame.equals("CHALLENGE")) key = "advancement-challenge";
        else if (frame.equals("GOAL")) key = "advancement-goal";
        plugin.send(Fmt.fill(plugin.msg(key),
                "username", Fmt.esc(e.getPlayer().getName()),
                "title", Fmt.esc(text(d.title())),
                "description", Fmt.esc(text(d.description()))));
    }
}
