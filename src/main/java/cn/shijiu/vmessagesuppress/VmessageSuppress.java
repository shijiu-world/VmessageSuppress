package cn.shijiu.vmessagesuppress;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.EnumSet;
import java.util.Iterator;
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
 * <h3>为什么要同时监听两条聊天管线</h3>
 * <p>Paper 1.19+ 里聊天有两套事件：新的 {@code AsyncChatEvent}（Component）和为了兼容
 * 老插件合成出来的 {@code AsyncPlayerChatEvent}。两套都会 fire，而且<b>取消不一定同步</b>：
 * Skript / 新插件大多取消的是新事件，而新事件往往在旧事件跑完之后才轮到 ——
 * 只监听旧事件时 {@code isCancelled()} 恒为 false，信号永远发不出去（"别的服照样看得到"）。
 * 保险起见两条都挂：谁发现被取消，谁负责通知；同一条聊天只通知一次。
 *
 * <p>「只通知一次」靠 {@link State#signalled} 去重，而它能不能生效取决于另一件事：
 * 两条管线各会跑一次 {@link #begin}，<b>第二条绝不能另起一份记录</b> —— 那会把第一条刚打上的
 * 标记冲掉，同一次取消就被通知两遍（日志刷两行、代理也白收一个包）。
 * 所以 {@link #begin} 按管线判重：这条管线在本次聊天里没跑过 = 新的一次聊天，才换记录。
 */
public final class VmessageSuppress extends JavaPlugin {

    /** 与代理端 Vmessage 约定好的通道。 */
    public static final String CHANNEL = "vmessage:suppress";

    /** 没被取消的记录最多留多久（毫秒）—— 只是怕异常路径攒垃圾，正常聊天不影响。 */
    private static final long STATE_TTL_MILLIS = 30_000L;

    /** 玩家 → 这次聊天的原文与「是否已通知过」。 */
    private final Map<UUID, State> states = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
        getServer().getPluginManager().registerEvents(new LegacyListener(), this);
        getServer().getPluginManager().registerEvents(new QuitListener(), this);
        // 有没有新版聊天事件，决定了兼容模式。Absent = 纯 Spigot/CraftBukkit，只听旧的。
        boolean modern = false;
        try {
            Class.forName("io.papermc.paper.event.player.AsyncChatEvent");
            getServer().getPluginManager().registerEvents(new ModernListener(), this);
            modern = true;
        } catch (final Throwable ignored) {
            // 非 Paper：只有旧事件可用，上面那个监听器已经够了
        }
        // 日志前缀不用手写：Bukkit 的 PluginLogger 会自动加 [VmessageSuppress]，再写一遍就重了。
        getLogger().info("已启用：被取消的聊天会通知代理（通道 " + CHANNEL + "）");
        getLogger().info("聊天事件："
                + (modern ? "新版 AsyncChatEvent + 旧版 AsyncPlayerChatEvent 两条都听"
                          : "只有旧版 AsyncPlayerChatEvent（非 Paper 服务端）"));
    }

    /** 最早的优先级：存下原文。万一中途有别插件改写过，我们发给代理的仍是玩家真正输入的那串。 */
    void begin(final Player p, final String text, final Pipe pipe) {
        purge();
        final State s = states.get(p.getUniqueId());
        if (s != null && s.started.add(pipe)) {
            // 同一次聊天的第二条管线：沿用已有记录，保住「已通知过」这个标记，只把原文兜个底
            s.text = text;
            debug("记录原文（" + pipe + "，沿用本次聊天的记录）：" + p.getName() + " → " + text);
            return;
        }
        states.put(p.getUniqueId(), new State(text, pipe));
        debug("记录原文（" + pipe + "）：" + p.getName() + " → " + text);
    }

    /**
     * 最后执行：此刻 isCancelled() 已是这条管线的最终结果。
     *
     * <p>⚠️ 没被取消时【不要】删掉记录：旧事件可能在新事件之前跑完，那时还没人来得及取消。
     * 留着，等另一条管线跑完之后发现「被取消了」时还能拿到原文。
     */
    void end(final Player p, final boolean cancelled, final String fallback, final Pipe pipe) {
        if (!cancelled) {
            debug(pipe + " 结束时未被取消：" + p.getName());
            return;
        }
        final State s = states.get(p.getUniqueId());
        if (s != null) {
            if (s.signalled) {
                debug(pipe + " 结束时已取消，但同一条已经通知过代理了，跳过");
                return;
            }
            s.signalled = true;
        }
        send(p, s != null ? s.text : fallback, pipe);
    }

    /** 直接发，不排到主线程 —— 代理那边有个几十毫秒的窗口在等，多一个 tick 就可能错过。 */
    private void send(final Player p, final String message, final Pipe pipe) {
        try {
            final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            final DataOutputStream out = new DataOutputStream(buffer);
            out.writeUTF(p.getUniqueId().toString());
            out.writeUTF(message);
            out.flush();
            p.sendPluginMessage(this, CHANNEL, buffer.toByteArray());
            getLogger().info("已通知代理（" + pipe + "）："
                    + p.getName() + " 的「" + message + "」被子服取消了");
        } catch (final Exception ex) {
            getLogger().warning("抑制信号发送失败：" + ex);
        }
    }

    private void debug(final String message) {
        if (getConfig().getBoolean("debug", false)) {
            getLogger().info(message);
        }
    }

    /** 顺手清掉过期记录，免得异常路径下一直堆着。 */
    private void purge() {
        if (states.size() < 64) {
            return;
        }
        final long now = System.currentTimeMillis();
        for (final Iterator<Map.Entry<UUID, State>> it = states.entrySet().iterator(); it.hasNext(); ) {
            if (now - it.next().getValue().created > STATE_TTL_MILLIS) {
                it.remove();
            }
        }
    }

    /**
     * Component → 纯文本。
     *
     * <p>代理端是拿玩家输入的原始字符串去比对的，一个多余的 §/标签都会让两边对不上号，
     * 所以这里只取文字部分。传 Object + 内部强转是为了让缺 adventure 的运行时不至于直接蹦。
     */
    private static String plain(final Object component) {
        try {
            return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                    .serialize((net.kyori.adventure.text.Component) component);
        } catch (final Throwable t) {
            return String.valueOf(component);
        }
    }

    /** 两条聊天管线，日志里要显示来源，所以各带一个中文名。 */
    private enum Pipe {
        LEGACY("旧事件"),
        MODERN("新事件");

        private final String label;

        Pipe(final String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** 一次聊天的过程状态。 */
    private static final class State {
        private String text;
        private final long created = System.currentTimeMillis();
        /** 本次聊天里已跑过 begin 的管线 —— 用来区分「同一次聊天的第二条管线」和「下一条聊天」。 */
        private final EnumSet<Pipe> started = EnumSet.noneOf(Pipe.class);
        private volatile boolean signalled;

        private State(final String text, final Pipe pipe) {
            this.text = text;
            this.started.add(pipe);
        }
    }

    /** 旧管线：绝大多数老插件（CMI、各种商店）取消的也是它。 */
    private final class LegacyListener implements Listener {
        @EventHandler(priority = EventPriority.LOWEST)
        public void onBegin(final AsyncPlayerChatEvent e) {
            begin(e.getPlayer(), e.getMessage(), Pipe.LEGACY);
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onEnd(final AsyncPlayerChatEvent e) {
            end(e.getPlayer(), e.isCancelled(), e.getMessage(), Pipe.LEGACY);
        }
    }

    /** 玩家下线就把他的记录拿掉 —— 不然聊过天的人会在这张表里越攒越多。 */
    private final class QuitListener implements Listener {
        @EventHandler
        public void onQuit(final org.bukkit.event.player.PlayerQuitEvent e) {
            states.remove(e.getPlayer().getUniqueId());
        }
    }

    /** 新管线：Skript / 现代插件取消的是它，且常常在旧事件跑完之后才轮到。 */
    private final class ModernListener implements Listener {
        @EventHandler(priority = EventPriority.LOWEST)
        public void onBegin(final io.papermc.paper.event.player.AsyncChatEvent e) {
            begin(e.getPlayer(), plain(e.message()), Pipe.MODERN);
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onEnd(final io.papermc.paper.event.player.AsyncChatEvent e) {
            end(e.getPlayer(), e.isCancelled(), plain(e.message()), Pipe.MODERN);
        }
    }
}
