package top.blueworkshop.cyancodex;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import top.blueworkshop.cyancodex.config.CyanConfig;
import top.blueworkshop.cyancodex.ui.MainScreen;

/**
 * CyanCodex 指令助手 · Fabric 客户端入口（Minecraft 26.2 分支）
 *
 * 26.x 的差异：
 *   - Fabric 键位 API 从 keybinding.v1.KeyBindingHelper 改名为 keymapping.v1.KeyMappingHelper
 *   - KeyMapping 的“分类”参数由字符串改成 KeyMapping.Category（由 Identifier 注册）
 *   - Minecraft#setScreen 改名为 setScreenAndShow，且不再暴露当前屏幕字段
 */
public class CyanCodexClient implements ClientModInitializer {
    public static final String MOD_ID = CyanLog.MOD_ID;

    private static KeyMapping openKey;

    @Override
    public void onInitializeClient() {
        CyanConfig.load();

        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(MOD_ID, "main"));

        openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "cyancodex.key.open",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                category
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.consumeClick()) {
                if (client.player != null) {
                    Minecraft.getInstance().setScreenAndShow(new MainScreen());
                }
            }
        });

        CyanLog.LOGGER.info("[CyanCodex] 指令助手已加载（26.2 分支），按 K 打开（接口：{}）", CyanConfig.get().apiBase);
    }
}
