package aaaa0ggmc.ulac.client;

import aaaa0ggmc.ulac.ControlState;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngameMenu;

/**
 * TAS 逐 tick 步进（客户端驱动）。
 *
 * 机制（已核实，见 reference IntegratedServer.tick L146-154）：
 *  开带 doesGuiPauseGame 的屏幕 → Minecraft.isGamePaused → 集成服务器跳过世界 tick（冻结）；
 *  关掉屏幕放行 → 服务端 tick 恢复 → 计满目标 tick 数后立刻重新冻结。
 *  反射直写 isGamePaused 会被 runGameLoop 每轮重算覆盖，不可行，只能开关屏幕。
 *
 * 精度：放行窗口 = 关屏到重新开屏。步进期间把客户端 timer 调到 100tps（5ms/tick），
 *  客户端频繁轮询服务端计数，窗口通常只含 1 个服务端 tick；偶尔多 1 个，
 *  录制端按世界时间去重，表现为多一帧而非重复帧。
 *
 * 状态机：IDLE →(begin) ARMING 等 2 个客户端 tick 确认冻结 → READY
 *         →(step) 关屏 RUNNING 服务端计数 → 计满 → READY（重新开屏）
 */
public final class TasStep {

    private enum Phase { IDLE, ARMING, READY, RUNNING }

    private static Phase phase = Phase.IDLE;
    private static int armTicks;
    private static int ticksWanted;
    private static int ticksSeen;
    /** 步进前的 tickTps，结束时恢复。 */
    private static double savedTps = 20.0D;
    /** 步进期间客户端轮询频率（tick/秒）。 */
    private static final double STEP_POLL_TPS = 100.0D;

    private TasStep() {
    }

    /** 进入步进模式：开暂停屏幕冻结世界。 */
    public static synchronized JsonObject begin(Minecraft mc) {
        JsonObject r = new JsonObject();
        if (phase != Phase.IDLE) {
            r.addProperty("ok", false);
            r.addProperty("phase", phase.name());
            r.addProperty("error", "step mode already active");
            return r;
        }
        if (mc.world == null || mc.player == null) {
            r.addProperty("ok", false);
            r.addProperty("error", "not in world");
            return r;
        }
        savedTps = ControlState.tickTps;
        ControlState.tickTps = STEP_POLL_TPS;
        mc.displayGuiScreen(new GuiIngameMenu());
        phase = Phase.ARMING;
        armTicks = 2;
        r.addProperty("ok", true);
        r.addProperty("phase", "ARMING");
        return r;
    }

    /** 放行 n 个服务端 tick 后重新冻结。READY 态才接受。 */
    public static synchronized JsonObject step(Minecraft mc, int n) {
        JsonObject r = new JsonObject();
        if (phase != Phase.READY) {
            r.addProperty("ok", false);
            r.addProperty("phase", phase.name());
            r.addProperty("error", "world not frozen (begin first, or previous step still running)");
            return r;
        }
        ticksWanted = Math.max(1, n);
        ticksSeen = 0;
        phase = Phase.RUNNING;
        mc.displayGuiScreen(null);   // 关屏放行：下一轮 runGameLoop 重算 isGamePaused=false，服务端恢复
        r.addProperty("ok", true);
        r.addProperty("running", ticksWanted);
        return r;
    }

    /** 退出步进模式：关屏恢复正常速度。 */
    public static synchronized JsonObject end(Minecraft mc) {
        JsonObject r = new JsonObject();
        mc.displayGuiScreen(null);
        ControlState.tickTps = savedTps;
        phase = Phase.IDLE;
        r.addProperty("ok", true);
        return r;
    }

    public static synchronized JsonObject status() {
        JsonObject r = new JsonObject();
        r.addProperty("phase", phase.name());
        r.addProperty("ticksSeen", ticksSeen);
        r.addProperty("ticksWanted", ticksWanted);
        return r;
    }

    /** 客户端 tick 驱动：ARMING 确认冻结；RUNNING 计满后重新开屏。 */
    public static void onClientTick(Minecraft mc) {
        Phase p;
        synchronized (TasStep.class) {
            p = phase;
        }
        if (p == Phase.ARMING) {
            if (--armTicks <= 0) {
                synchronized (TasStep.class) {
                    phase = Phase.READY;
                }
            }
        } else if (p == Phase.RUNNING) {
            int seen;
            synchronized (TasStep.class) {
                seen = ticksSeen;
            }
            if (seen >= ticksWanted) {
                mc.displayGuiScreen(new GuiIngameMenu());
                synchronized (TasStep.class) {
                    phase = Phase.READY;
                }
            }
        }
    }

    /** 服务端 tick 计数（ServerTickHandler 调用）：只在 RUNNING 态计数。 */
    public static void onServerTick() {
        synchronized (TasStep.class) {
            if (phase == Phase.RUNNING) {
                ticksSeen++;
            }
        }
    }
}
