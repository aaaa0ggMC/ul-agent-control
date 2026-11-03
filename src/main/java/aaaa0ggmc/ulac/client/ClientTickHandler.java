package aaaa0ggmc.ulac.client;

import aaaa0ggmc.ulac.ControlState;
import aaaa0ggmc.ulac.UlAgentControl;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.util.EnumHand;
import net.minecraft.util.MovementInput;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.client.event.InputUpdateEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;

import java.io.File;
import java.lang.reflect.Field;

/**
 * 客户端 tick：虚拟输入合成、视角控制、Timer 调速、一次性动作执行、状态快照、聊天捕获。
 */
@Mod.EventBusSubscriber(value = Side.CLIENT, modid = UlAgentControl.MODID)
public class ClientTickHandler {

    private static Field timerField;
    private static Field tickLengthField;

    private static Field findField(Class<?> c, String... names) {
        for (String n : names) {
            try {
                Field f = c.getDeclaredField(n);
                f.setAccessible(true);
                return f;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static boolean focusFixApplied;

    static {
        // 开发环境是映射名，运行时是 SRG 名，两个都试
        timerField = findField(Minecraft.class, "timer", "field_71428_T");
        if (timerField != null) {
            tickLengthField = findField(timerField.getType(), "tickLength", "field_194149_e");
        }
        if (timerField == null || tickLengthField == null) {
            UlAgentControl.LOGGER.error("[ulac] timer reflection unavailable");
        }
    }

    /**
     * 关掉「失去焦点自动暂停」：无头/无人值守驱动时游戏必须一直跑，
     * 否则一失焦就弹暂停菜单、世界停摆，命令队列全部超时。
     */
    private static void applyFocusFix(Minecraft mc) {
        try {
            if (mc.gameSettings != null && mc.gameSettings.pauseOnLostFocus) {
                mc.gameSettings.pauseOnLostFocus = false;
                mc.gameSettings.saveOptions();
                UlAgentControl.LOGGER.info("[ulac] pauseOnLostFocus disabled (unfocused game keeps running)");
            }
        } catch (Throwable t) {
            UlAgentControl.LOGGER.warn("[ulac] focus fix failed: {}", t.toString());
        }
    }

    private ClientTickHandler() {
    }

    /** 虚拟输入：覆盖本 tick 的 movementInput（正常玩家管道的同一入口）。 */
    @SubscribeEvent
    public static void onInputUpdate(InputUpdateEvent event) {
        MovementInput input = event.getMovementInput();
        input.moveForward = ControlState.moveForward;
        input.moveStrafe = ControlState.moveStrafe;
        input.jump = ControlState.jump;
        input.sneak = ControlState.sneak;
        if (ControlState.sprint) {
            net.minecraft.entity.player.EntityPlayer player = event.getEntityPlayer();
            if (player != null) {
                player.setSprinting(true);
            }
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();

        // 失焦暂停修复（一次性）
        if (!focusFixApplied) {
            focusFixApplied = true;
            applyFocusFix(mc);
        }

        // 聊天缓冲（命令输出/事件文案都从这过）
        // （聊天事件在 onChatReceived 里写入 ControlState）

        ControlState.QueuedAction action;
        while ((action = ControlState.CLIENT_ACTIONS.poll()) != null) {
            action.done(run(mc, action));
        }

        // 逐 tick 录制 + TAS 步进状态机（世界在即可跑，不等 player）
        TickRecorder.onClientTick(mc);
        TasStep.onClientTick(mc);

        if (mc.player == null) {
            return;
        }
        if (ControlState.lookActive) {
            mc.player.rotationYaw = ControlState.lookYaw;
            float p = ControlState.lookPitch;
            if (p > 90.0F) p = 90.0F;
            if (p < -90.0F) p = -90.0F;
            mc.player.rotationPitch = p;
        }
        applyTimer();
    }

    /** 客户端 Timer 调速：tickLength(ms/tick) = 1000 / tps。 */
    private static void applyTimer() {
        if (timerField == null || tickLengthField == null) {
            return;
        }
        try {
            Object timer = timerField.get(Minecraft.getMinecraft());
            if (timer != null) {
                tickLengthField.setFloat(timer, (float) (1000.0 / Math.max(0.1, ControlState.tickTps)));
            }
        } catch (Throwable ignored) {
        }
    }

    private static JsonObject run(Minecraft mc, ControlState.QueuedAction action) {
        switch (action.type) {
            case "state":
                return SnapshotBuilder.build(mc);
            case "attack": {
                if (mc.player == null) break;
                if (mc.objectMouseOver != null && mc.objectMouseOver.entityHit != null) {
                    mc.playerController.attackEntity(mc.player, mc.objectMouseOver.entityHit);
                }
                mc.player.swingArm(EnumHand.MAIN_HAND);
                JsonObject r = new JsonObject();
                r.addProperty("ok", true);
                r.addProperty("swung", true);
                return r;
            }
            case "use": {
                if (mc.player == null) break;
                mc.playerController.processRightClick(mc.player, mc.world, EnumHand.MAIN_HAND);
                JsonObject r = new JsonObject();
                r.addProperty("ok", true);
                r.addProperty("used", true);
                return r;
            }
            case "hotbar": {
                if (mc.player == null) break;
                int slot = action.params.has("slot") ? action.params.get("slot").getAsInt() : 0;
                slot = Math.max(0, Math.min(8, slot));
                mc.player.inventory.currentItem = slot;
                JsonObject r = new JsonObject();
                r.addProperty("ok", true);
                r.addProperty("slot", slot);
                return r;
            }
            case "chat": {
                if (mc.player == null) break;
                String text = action.params.has("text") ? action.params.get("text").getAsString() : "";
                mc.player.sendChatMessage(text);
                JsonObject r = new JsonObject();
                r.addProperty("ok", true);
                r.addProperty("sent", text);
                return r;
            }
            case "join": {
                // 主菜单直接进世界（等价于世界列表点 Play），免 GUI 操作
                if (mc.player != null) {
                    return ControlState.QueuedAction.error("already in game");
                }
                String want = action.params.has("name") ? action.params.get("name").getAsString() : null;
                java.util.List<net.minecraft.world.storage.WorldSummary> saves;
                try {
                    saves = mc.getSaveLoader().getSaveList();
                } catch (Throwable t) {
                    return ControlState.QueuedAction.error("cannot list saves: " + t);
                }
                net.minecraft.world.storage.WorldSummary target = null;
                StringBuilder names = new StringBuilder();
                for (net.minecraft.world.storage.WorldSummary s : saves) {
                    if (names.length() > 0) names.append(", ");
                    names.append(s.getFileName());
                    if (want == null || want.equals(s.getFileName()) || want.equals(s.getDisplayName())) {
                        target = s;
                    }
                }
                if (target == null) {
                    return ControlState.QueuedAction.error("world not found: " + want + " | available: " + names);
                }
                net.minecraftforge.fml.client.FMLClientHandler.instance().tryLoadExistingWorld(null, target);
                JsonObject r = new JsonObject();
                r.addProperty("ok", true);
                r.addProperty("joining", target.getFileName());
                return r;
            }
            case "screenshot": {
                String name = action.params.has("name") ? action.params.get("name").getAsString()
                        : "ulac-" + System.currentTimeMillis();
                if (!name.toLowerCase().endsWith(".png")) {
                    name = name + ".png";
                }
                JsonObject r = new JsonObject();
                try {
                    ScreenShotHelper.saveScreenshot(
                            Minecraft.getMinecraft().gameDir, name,
                            mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
                    ControlState.lastScreenshot = name;
                    r.addProperty("ok", true);
                    r.addProperty("file", "screenshots/" + name);
                } catch (Throwable t) {
                    return ControlState.QueuedAction.error("screenshot failed: " + t);
                }
                return r;
            }
            case "record": {
                // 逐 tick 帧录制：{"op":"start|stop","every":1}
                String op = action.params.has("op") ? action.params.get("op").getAsString() : "start";
                if (op.equals("stop")) {
                    return TickRecorder.stop();
                }
                int every = action.params.has("every") ? action.params.get("every").getAsInt() : 1;
                return TickRecorder.start(mc.gameDir, every);
            }
            case "step": {
                // TAS 步进：{"op":"begin|step|end|status","ticks":N}
                String op = action.params.has("op") ? action.params.get("op").getAsString() : "status";
                switch (op) {
                    case "begin":
                        return TasStep.begin(mc);
                    case "end":
                        return TasStep.end(mc);
                    case "status":
                        return TasStep.status();
                    default:
                        break;
                }
                int ticks = action.params.has("ticks") ? action.params.get("ticks").getAsInt() : 1;
                return TasStep.step(mc, ticks);
            }
            default:
                break;
        }
        return ControlState.QueuedAction.error("unknown client action: " + action.type);
    }

    /** 聊天捕获。 */
    @SubscribeEvent
    public static void onChatReceived(net.minecraftforge.client.event.ClientChatReceivedEvent event) {
        try {
            ControlState.addChat(event.getMessage().getUnformattedText());
        } catch (Throwable ignored) {
        }
    }
}
