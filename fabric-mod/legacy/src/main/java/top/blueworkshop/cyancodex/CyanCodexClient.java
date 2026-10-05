package top.blueworkshop.cyancodex;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.blueworkshop.cyancodex.config.CyanConfig;
import top.blueworkshop.cyancodex.ui.MainScreen;

/**
 * CyanCodex 指令助手 · Fabric 客户端入口
 *
 * 默认按键 K 打开助手界面；界面里可以浏览指令库、用生成器产出 Java 版指令、
 * 问 AI 要指令，并把结果复制到剪贴板或直接执行。
 */
public class CyanCodexClient implements ClientModInitializer {
    public static final String MOD_ID = "cyancodex";
    public static final Logger LOGGER = CyanLog.LOGGER;

    private static KeyMapping openKey;

    @Override
    public void onInitializeClient() {
        CyanConfig.load();

        openKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "cyancodex.key.open",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                "cyancodex.key.category"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.consumeClick()) {
                if (client.screen == null && client.player != null) {
                    client.setScreen(new MainScreen());
                }
            }
        });

        LOGGER.info("[CyanCodex] 指令助手已加载，按 K 打开（接口：{}）", CyanConfig.get().apiBase);
    }
}
