package aaaa0ggmc.ulac.client;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ScreenShotHelper;

import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 逐 tick 帧录制（TAS 演示用）。
 *
 * 客户端 tick 里检测 {@code world.getTotalWorldTime()} 变化：世界每推进 1 tick 存一帧到
 * {@code <gameDir>/screenshots/ulac-rec-<时间戳>/frame_%05d.png}。
 * 用世界时间（而非客户端 tick 计数）判重，所以：
 *  - 步进放行窗口内跑了 2 个服务端 tick 也只会多一帧，不会出重复帧；
 *  - everyN 采样按「录制期间经过的 tick 数」取模。
 *
 * 配合 assemble.sh 以 fps=20/everyN 压片：1 秒视频 = 20 个游戏 tick（MC 实时速度）。
 */
public final class TickRecorder {

    private static boolean recording;
    private static int everyN = 1;
    private static long lastWorldTime = Long.MIN_VALUE;
    private static long tickCount;
    private static int frameIndex;
    private static File dir;

    private TickRecorder() {
    }

    /** 开始录制。dir 传 null 时自动建 screenshots/ulac-rec-<ts>/。everyN=1 每 tick 一帧。 */
    public static synchronized JsonObject start(File gameDir, int everyNIn) {
        JsonObject r = new JsonObject();
        if (recording) {
            r.addProperty("ok", false);
            r.addProperty("error", "already recording");
            return r;
        }
        everyN = Math.max(1, everyNIn);
        dir = new File(gameDir, "screenshots" + File.separator + "ulac-rec-" + System.currentTimeMillis());
        if (!dir.mkdirs() && !dir.isDirectory()) {
            r.addProperty("ok", false);
            r.addProperty("error", "cannot create " + dir);
            return r;
        }
        recording = true;
        lastWorldTime = Long.MIN_VALUE;
        tickCount = 0;
        frameIndex = 0;
        r.addProperty("ok", true);
        r.addProperty("dir", dir.getAbsolutePath());
        r.addProperty("everyN", everyN);
        return r;
    }

    /** 停止录制，返回帧数与目录。 */
    public static synchronized JsonObject stop() {
        JsonObject r = new JsonObject();
        r.addProperty("ok", true);
        r.addProperty("frames", frameIndex);
        r.addProperty("dir", dir == null ? "" : dir.getAbsolutePath());
        recording = false;
        // 排空后台编码队列，保证返回时帧文件都已落盘（assemble 才不会缺尾帧）
        try {
            ENCODER.submit(() -> {
            }).get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Throwable ignored) {
        }
        return r;
    }

    public static synchronized boolean isRecording() {
        return recording;
    }

    /** 客户端 tick 调用：服务端世界时间变了（= 推进了 1 个服务端 tick）就存一帧。 */
    public static void onClientTick(Minecraft mc) {
        if (!recording) {
            return;
        }
        // 用集成服务器的权威世界时间：mc.world（WorldClient）的时间来自每 20 tick 一个的
        // SPacketTimeUpdate，不是逐 tick 信号；服务端 worlds[0].totalWorldTime 每 tick 必 +1。
        long worldTime;
        MinecraftServer server = mc.getIntegratedServer();
        if (server != null && server.worlds != null && server.worlds.length > 0
                && server.worlds[0] != null) {
            worldTime = server.worlds[0].getTotalWorldTime();
        } else if (mc.world != null) {
            worldTime = mc.world.getTotalWorldTime();
        } else {
            return;
        }
        if (worldTime == lastWorldTime) {
            return;
        }
        lastWorldTime = worldTime;
        tickCount++;
        if (tickCount % everyN != 0) {
            return;
        }
        capture(mc);
    }

    /** 编码队列有界：1080p 一帧 BufferedImage ~8MB，无界队列会在编码追不上捕获时把堆撑爆
     *  （实测长录制 OOM：Java heap space）。超出就丢帧并计数，宁缺帧不崩。 */
    private static final java.util.concurrent.ArrayBlockingQueue<Runnable> ENCODER_QUEUE =
            new java.util.concurrent.ArrayBlockingQueue<>(12);
    private static final java.util.concurrent.ExecutorService ENCODER =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "ulac-frame-encoder");
                t.setDaemon(true);
                return t;
            });
    private static int droppedFrames;

    private static synchronized void capture(Minecraft mc) {
        if (dir == null) {
            return;
        }
        final int index = frameIndex;
        try {
            // 主线只做 GL 读取（快）；PNG 编码丢后台线程——1080p 编码 ~100ms 会让
            // 250ms 间隔的服务端 tick 在保存期间漏帧（实测 20 tick 只出 11 帧）。
            BufferedImage image = ScreenShotHelper.createScreenshot(
                    mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
            if (image == null) {
                return;
            }
            final File out = new File(dir, String.format("frame_%05d.png", index));
            Runnable job = () -> {
                try {
                    javax.imageio.ImageIO.write(image, "png", out);
                } catch (Throwable ignored) {
                    // 单帧失败不中断录制
                }
            };
            if (!ENCODER_QUEUE.offer(job)) {
                droppedFrames++;
                return;   // 队列满：丢这一帧，绝不堆积 BufferedImage
            }
            frameIndex++;
            ENCODER.execute(() -> {
                Runnable r = ENCODER_QUEUE.poll();
                if (r != null) {
                    r.run();
                }
            });
        } catch (Throwable ignored) {
            // GL 读取偶发失败（OptiFine framebuffer），跳过该帧
        }
    }
}
