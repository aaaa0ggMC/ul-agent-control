package aaaa0ggmc.ulac.server;

import aaaa0ggmc.ulac.ControlState;
import aaaa0ggmc.ulac.UlAgentControl;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.List;

/**
 * 服务端 tick：①tick 节流（把 tick 间隔拉长到 1000/tps，实现慢动作）；
 * ②消费服务端动作队列（命令执行 + 输出捕获）。
 */
@Mod.EventBusSubscriber(modid = UlAgentControl.MODID)
public class ServerTickHandler {

    private static long lastTickNanos = System.nanoTime();

    private ServerTickHandler() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }

        // tick 节流
        double tps = ControlState.tickTps;
        long period = (long) (1_000_000_000.0 / Math.max(0.1, tps));
        long now = System.nanoTime();
        long spent = now - lastTickNanos;
        if (spent < period) {
            long rest = period - spent;
            try {
                Thread.sleep(rest / 1_000_000L, (int) (rest % 1_000_000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        now = System.nanoTime();
        ControlState.lastTickMs = (now - lastTickNanos) / 1_000_000.0;
        ControlState.achievedTps = 1000.0 / Math.max(1.0, ControlState.lastTickMs);
        lastTickNanos = now;

        // TAS 步进计数：暂停时本事件不触发，故只在放行窗口内累加（语义即「服务端 tick 数」）
        aaaa0ggmc.ulac.client.TasStep.onServerTick();

        ControlState.QueuedAction action;
        while ((action = ControlState.SERVER_ACTIONS.poll()) != null) {
            action.done(run(action));
        }
    }

    private static JsonObject run(ControlState.QueuedAction action) {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        JsonObject r = new JsonObject();
        if (server == null) {
            return ControlState.QueuedAction.error("no server");
        }
        EntityPlayerMP player = server.getPlayerList().getPlayers().isEmpty()
                ? null : server.getPlayerList().getPlayers().get(0);
        if ("command".equals(action.type)) {
            String line = action.params.has("line") ? action.params.get("line").getAsString() : "";
            if (player == null) {
                return ControlState.QueuedAction.error("no player");
            }
            // 以玩家本人身份执行（权限/目标选择器/getCommandSenderAsPlayer 语义一致）；
            // 输出以聊天形式回到客户端，用专用捕获缓冲收集（不受环形裁剪影响）。
            ControlState.CAPTURE.clear();
            ControlState.capturing = true;
            int code;
            try {
                code = server.getCommandManager().executeCommand(player, line);
            } catch (Exception e) {
                ControlState.capturing = false;
                return ControlState.QueuedAction.error("command threw: " + e);
            }
            long window = Math.round(Math.min(3000.0, Math.max(300.0, 2000.0 / Math.max(0.1, ControlState.tickTps))));
            try {
                Thread.sleep(window);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ControlState.capturing = false;
            r.addProperty("ok", true);
            r.addProperty("result", code);
            JsonArray out = new JsonArray();
            for (String s : ControlState.CAPTURE) {
                out.add(s);
            }
            r.add("output", out);
            return r;
        }
        if ("respawn".equals(action.type)) {
            if (player == null) {
                return ControlState.QueuedAction.error("no player");
            }
            server.getPlayerList().recreatePlayerEntity(player, player.dimension, false);
            r.addProperty("ok", true);
            r.addProperty("respawned", true);
            return r;
        }
        if ("invoke".equals(action.type)) {
            // 服务端语义的反射调用：在服务端 tick 内执行（只有放行 tick 才跑到这里，
            // 实体位置/移动增量等状态都是活的——冻结期的 futureTaskQueue 里跑会看到静止世界）。
            String cls = action.params.has("class") ? action.params.get("class").getAsString() : "";
            String mth = action.params.has("method") ? action.params.get("method").getAsString() : "";
            boolean withPlayer = action.params.has("arg")
                    && "player".equals(action.params.get("arg").getAsString());
            try {
                Class<?> c = Class.forName(cls);
                java.lang.reflect.Method m = withPlayer
                        ? c.getDeclaredMethod(mth, EntityPlayerMP.class)
                        : c.getDeclaredMethod(mth);
                m.setAccessible(true);
                Object out = withPlayer ? m.invoke(null, player) : m.invoke(null);
                r.addProperty("ok", true);
                r.addProperty("returned", String.valueOf(out));
                return r;
            } catch (Throwable t) {
                Throwable cause = t.getCause() != null ? t.getCause() : t;
                return ControlState.QueuedAction.error("invoke failed: " + cause);
            }
        }
        return ControlState.QueuedAction.error("unknown server action: " + action.type);
    }
}
