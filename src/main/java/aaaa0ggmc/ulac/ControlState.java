package aaaa0ggmc.ulac;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 全局共享控制状态：HTTP 线程写、游戏线程读。
 * 所有字段 volatile / 线程安全容器，够测试用即可。
 */
public final class ControlState {

    /** 目标 TPS（0.1 ~ 20；20 = 原速）。服务端节流 + 客户端 Timer 同步。 */
    public static volatile double tickTps = 20.0;

    /** 粘性虚拟输入（客户端每个 tick 施加到 movementInput）。 */
    public static volatile float moveForward = 0.0F;
    public static volatile float moveStrafe = 0.0F;
    public static volatile boolean jump = false;
    public static volatile boolean sneak = false;
    public static volatile boolean sprint = false;

    /** 粘性视角（绝对角度；pitch 会被钳制）。 */
    public static volatile boolean lookActive = false;
    public static volatile float lookYaw = 0.0F;
    public static volatile float lookPitch = 0.0F;

    /** 一次性动作队列（客户端 tick / 服务端 tick 分别消费）。 */
    public static final ConcurrentLinkedQueue<QueuedAction> CLIENT_ACTIONS = new ConcurrentLinkedQueue<>();
    public static final ConcurrentLinkedQueue<QueuedAction> SERVER_ACTIONS = new ConcurrentLinkedQueue<>();

    /** 聊天环形缓冲（客户端收到的聊天/命令输出）。 */
    public static final List<String> CHAT = new CopyOnWriteArrayList<>();
    /** 命令输出专用捕获缓冲（执行命令期间打开，避免环形裁剪让增量错位）。 */
    public static final List<String> CAPTURE = new CopyOnWriteArrayList<>();
    public static volatile boolean capturing = false;
    public static volatile String lastScreenshot = "";

    /** 最近一次 tick 实测数据。 */
    public static volatile double lastTickMs = 0.0;
    public static volatile double achievedTps = 20.0;

    private ControlState() {
    }

    /** 清空虚拟输入与视角（tick 速率保留）。 */
    public static void resetControls() {
        moveForward = 0.0F;
        moveStrafe = 0.0F;
        jump = false;
        sneak = false;
        sprint = false;
        lookActive = false;
    }

    public static void addChat(String line) {
        CHAT.add(line);
        while (CHAT.size() > 50) {
            CHAT.remove(0);
        }
        if (capturing) {
            CAPTURE.add(line);
        }
    }

    public static List<String> recentChat(int n) {
        List<String> out = new ArrayList<>(CHAT);
        return out.subList(Math.max(0, out.size() - n), out.size());
    }

    /** 一次性动作。sync 非空时执行方完成后填结果并 countDown。 */
    public static final class QueuedAction {
        public final String type;
        public final JsonObject params;
        public final java.util.concurrent.CountDownLatch latch;
        public volatile JsonObject result;

        public QueuedAction(String type, JsonObject params, boolean sync) {
            this.type = type;
            this.params = params;
            this.latch = sync ? new java.util.concurrent.CountDownLatch(1) : null;
        }

        public JsonObject await(long millis) {
            if (latch == null) {
                JsonObject o = new JsonObject();
                o.addProperty("queued", true);
                return o;
            }
            try {
                latch.await(millis, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return result != null ? result : error("timeout");
        }

        public static JsonObject error(String msg) {
            JsonObject o = new JsonObject();
            o.addProperty("ok", false);
            o.addProperty("error", msg);
            return o;
        }

        public JsonObject done(JsonObject r) {
            this.result = r;
            if (latch != null) {
                latch.countDown();
            }
            return r;
        }
    }
}
