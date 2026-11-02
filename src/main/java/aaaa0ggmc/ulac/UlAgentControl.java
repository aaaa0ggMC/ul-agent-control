package aaaa0ggmc.ulac;

import aaaa0ggmc.ulac.server.ControlServer;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;

/**
 * UL Agent Control —— 程式化游戏控制（测试工具）。
 *
 * 回环 HTTP API（token 鉴权，只绑 127.0.0.1）：
 *   GET  /state   状态回读（位置/血/背包/附近实体/注视目标/界面/聊天缓冲/TPS…）
 *   POST /cmd     动作执行（tick 调速、虚拟输入、视角、攻击/使用/切栏、聊天、
 *                 执行命令并回传输出、游戏内截图）
 *
 * 连接信息写在 <gameDir>/ul-agent-control.json（host/port/token），
 * node SDK 见项目 node/ 目录。
 */
@Mod(
        modid = UlAgentControl.MODID,
        name = UlAgentControl.NAME,
        version = UlAgentControl.VERSION,
        acceptableRemoteVersions = "*"
)
public class UlAgentControl {

    public static final String MODID = "ul_agent_control";
    public static final String NAME = "UL Agent Control";
    public static final String VERSION = "1.0.0";
    public static final Logger LOGGER = LogManager.getLogger(NAME);

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        File gameDir = event.getModConfigurationDirectory().getParentFile();
        ControlServer.start(gameDir);
        LOGGER.info("[ulac] agent control ready: {}", ControlServer.infoFile);
    }
}
