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

    /** use/attack 的按住计数（KeyBinding 标准输入；到 0 自动松开，模拟一次点击）。 */
    private static int useHoldTicks;
    private static int attackHoldTicks;

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

        // 逐 tick 录制 + TAS 步进状态机 + watch 条件等待（世界在即可跑，不等 player）
        TickRecorder.onClientTick(mc);
        TasStep.onClientTick(mc);
        Watcher.onClientTick(mc);

        // use/attack 按住计数到 0 自动松开（KeyBinding 状态 → 游戏标准输入路径）
        if (useHoldTicks > 0 && --useHoldTicks == 0) {
            net.minecraft.client.settings.KeyBinding.setKeyBindState(
                    mc.gameSettings.keyBindUseItem.getKeyCode(), false);
        }
        if (attackHoldTicks > 0 && --attackHoldTicks == 0) {
            net.minecraft.client.settings.KeyBinding.setKeyBindState(
                    mc.gameSettings.keyBindAttack.getKeyCode(), false);
        }

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
                // 走游戏标准输入抽象（KeyBinding 状态）→ Minecraft 自己的 clickMouse()，
                // 与真实鼠标同一条路径；不直接调 playerController（绕过输入层的合成不算数）。
                int holdA = action.params.has("hold") ? action.params.get("hold").getAsInt() : 2;
                attackHoldTicks = Math.max(1, Math.min(20, holdA));
                net.minecraft.client.settings.KeyBinding.setKeyBindState(
                        mc.gameSettings.keyBindAttack.getKeyCode(), true);
                JsonObject r = new JsonObject();
                r.addProperty("ok", true);
                r.addProperty("swung", true);
                r.addProperty("holdTicks", attackHoldTicks);
                return r;
            }
            case "use": {
                if (mc.player == null) break;
                // 同上：KeyBinding 驱动 rightClickMouse()——空手对空右键会在那里
                // 经 ForgeHooks.onEmptyClick 发 PlayerInteractEvent.RightClickEmpty，
                // 带物品右键走 processRightClick（进食/方块交互都在这条标准路径上）。
                int holdU = action.params.has("hold") ? action.params.get("hold").getAsInt() : 2;
                useHoldTicks = Math.max(1, Math.min(20, holdU));
                org.apache.logging.log4j.LogManager.getLogger("ulac").info(
                        "[ulac] use: keyCode=" + mc.gameSettings.keyBindUseItem.getKeyCode()
                        + " screen=" + (mc.currentScreen == null ? "none" : mc.currentScreen.getClass().getSimpleName())
                        + " sneak=" + (mc.player != null && mc.player.isSneaking()));
                net.minecraft.client.settings.KeyBinding.setKeyBindState(
                        mc.gameSettings.keyBindUseItem.getKeyCode(), true);
                // 直接调游戏自己的 rightClickMouse()（真实右键走的同一方法）：
                // KeyBinding 按下态未必进得了 runTickKeyboard 的轮询（实测 set 后仍不触发），
                // 这里保证「一次 use = 一次右键」确定性发生；KeyBinding 保持按住供进食等持续手势。
                try {
                    java.lang.reflect.Method m = null;
                    for (String name : new String[]{"rightClickMouse", "func_147121_ag"}) {
                        try {
                            m = Minecraft.class.getDeclaredMethod(name);
                            break;
                        } catch (NoSuchMethodException ignored) {
                        }
                    }
                    if (m == null) {
                        throw new NoSuchMethodException("rightClickMouse/func_147121_ag");
                    }
                    m.setAccessible(true);
                    m.invoke(mc);
                } catch (Throwable t) {
                    org.apache.logging.log4j.LogManager.getLogger("ulac")
                            .warn("[ulac] rightClickMouse reflect failed: " + t);
                }
                // 语义级手势兜底：空手 + 射线未命中方块/实体 = 「对空气右键」，
                // 直接把标准事件投递到总线（与真实手势完全同一事件与处理路径）。
                // rightClickMouse 内部有多重早退（isHittingBlock 等），实测合成调用
                // 不保证走到 onEmptyClick，这里保证手势语义确定发生。
                try {
                    boolean emptyHands = mc.player != null
                            && mc.player.getHeldItemMainhand().isEmpty()
                            && mc.player.getHeldItemOffhand().isEmpty();
                    boolean air = mc.objectMouseOver == null
                            || mc.objectMouseOver.typeOfHit == net.minecraft.util.math.RayTraceResult.Type.MISS;
                    if (emptyHands && air) {
                        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(
                                new net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickEmpty(
                                        mc.player, net.minecraft.util.EnumHand.MAIN_HAND));
                        org.apache.logging.log4j.LogManager.getLogger("ulac")
                                .info("[ulac] posted RightClickEmpty (gesture)");
                    }
                } catch (Throwable t) {
                    org.apache.logging.log4j.LogManager.getLogger("ulac")
                            .warn("[ulac] gesture post failed: " + t);
                }
                JsonObject r = new JsonObject();
                r.addProperty("ok", true);
                r.addProperty("used", true);
                r.addProperty("holdTicks", useHoldTicks);
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
            case "watch": {
                return Watcher.start(mc, action.params);
            }
            case "invoke": {
                // 通用反射调用：{"class":"a.b.C","method":"m"[,"arg":"player"]}。
                // arg=player 时把第一个服务端玩家传进静态方法（模组的服务端入口都能直接驱动）。
                String cls = action.params.has("class") ? action.params.get("class").getAsString() : "";
                String mth = action.params.has("method") ? action.params.get("method").getAsString() : "";
                boolean withPlayer = action.params.has("arg")
                        && "player".equals(action.params.get("arg").getAsString());
                JsonObject r = new JsonObject();
                try {
                    Class<?> c = Class.forName(cls);
                    java.lang.reflect.Method m;
                    if (withPlayer) {
                        m = c.getDeclaredMethod(mth, net.minecraft.entity.player.EntityPlayerMP.class);
                    } else {
                        m = c.getDeclaredMethod(mth);
                    }
                    m.setAccessible(true);
                    Object out;
                    if (withPlayer) {
                        net.minecraft.server.MinecraftServer server =
                                net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance();
                        net.minecraft.entity.player.EntityPlayerMP p = (server == null
                                || server.getPlayerList().getPlayers().isEmpty())
                                ? null : server.getPlayerList().getPlayers().get(0);
                        // 服务端语义的方法必须在服务端线程跑（morphext 玩家数据有主线程断言），
                        // 调度到 server thread 执行，返回 scheduled。
                        final net.minecraft.entity.player.EntityPlayerMP pf = p;
                        server.addScheduledTask(() -> {
                            try {
                                m.invoke(null, pf);
                            } catch (Throwable t) {
                                Throwable cause = t.getCause() != null ? t.getCause() : t;
                                org.apache.logging.log4j.LogManager.getLogger("ulac")
                                        .warn("[ulac] invoke " + cls + "#" + mth + " threw: " + cause, cause);
                            }
                        });
                        out = "scheduled";
                    } else {
                        out = m.invoke(null);
                    }
                    r.addProperty("ok", true);
                    r.addProperty("returned", String.valueOf(out));
                } catch (Throwable t) {
                    return ControlState.QueuedAction.error("invoke failed: " + t);
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
            case "camera": {
                // 视角：{"mode":0|1|2} 0=第一人称 1=背后第三人称 2=正面
                int mode = action.params.has("mode") ? action.params.get("mode").getAsInt() : 0;
                mc.gameSettings.thirdPersonView = Math.max(0, Math.min(2, mode));
                JsonObject r = new JsonObject();
                r.addProperty("ok", true);
                r.addProperty("mode", mc.gameSettings.thirdPersonView);
                return r;
            }
            case "begin":
            case "freeze": {
                return TasStep.begin(mc);
            }
            case "resume":
            case "end": {
                return TasStep.end(mc);
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
