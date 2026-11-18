package aaaa0ggmc.ulac;

import com.google.gson.JsonObject;

/**
 * 统一控制动作命令接口（命令模式路由表条目）。
 */
@FunctionalInterface
public interface IControlAction {
    JsonObject execute(JsonObject req);
}
