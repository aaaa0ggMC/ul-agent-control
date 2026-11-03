package aaaa0ggmc.ulac.client;

import aaaa0ggmc.ulac.ControlState;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

/**
 * watch(条件, tick 速度, 超时) —— 脚本化等待原语。
 *
 * 语义：在「逐 tick 步进」的时间模型下反复推进游戏时间并评估条件，满足即返回；
 * 超时返回 satisfied=false。条件 = 任意模组的公开静态方法（与 invoke 同一套反射语义），
 * 比较器支持 equals / min / max。
 *
 * 例（等泰坦变身完成）：
 *   watch {"class":"aaaa0ggmc.ulmodpack.zombie.ZombieForms","method":"isTitan","arg":"player",
 *          "expect":{"equals":"true"},"every":1,"timeout_ms":120000}
 * 例（等随从数 ≥1）：
 *   watch {"class":"...HordeManager","method":"countFollowers","arg":"player",
 *          "expect":{"min":1},"every":2,"timeout_ms":60000}
 *
 * 时间模型：进入 watch 时若世界未冻结会自动开 step 模式（暂停冻结）；每次评估前放行
 * every 个 tick。结束后保持当前冻结状态（脚本继续用 step/forward 控制）。
 */
public final class Watcher {

    private static volatile boolean active;
    private static volatile boolean resultReady;
    private static volatile Object lastResult;

    private static String cls;
    private static String mth;
    private static boolean withPlayer;
    private static String expectEquals;
    private static Double expectMin;
    private static Double expectMax;
    private static long deadlineMs;
    private static int every = 1;
    private static int ticksWaited;
    private static boolean satisfied;

    private Watcher() {
    }

    public static synchronized JsonObject start(Minecraft mc, JsonObject p) {
        JsonObject r = new JsonObject();
        if (active) {
            r.addProperty("ok", false);
            r.addProperty("error", "a watch is already running");
            return r;
        }
        cls = p.has("class") ? p.get("class").getAsString() : "";
        mth = p.has("method") ? p.get("method").getAsString() : "";
        withPlayer = p.has("arg") && "player".equals(p.get("arg").getAsString());
        expectEquals = null;
        expectMin = null;
        expectMax = null;
        if (p.has("expect")) {
            JsonObject e = p.getAsJsonObject("expect");
            if (e.has("equals")) {
                expectEquals = e.get("equals").getAsString();
            }
            if (e.has("min")) {
                expectMin = e.get("min").getAsDouble();
            }
            if (e.has("max")) {
                expectMax = e.get("max").getAsDouble();
            }
        }
        if (cls.isEmpty() || mth.isEmpty() || (expectEquals == null && expectMin == null && expectMax == null)) {
            r.addProperty("ok", false);
            r.addProperty("error", "need class/method and one of expect.equals/min/max");
            return r;
        }
        every = Math.max(1, p.has("every") ? p.get("every").getAsInt() : 1);
        long timeout = p.has("timeout_ms") ? p.get("timeout_ms").getAsLong() : 120000L;
        deadlineMs = System.currentTimeMillis() + timeout;
        ticksWaited = 0;
        satisfied = false;
        lastResult = null;

        // 世界未冻结就自动开 step 模式（冻结），让时间只在每次评估时前进 every tick
        if (TasStep.status().get("phase").getAsString().equals("IDLE")) {
            TasStep.begin(mc);
        }
        active = true;
        resultReady = false;
        releaseTicks(mc);   // 先放行 tick：让实体更新跑过一轮（位移/状态可被观测）
        evaluate(mc);       // 首次评估落在其后
        r.addProperty("ok", true);
        r.addProperty("watching", cls + "#" + mth);
        r.addProperty("every", every);
        r.addProperty("timeout_ms", timeout);
        return r;
    }

    public static synchronized JsonObject result() {
        JsonObject r = new JsonObject();
        r.addProperty("ok", true);
        r.addProperty("active", active);
        r.addProperty("satisfied", satisfied);
        r.addProperty("result", lastResult == null ? "null" : String.valueOf(lastResult));
        r.addProperty("ticks_waited", ticksWaited);
        return r;
    }

    public static void onClientTick(Minecraft mc) {
        if (!active) {
            return;
        }
        if (System.currentTimeMillis() > deadlineMs) {
            synchronized (Watcher.class) {
                active = false;
            }
            return;
        }
        pollPending();
        if (!resultReady) {
            return;
        }
        if (matches()) {
            synchronized (Watcher.class) {
                active = false;
                satisfied = true;
            }
            return;
        }
        synchronized (Watcher.class) {
            resultReady = false;
        }
        evaluate(mc);
        releaseTicks(mc);
    }

    private static ControlState.QueuedAction pending;

    /**
     * 评估：经服务端 tick 队列执行（放行 tick 内跑，实体状态是活的；冻结期的
     * futureTaskQueue 会看到静止世界——实测 isMoving 类判定全挂）。
     * 非阻塞：每次客户端 tick 轮询 QueuedAction.result。
     */
    private static void evaluate(Minecraft mc) {
        JsonObject p = new JsonObject();
        p.addProperty("class", cls);
        p.addProperty("method", mth);
        if (withPlayer) {
            p.addProperty("arg", "player");
        }
        pending = new ControlState.QueuedAction("invoke", p, true);
        ControlState.SERVER_ACTIONS.add(pending);
    }

    private static void pollPending() {
        ControlState.QueuedAction q = pending;
        if (q == null) {
            return;
        }
        JsonObject r = q.result;
        if (r == null) {
            return;
        }
        pending = null;
        if (r.has("returned")) {
            lastResult = r.get("returned").getAsString();
        } else {
            lastResult = "ERR:" + (r.has("error") ? r.get("error").getAsString() : "?");
        }
        resultReady = true;
    }

    /** 放行 every 个 tick（冻结态走 TasStep；运行态时间自己走）。 */
    private static void releaseTicks(Minecraft mc) {
        String phase = TasStep.status().get("phase").getAsString();
        if (phase.equals("READY")) {
            TasStep.step(mc, every);
            ticksWaited += every;
        } else if (phase.equals("IDLE")) {
            ticksWaited += every;   // 世界在跑，按 every 个 tick 节流由 resultReady 控制
        }
    }

    private static boolean matches() {
        String s = String.valueOf(lastResult);
        if (expectEquals != null && !s.equalsIgnoreCase(expectEquals)) {
            return false;
        }
        if (expectMin != null || expectMax != null) {
            double v;
            try {
                v = Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return false;
            }
            if (expectMin != null && v < expectMin) {
                return false;
            }
            if (expectMax != null && v > expectMax) {
                return false;
            }
        }
        return true;
    }

    /** 调试：当前 watch 的状态快照。 */
    public static synchronized JsonObject status() {
        JsonObject r = result();
        r.addProperty("cls", cls == null ? "" : cls);
        r.addProperty("mth", mth == null ? "" : mth);
        return r;
    }

    /** 等 watch 结束（CLI 同步等待用）：轮询到 !active 或超时。 */
    public static JsonObject awaitFinish(long waitMs) {
        long until = System.currentTimeMillis() + waitMs;
        while (active && System.currentTimeMillis() < until) {
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return result();
    }
}
