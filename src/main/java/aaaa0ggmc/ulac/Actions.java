package aaaa0ggmc.ulac;

import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 动作分发：POST /cmd 的 JSON {"action": "...", ...} 进这里。
 * 通过命令模式路由表分发；带 latch 的同步动作由游戏线程消费后回填结果。
 */
public final class Actions {

    private static final Map<String, IControlAction> ROUTES = buildRoutes();

    private Actions() {
    }

    private static Map<String, IControlAction> buildRoutes() {
        Map<String, IControlAction> map = new HashMap<>();

        map.put("tick", req -> {
            double rate = num(req, "rate", 20.0);
            if (rate < 0.1) rate = 0.1;
            if (rate > 20.0) rate = 20.0;
            ControlState.tickTps = rate;
            JsonObject r = new JsonObject();
            r.addProperty("rate", rate);
            return ok(r);
        });

        map.put("move", req -> {
            if (req.has("forward")) ControlState.moveForward = (float) num(req, "forward", 0.0);
            if (req.has("strafe")) ControlState.moveStrafe = (float) num(req, "strafe", 0.0);
            if (req.has("jump")) ControlState.jump = req.get("jump").getAsBoolean();
            if (req.has("sneak")) ControlState.sneak = req.get("sneak").getAsBoolean();
            if (req.has("sprint")) ControlState.sprint = req.get("sprint").getAsBoolean();
            JsonObject r = new JsonObject();
            r.addProperty("forward", ControlState.moveForward);
            r.addProperty("strafe", ControlState.moveStrafe);
            r.addProperty("jump", ControlState.jump);
            r.addProperty("sneak", ControlState.sneak);
            r.addProperty("sprint", ControlState.sprint);
            return ok(r);
        });

        map.put("look", req -> {
            if (req.has("yaw")) ControlState.lookYaw = (float) num(req, "yaw", 0.0);
            if (req.has("pitch")) ControlState.lookPitch = (float) num(req, "pitch", 0.0);
            ControlState.lookActive = true;
            JsonObject r = new JsonObject();
            r.addProperty("yaw", ControlState.lookYaw);
            r.addProperty("pitch", ControlState.lookPitch);
            return ok(r);
        });

        map.put("lookoff", req -> {
            ControlState.lookActive = false;
            return ok(new JsonObject());
        });

        map.put("reset", req -> {
            ControlState.resetControls();
            return ok(new JsonObject());
        });

        map.put("command", req -> enqueueServer("command", req, 8000.0));
        map.put("respawn", req -> enqueueServer("respawn", req, 5000.0));
        map.put("state", req -> enqueueClient("state", req, 3000.0));

        for (String clientAction : new String[]{
                "attack", "use", "hotbar", "chat", "join", "screenshot",
                "camera", "begin", "freeze", "resume", "end", "record", "step"
        }) {
            map.put(clientAction, req -> enqueueClient(clientAction, req, 5000.0));
        }

        map.put("invoke", req -> {
            long wait = (long) num(req, "wait_ms", 8000.0);
            boolean withPlayer = req.has("arg") && "player".equals(req.get("arg").getAsString());
            ControlState.QueuedAction q = new ControlState.QueuedAction("invoke", req, true);
            if (withPlayer) {
                ControlState.SERVER_ACTIONS.add(q);
            } else {
                ControlState.CLIENT_ACTIONS.add(q);
            }
            return q.await(wait);
        });

        map.put("watch", req -> {
            ControlState.QueuedAction q = new ControlState.QueuedAction("watch", req, true);
            ControlState.CLIENT_ACTIONS.add(q);
            JsonObject started = q.await(3000L);
            if (started != null && started.has("ok") && !started.get("ok").getAsBoolean()) {
                return started;
            }
            long timeout = (long) num(req, "timeout_ms", 120000.0);
            return aaaa0ggmc.ulac.client.Watcher.awaitFinish(timeout + 5000L);
        });

        return Collections.unmodifiableMap(map);
    }

    public static JsonObject dispatch(JsonObject req) {
        // 任意 CLI 动作即视为操控会话开始（失焦暂停修复等副作用自此才生效）
        ControlState.driveSessionActive = true;
        String action = req.has("action") ? req.get("action").getAsString() : "";
        IControlAction handler = ROUTES.get(action);
        if (handler == null) {
            return ControlState.QueuedAction.error("unknown action: " + action);
        }
        return handler.execute(req);
    }

    private static JsonObject enqueueClient(String type, JsonObject req, double defaultWaitMs) {
        long wait = (long) num(req, "wait_ms", defaultWaitMs);
        ControlState.QueuedAction q = new ControlState.QueuedAction(type, req, true);
        ControlState.CLIENT_ACTIONS.add(q);
        return q.await(wait);
    }

    private static JsonObject enqueueServer(String type, JsonObject req, double defaultWaitMs) {
        long wait = (long) num(req, "wait_ms", defaultWaitMs);
        ControlState.QueuedAction q = new ControlState.QueuedAction(type, req, true);
        ControlState.SERVER_ACTIONS.add(q);
        return q.await(wait);
    }

    private static double num(JsonObject o, String key, double def) {
        try {
            return o.has(key) ? o.get(key).getAsDouble() : def;
        } catch (Exception e) {
            return def;
        }
    }

    private static JsonObject ok(JsonObject r) {
        r.addProperty("ok", true);
        return r;
    }
}
