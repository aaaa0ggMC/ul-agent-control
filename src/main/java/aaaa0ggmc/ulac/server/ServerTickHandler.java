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

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

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

        // TAS 步进计数：暂停时本事件不触发，故只在放行窗口内累加（通过回调解耦客户端类）
        Runnable hook = ControlState.serverTickHook;
        if (hook != null) {
            hook.run();
        }

        if (pendingCommand != null && System.currentTimeMillis() >= pendingCommandDeadlineMs) {
            finishPendingCommand();
        }

        ControlState.QueuedAction action;
        while ((action = ControlState.SERVER_ACTIONS.poll()) != null) {
            if ("command".equals(action.type)) {
                startCommandAsync(action);
            } else {
                action.done(run(action));
            }
        }
    }

    private static ControlState.QueuedAction pendingCommand;
    private static int pendingCommandCode;
    private static long pendingCommandDeadlineMs;

    private static void startCommandAsync(ControlState.QueuedAction action) {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) {
            action.done(ControlState.QueuedAction.error("no server"));
            return;
        }
        EntityPlayerMP player = server.getPlayerList().getPlayers().isEmpty()
                ? null : server.getPlayerList().getPlayers().get(0);
        if (player == null) {
            action.done(ControlState.QueuedAction.error("no player"));
            return;
        }
        if (pendingCommand != null) {
            finishPendingCommand();
        }
        String line = action.params.has("line") ? action.params.get("line").getAsString() : "";
        ControlState.CAPTURE.clear();
        ControlState.capturing = true;
        int code;
        try {
            code = server.getCommandManager().executeCommand(player, line);
        } catch (Exception e) {
            ControlState.capturing = false;
            action.done(ControlState.QueuedAction.error("command threw: " + e));
            return;
        }
        long window = Math.round(Math.min(3000.0, Math.max(300.0, 2000.0 / Math.max(0.1, ControlState.tickTps))));
        pendingCommand = action;
        pendingCommandCode = code;
        pendingCommandDeadlineMs = System.currentTimeMillis() + window;
    }

    private static void finishPendingCommand() {
        ControlState.QueuedAction action = pendingCommand;
        pendingCommand = null;
        ControlState.capturing = false;
        if (action == null) {
            return;
        }
        JsonObject r = new JsonObject();
        r.addProperty("ok", true);
        r.addProperty("result", pendingCommandCode);
        JsonArray out = new JsonArray();
        for (String s : ControlState.CAPTURE) {
            out.add(s);
        }
        r.add("output", out);
        action.done(r);
    }

    private static final Map<String, BiFunction<MinecraftServer, ControlState.QueuedAction, JsonObject>> SERVER_ROUTES = buildServerRoutes();

    private static Map<String, BiFunction<MinecraftServer, ControlState.QueuedAction, JsonObject>> buildServerRoutes() {
        Map<String, BiFunction<MinecraftServer, ControlState.QueuedAction, JsonObject>> map = new HashMap<>();
        map.put("respawn", (server, action) -> {
            EntityPlayerMP player = server.getPlayerList().getPlayers().isEmpty()
                    ? null : server.getPlayerList().getPlayers().get(0);
            if (player == null) {
                return ControlState.QueuedAction.error("no player");
            }
            server.getPlayerList().recreatePlayerEntity(player, player.dimension, false);
            JsonObject r = new JsonObject();
            r.addProperty("ok", true);
            r.addProperty("respawned", true);
            return r;
        });
        map.put("invoke", (server, action) -> {
            EntityPlayerMP player = server.getPlayerList().getPlayers().isEmpty()
                    ? null : server.getPlayerList().getPlayers().get(0);
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
                JsonObject r = new JsonObject();
                r.addProperty("ok", true);
                r.addProperty("returned", String.valueOf(out));
                return r;
            } catch (Throwable t) {
                Throwable cause = t.getCause() != null ? t.getCause() : t;
                return ControlState.QueuedAction.error("invoke failed: " + cause);
            }
        });
        return Collections.unmodifiableMap(map);
    }

    private static JsonObject run(ControlState.QueuedAction action) {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) {
            return ControlState.QueuedAction.error("no server");
        }
        BiFunction<MinecraftServer, ControlState.QueuedAction, JsonObject> handler = SERVER_ROUTES.get(action.type);
        if (handler == null) {
            return ControlState.QueuedAction.error("unknown server action: " + action.type);
        }
        return handler.apply(server, action);
    }
}
