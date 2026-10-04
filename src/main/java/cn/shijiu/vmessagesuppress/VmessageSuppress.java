package cn.shijiu.vmessagesuppress;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Vmessage 的子服配套插件：把「这条聊天被子服插件取消掉了」告诉代理。
 *
 * <p>为什么需要它：Velocity 上的跨服聊天插件，在收到玩家聊天的【同一时刻】就把消息发给了别的子服；
 * 而子服插件（商店输入数量、菜单输入、签到输入……）的 setCancelled(true) 发生在【之后】，
 * 且没有任何机制能回传给代理 —— 于是别的子服照样看到玩家输入的「64」「abc」。
 *
 * <p>做法：抢在所有人前面（LOWEST）记下玩家真正输入的原文，等所有人跑完（MONITOR）再看一眼
 * 事件有没有被取消；取消了就通过插件消息通道告诉代理「这条别转发」。
 *
 * <p>只有被取消时才发包，正常聊天零额外开销。
 */
public final class VmessageSuppress extends JavaPlugin implements Listener {

    /** 与代理端 Vmessage 约定好的通道。 */
    public static final String CHANNEL = "vmessage:suppress";

    /** 玩家 → 他这次聊天真正输入的原文（LOWEST 存，MONITOR 取走）。 */
    private final Map<UUID, String> original = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("[VmessageSuppress] 已启用：被取消的聊天会通知代理（通道 " + CHANNEL + "）");
    }

    /**
     * 最早的优先级：存下原文。
     * 万一中间有插件改写了 message，我们发给代理的仍是玩家真正输入的那串 ——
     * 代理端是拿玩家原始输入去比对的，对不上就抑制不掉。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChatBegin(final AsyncPlayerChatEvent e) {
        original.put(e.getPlayer().getUniqueId(), e.getMessage());
    }

    /** 最后执行：此刻 isCancelled() 已经是最终结果。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChatEnd(final AsyncPlayerChatEvent e) {
        final Player p = e.getPlayer();
        final String raw = original.remove(p.getUniqueId());
        if (!e.isCancelled()) {
            return;
        }
        send(p, raw != null ? raw : e.getMessage());
    }

    /** 直接发，不排到主线程 —— 代理那边有个几十毫秒的窗口在等，多一个 tick 就可能错过。 */
    private void send(final Player p, final String message) {
        try {
            final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            final DataOutputStream out = new DataOutputStream(buffer);
            out.writeUTF(p.getUniqueId().toString());
            out.writeUTF(message);
            out.flush();
            p.sendPluginMessage(this, CHANNEL, buffer.toByteArray());
            if (getConfig().getBoolean("debug", false)) {
                getLogger().info("[VmessageSuppress] 已通知代理："
                        + p.getName() + " 的「" + message + "」被子服取消了");
            }
        } catch (final Exception ex) {
            getLogger().warning("[VmessageSuppress] 抑制信号发送失败：" + ex);
        }
    }
}
