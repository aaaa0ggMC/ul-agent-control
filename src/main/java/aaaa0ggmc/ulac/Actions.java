package aaaa0ggmc.ulac;

import com.google.gson.JsonObject;

/**
 * 动作分发：POST /cmd 的 JSON {"action": "...", ...} 进这里。
 * 带 latch 的同步动作由游戏线程消费后回填结果；command 默认等待 5s（慢速 tick 下可调 wait_ms）。
 */
public final class Actions {

    private Actions() {
    }

    public static JsonObject dispatch(JsonObject req) {
        String action = req.has("action") ? req.get("action").getAsString() : "";
        switch (action) {
            case "tick": {
                double rate = num(req, "rate", 20.0);
                if (rate < 0.1) rate = 0.1;
                if (rate > 20.0) rate = 20.0;
                ControlState.tickTps = rate;
                JsonObject r = new JsonObject();
                r.addProperty("rate", rate);
                return ok(r);
            }
            case "move": {
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
            }
            case "look": {
                if (req.has("yaw")) ControlState.lookYaw = (float) num(req, "yaw", 0.0);
                if (req.has("pitch")) ControlState.lookPitch = (float) num(req, "pitch", 0.0);
                ControlState.lookActive = true;
                JsonObject r = new JsonObject();
                r.addProperty("yaw", ControlState.lookYaw);
                r.addProperty("pitch", ControlState.lookPitch);
                return ok(r);
            }
            case "lookoff": {
                ControlState.lookActive = false;
                return ok(new JsonObject());
            }
            case "reset": {
                ControlState.resetControls();
                return ok(new JsonObject());
            }
            case "command": {
                String line = req.has("line") ? req.get("line").getAsString() : "";
                long wait = (long) num(req, "wait_ms", 8000.0);
                ControlState.QueuedAction q = new ControlState.QueuedAction("command", req, true);
                ControlState.SERVER_ACTIONS.add(q);
                return q.await(wait);
            }
            case "respawn": {
                long wait = (long) num(req, "wait_ms", 5000.0);
                ControlState.QueuedAction q = new ControlState.QueuedAction("respawn", req, true);
                ControlState.SERVER_ACTIONS.add(q);
                return q.await(wait);
            }
            case "state": {
                ControlState.QueuedAction q = new ControlState.QueuedAction("state", req, true);
                ControlState.CLIENT_ACTIONS.add(q);
                JsonObject r = q.await((long) num(req, "wait_ms", 3000.0));
                return r;
            }
            case "attack":
            case "use":
            case "hotbar":
            case "chat":
            case "screenshot": {
                long wait = (long) num(req, "wait_ms", 5000.0);
                ControlState.QueuedAction q = new ControlState.QueuedAction(action, req, true);
                ControlState.CLIENT_ACTIONS.add(q);
                return q.await(wait);
            }
            default: {
                return ControlState.QueuedAction.error("unknown action: " + action);
            }
        }
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
