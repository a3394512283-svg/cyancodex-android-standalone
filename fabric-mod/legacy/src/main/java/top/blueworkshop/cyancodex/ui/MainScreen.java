package top.blueworkshop.cyancodex.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import top.blueworkshop.cyancodex.api.CyanApi;
import top.blueworkshop.cyancodex.config.CyanConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * CyanCodex 指令助手主界面。
 *
 * 版面（GUI 坐标）：
 *   y=26 页签行 | y=50 控件行 | y=74 生成器附加行
 *   列表从 listTop 开始，行高 22，可见行数按窗口高度自适应
 *   底部：复制 / 执行 / 收藏 / 翻屏 / 刷新
 */
public class MainScreen extends Screen {
    private enum Tab {
        LIBRARY("指令库"), GENERATOR("生成器"), AI("AI 助手"), FAVORITE("收藏"), SETTINGS("设置");

        final String label;

        Tab(String label) {
            this.label = label;
        }
    }

    private static final int ROW_H = 22;
    private static final int ROW_TABS_Y = 26;
    private static final int ROW_CTRL_Y = 50;
    private static final int ROW_EXTRA_Y = 74;

    private static final String[] GEN_IDS = {"scroller", "scoreboard", "enumerate", "music", "titleraw"};
    private static final String[] GEN_NAMES = {"滚动字幕", "T显/计分板", "穷举生成", "音乐生成器", "T显JSON"};
    private static final String[][] GEN_MODES = {
            {"roll", "banner"},
            {"sidebar", "tdisplay"},
            {"", ""},
            {"true", "false"},
            {"title", "subtitle"}
    };
    private static final String[] GEN_FIELD_A_LABEL = {"字幕内容", "计分项ID", "指令模板", "音符序列", "显示文字"};
    private static final String[] GEN_FIELD_B_LABEL = {"", "显示名称", "候选物品", "乐器", ""};

    private Tab tab = Tab.LIBRARY;

    private final List<String> lines = new ArrayList<>();
    private final List<String> rawValues = new ArrayList<>();
    private int selected = -1;
    private int page = 1;
    private int total = 0;
    private boolean loading = false;
    private String status = "";
    private boolean statusError = false;

    private String query = "";
    private EditBox searchBox;

    private int genIndex = 0;
    private EditBox genFieldA;
    private EditBox genFieldB;
    private int genModeIndex = 0;

    private EditBox aiBox;
    private final List<String> aiMessages = new ArrayList<>();

    private EditBox apiBox;
    private EditBox keyBox;

    public MainScreen() {
        super(Component.literal("CyanCodex 指令助手"));
    }

    private int listTop() {
        return tab == Tab.GENERATOR ? ROW_EXTRA_Y + 68 : ROW_CTRL_Y + 26;
    }

    private int visibleRows() {
        int avail = this.height - listTop() - 40;
        return Math.max(2, Math.min(10, avail / ROW_H));
    }

    @Override
    protected void init() {
        clearWidgets();

        int tabW = Math.max(52, (this.width - 20) / Tab.values().length - 4);
        int x = 10;
        for (Tab t : Tab.values()) {
            final Tab target = t;
            addRenderableWidget(Button.builder(Component.literal(t.label), b -> {
                tab = target;
                selected = -1;
                page = 1;
                init();
                if (target == Tab.LIBRARY) loadCommands();
                if (target == Tab.FAVORITE) showFavorites();
            }).bounds(x, ROW_TABS_Y, tabW, 20).build());
            x += tabW + 4;
        }

        switch (tab) {
            case LIBRARY -> initLibrary();
            case GENERATOR -> initGenerator();
            case AI -> initAi();
            case FAVORITE -> showFavorites();
            case SETTINGS -> initSettings();
        }

        if (tab != Tab.SETTINGS) initActionBar();
    }

    private void initLibrary() {
        int bw = 58;
        searchBox = new EditBox(this.font, 10, ROW_CTRL_Y, Math.max(60, this.width - 20 - bw * 2 - 8), 20,
                Component.literal("搜索指令"));
        searchBox.setMaxLength(64);
        searchBox.setValue(query);
        addRenderableWidget(searchBox);
        addRenderableWidget(Button.builder(Component.literal("搜索"), b -> {
            query = searchBox.getValue();
            page = 1;
            loadCommands();
        }).bounds(this.width - 10 - bw * 2 - 4, ROW_CTRL_Y, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("刷新"), b -> loadCommands())
                .bounds(this.width - 10 - bw, ROW_CTRL_Y, bw, 20).build());
    }

    private void initGenerator() {
        int bw = Math.max(48, (this.width - 20) / GEN_IDS.length - 4);
        int x = 10;
        for (int i = 0; i < GEN_IDS.length; i++) {
            final int idx = i;
            addRenderableWidget(Button.builder(Component.literal(GEN_NAMES[i]), b -> {
                genIndex = idx;
                genModeIndex = 0;
                lines.clear();
                rawValues.clear();
                selected = -1;
                init();
            }).bounds(x, ROW_CTRL_Y, bw, 20).build());
            x += bw + 4;
        }

        genFieldA = new EditBox(this.font, 10, ROW_EXTRA_Y, this.width - 20, 18,
                Component.literal(GEN_FIELD_A_LABEL[genIndex] + "（主要参数）"));
        genFieldA.setMaxLength(400);
        genFieldA.setValue(defaultFieldA());
        addRenderableWidget(genFieldA);

        int y2 = ROW_EXTRA_Y + 22;
        if (!GEN_FIELD_B_LABEL[genIndex].isEmpty()) {
            genFieldB = new EditBox(this.font, 10, y2, Math.max(80, this.width - 130), 18,
                    Component.literal(GEN_FIELD_B_LABEL[genIndex]));
            genFieldB.setMaxLength(200);
            genFieldB.setValue(defaultFieldB());
            addRenderableWidget(genFieldB);
        }
        if (!GEN_MODES[genIndex][0].isEmpty()) {
            addRenderableWidget(Button.builder(modeLabel(), b -> {
                genModeIndex = (genModeIndex + 1) % GEN_MODES[genIndex].length;
                b.setMessage(modeLabel());
            }).bounds(this.width - 118, y2, 108, 18).build());
        }
        addRenderableWidget(Button.builder(Component.literal("⚡ 生成 Java 版指令"), b -> runGenerator())
                .bounds(10, y2 + 22, this.width - 20, 20).build());
    }

    private Component modeLabel() {
        String m = GEN_MODES[genIndex][genModeIndex];
        String shown = switch (m) {
            case "roll" -> "轮播";
            case "banner" -> "横幅";
            case "sidebar" -> "侧边栏";
            case "tdisplay" -> "T显";
            case "true" -> "循环";
            case "false" -> "不循环";
            case "title" -> "标题";
            case "subtitle" -> "副标题";
            default -> m;
        };
        return Component.literal("模式：" + shown);
    }

    private String defaultFieldA() {
        return switch (GEN_IDS[genIndex]) {
            case "scroller" -> "欢迎来到沧蓝工坊 服务器长期开放";
            case "scoreboard" -> "money";
            case "enumerate" -> "give @p minecraft:{item} {n}";
            case "music" -> "0,2,4,7,12";
            default -> "欢迎来到服务器";
        };
    }

    private String defaultFieldB() {
        return switch (GEN_IDS[genIndex]) {
            case "scoreboard" -> "金币";
            case "enumerate" -> "apple,bread,diamond";
            case "music" -> "harp";
            default -> "";
        };
    }

    private void initAi() {
        aiBox = new EditBox(this.font, 10, ROW_CTRL_Y, Math.max(80, this.width - 20 - 92), 20,
                Component.literal("用一句话描述你要的指令"));
        aiBox.setMaxLength(400);
        addRenderableWidget(aiBox);
        addRenderableWidget(Button.builder(Component.literal("问 AI"), b -> askAi())
                .bounds(this.width - 10 - 86, ROW_CTRL_Y, 86, 20).build());
    }

    private void initSettings() {
        apiBox = new EditBox(this.font, 10, ROW_CTRL_Y + 14, this.width - 20, 20, Component.literal("接口地址"));
        apiBox.setMaxLength(200);
        apiBox.setValue(CyanConfig.get().apiBase);
        addRenderableWidget(apiBox);

        keyBox = new EditBox(this.font, 10, ROW_CTRL_Y + 58, this.width - 20, 20, Component.literal("API 密钥"));
        keyBox.setMaxLength(200);
        keyBox.setValue(CyanConfig.get().apiKey);
        addRenderableWidget(keyBox);

        addRenderableWidget(Button.builder(Component.literal("保存并测试"), b -> {
            CyanConfig.get().apiBase = apiBox.getValue().trim().replaceAll("/+$", "");
            CyanConfig.get().apiKey = keyBox.getValue().trim();
            CyanConfig.save();
            testKey();
        }).bounds(10, ROW_CTRL_Y + 86, 110, 20).build());
        addRenderableWidget(Button.builder(Component.literal("配置写在 config/cyancodex.json"), b ->
                setStatus("配置路径：.minecraft/config/cyancodex.json", false))
                .bounds(126, ROW_CTRL_Y + 86, 200, 20).build());
    }

    private void initActionBar() {
        int by = this.height - 26;
        int gap = 4;
        int bw = Math.max(40, (this.width - 20 - gap * 5) / 6);
        int x = 10;
        addRenderableWidget(Button.builder(Component.literal("复制"), b -> copySelected()).bounds(x, by, bw, 20).build());
        x += bw + gap;
        addRenderableWidget(Button.builder(Component.literal("执行"), b -> runSelected()).bounds(x, by, bw, 20).build());
        x += bw + gap;
        addRenderableWidget(Button.builder(Component.literal("收藏"), b -> favoriteSelected()).bounds(x, by, bw, 20).build());
        x += bw + gap;
        addRenderableWidget(Button.builder(Component.literal("◀"), b -> turnPage(-1)).bounds(x, by, bw, 20).build());
        x += bw + gap;
        addRenderableWidget(Button.builder(Component.literal("▶"), b -> turnPage(1)).bounds(x, by, bw, 20).build());
        x += bw + gap;
        addRenderableWidget(Button.builder(Component.literal("刷新"), b -> {
            if (tab == Tab.LIBRARY) loadCommands();
            else if (tab == Tab.FAVORITE) showFavorites();
        }).bounds(x, by, bw, 20).build());
    }

    private void turnPage(int delta) {
        page = Math.max(1, page + delta);
        selected = -1;
        if (tab == Tab.LIBRARY) loadCommands();
        else setStatus("第 " + page + " 屏", false);
    }

    // ==================== 生成器 ====================

    private void runGenerator() {
        String type = GEN_IDS[genIndex];
        String a = genFieldA == null ? "" : genFieldA.getValue().trim();
        String b = genFieldB == null ? "" : genFieldB.getValue().trim();
        String mode = GEN_MODES[genIndex][0].isEmpty() ? "" : GEN_MODES[genIndex][genModeIndex];

        JsonObject args = new JsonObject();
        switch (type) {
            case "scroller" -> {
                args.addProperty("text", a);
                args.addProperty("mode", mode.isEmpty() ? "roll" : mode);
                args.addProperty("color", "gold");
            }
            case "scoreboard" -> {
                args.addProperty("objective", a.isEmpty() ? "sb" : a);
                args.addProperty("displayName", b.isEmpty() ? "分数" : b);
                args.addProperty("mode", mode.isEmpty() ? "sidebar" : mode);
                args.addProperty("slot", "sidebar");
                args.addProperty("tpos", "title");
                if ("tdisplay".equals(mode)) {
                    JsonArray tl = new JsonArray();
                    JsonObject line = new JsonObject();
                    line.addProperty("prefix", "");
                    line.addProperty("objective", a.isEmpty() ? "sb" : a);
                    line.addProperty("suffix", "");
                    tl.add(line);
                    args.add("tlines", tl);
                } else {
                    JsonArray entries = new JsonArray();
                    String[] names = {"Steve", "Alex"};
                    int[] values = {1280, 960};
                    for (int i = 0; i < names.length; i++) {
                        JsonObject e = new JsonObject();
                        e.addProperty("player", names[i]);
                        e.addProperty("value", values[i]);
                        entries.add(e);
                    }
                    args.add("entries", entries);
                }
            }
            case "enumerate" -> {
                args.addProperty("template", a);
                JsonObject vars = new JsonObject();
                JsonArray items = new JsonArray();
                for (String s : splitCsv(b.isEmpty() ? "apple,bread,diamond" : b)) items.add(s);
                JsonArray counts = new JsonArray();
                for (String s : new String[]{"1", "8", "64"}) counts.add(s);
                vars.add("item", items);
                vars.add("n", counts);
                args.add("variables", vars);
                args.addProperty("maxCount", 60);
            }
            case "music" -> {
                JsonArray events = new JsonArray();
                int tick = 0;
                for (String s : splitCsv(a)) {
                    int note;
                    try {
                        note = Integer.parseInt(s.trim());
                    } catch (Exception ex) {
                        continue;
                    }
                    JsonObject e = new JsonObject();
                    e.addProperty("tick", tick);
                    e.addProperty("instrument", b.isEmpty() ? "harp" : b);
                    e.addProperty("note", Math.max(0, Math.min(24, note)));
                    events.add(e);
                    tick += 2;
                }
                args.add("events", events);
                args.addProperty("loop", !"false".equals(mode));
                args.addProperty("tempo", 10);
            }
            default -> {
                args.addProperty("target", "@a");
                args.addProperty("pos", mode.isEmpty() ? "title" : mode);
                JsonArray segs = new JsonArray();
                JsonObject text = new JsonObject();
                text.addProperty("kind", "text");
                text.addProperty("text", a);
                segs.add(text);
                JsonObject sel = new JsonObject();
                sel.addProperty("kind", "selector");
                sel.addProperty("selector", "@p");
                segs.add(sel);
                args.add("segments", segs);
            }
        }

        setStatus("生成中…", false);
        CyanConfig.get().lastArgs.put(type, args.toString());
        CyanConfig.save();
        CompletableFuture.supplyAsync(() -> CyanApi.generate(type, args, "java")).thenAccept(r ->
                this.minecraft.execute(() -> {
                    lines.clear();
                    rawValues.clear();
                    selected = -1;
                    page = 1;
                    if (!r.ok) {
                        setStatus(r.error, true);
                        return;
                    }
                    if (r.data != null) {
                        lines.addAll(r.data.commands);
                        rawValues.addAll(r.data.commands);
                    }
                    setStatus("已生成 " + lines.size() + " 条 Java 版指令（点选后复制或执行）", false);
                }));
    }

    private static List<String> splitCsv(String s) {
        List<String> out = new ArrayList<>();
        for (String p : s.split("[,，\\s]+")) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    // ==================== 指令库 / 收藏 ====================

    private void loadCommands() {
        loading = true;
        setStatus("加载中…", false);
        final int p = page;
        final String q = query;
        CompletableFuture.supplyAsync(() -> CyanApi.commands(q, p, 10)).thenAccept(r ->
                this.minecraft.execute(() -> {
                    loading = false;
                    lines.clear();
                    rawValues.clear();
                    selected = -1;
                    if (!r.ok) {
                        setStatus(r.error, true);
                        return;
                    }
                    if (r.data != null) {
                        total = r.data.total;
                        for (CyanApi.CommandItem c : r.data.list) {
                            String title = c.displayTitle();
                            if (title.length() > 44) title = title.substring(0, 44) + "…";
                            lines.add(title);
                            rawValues.add(c.command);
                        }
                    }
                    setStatus("第 " + p + " 屏 · 共 " + total + " 条" + (q.isBlank() ? "" : " · 搜索：" + q), false);
                }));
    }

    private void showFavorites() {
        lines.clear();
        rawValues.clear();
        for (String cmd : CyanConfig.get().favorites) {
            String show = cmd.length() > 50 ? cmd.substring(0, 50) + "…" : cmd;
            lines.add(show);
            rawValues.add(cmd);
        }
        selected = -1;
        setStatus("收藏 " + lines.size() + " 条（◀ ▶ 翻屏）", false);
    }

    // ==================== AI ====================

    private void askAi() {
        String q = aiBox == null ? "" : aiBox.getValue().trim();
        if (q.isEmpty()) {
            setStatus("请先用一句话描述你要的指令", true);
            return;
        }
        aiMessages.add(q);
        aiBox.setValue("");
        rebuildAiView();
        lines.add("§7AI 思考中…");
        rawValues.add("");
        setStatus("AI 思考中…（上游限流会自动重试一次）", false);

        JsonArray msgs = new JsonArray();
        JsonObject sys = new JsonObject();
        sys.addProperty("role", "user");
        sys.addProperty("content", "你是《我的世界》Java 版指令专家。只输出可直接使用的 Java 版指令（1.20+ 语法），"
                + "多条指令每行一条；必要时可在指令后用一行以 # 开头的注释说明参数。不要输出多余解释。");
        msgs.add(sys);
        for (int k = 0; k < aiMessages.size(); k++) {
            JsonObject m = new JsonObject();
            m.addProperty("role", k % 2 == 0 ? "user" : "assistant");
            m.addProperty("content", aiMessages.get(k));
            msgs.add(m);
        }

        CompletableFuture.supplyAsync(() -> {
            CyanApi.Result<String> r = CyanApi.aiChat(msgs);
            if (!r.ok && r.error != null && r.error.contains("限流")) {
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException ignored) {
                }
                r = CyanApi.aiChat(msgs);
            }
            return r;
        }).thenAccept(r -> this.minecraft.execute(() -> {
            if (!lines.isEmpty()) {
                lines.remove(lines.size() - 1);
                rawValues.remove(rawValues.size() - 1);
            }
            if (!r.ok) {
                setStatus(r.error, true);
                return;
            }
            aiMessages.add(r.data == null ? "" : r.data);
            rebuildAiView();
            setStatus("AI 已回复（点选后可复制或执行）", false);
        }));
    }

    private void rebuildAiView() {
        lines.clear();
        rawValues.clear();
        for (int i = 0; i < aiMessages.size(); i++) {
            String m = aiMessages.get(i);
            if (i % 2 == 0) {
                lines.add("§b你：§f" + shorten(m));
                rawValues.add("");
            } else {
                for (String oneLine : m.split("\n")) {
                    if (oneLine.isBlank()) continue;
                    lines.add(shorten(oneLine));
                    rawValues.add(oneLine.trim());
                }
            }
        }
        selected = -1;
    }

    private static String shorten(String s) {
        String t = s.replace("\n", " ").trim();
        return t.length() > 70 ? t.substring(0, 70) + "…" : t;
    }

    // ==================== 设置 ====================

    private void testKey() {
        if (!CyanConfig.get().hasKey()) {
            setStatus("请填写 cyx_ 开头的 API 密钥（开发者平台 → API 密钥）", true);
            return;
        }
        setStatus("测试中…", false);
        CompletableFuture.supplyAsync(CyanApi::keyQuota).thenAccept(r ->
                this.minecraft.execute(() -> setStatus(r.ok ? r.data : r.error, !r.ok)));
    }

    // ==================== 行操作 ====================

    private String selectedValue() {
        if (selected < 0 || selected >= rawValues.size()) return null;
        String v = rawValues.get(selected);
        return v == null || v.isBlank() ? null : v;
    }

    private void copySelected() {
        String v = selectedValue();
        if (v == null) {
            setStatus("先点选一条结果", true);
            return;
        }
        if (this.minecraft != null) this.minecraft.keyboardHandler.setClipboard(v);
        CyanConfig.get().remember(v);
        setStatus("已复制到剪贴板：" + shorten(v), false);
    }

    private void runSelected() {
        String v = selectedValue();
        if (v == null) {
            setStatus("先点选一条结果", true);
            return;
        }
        if (this.minecraft == null || this.minecraft.player == null) return;
        if (v.startsWith("#")) {
            setStatus("这是注释行，不能执行", true);
            return;
        }
        String cmd = v.startsWith("/") ? v.substring(1) : v;
        try {
            this.minecraft.player.connection.sendCommand(cmd);
            CyanConfig.get().remember(v);
            setStatus("已执行（需要 OP 权限）", false);
        } catch (Exception e) {
            setStatus("执行失败：" + e.getMessage(), true);
        }
    }

    private void favoriteSelected() {
        String v = selectedValue();
        if (v == null) {
            setStatus("先点选一条结果", true);
            return;
        }
        boolean added = CyanConfig.get().toggleFavorite(v);
        setStatus(added ? "已加入收藏" : "已取消收藏", false);
        if (tab == Tab.FAVORITE) showFavorites();
    }

    private void setStatus(String msg, boolean error) {
        this.status = msg == null ? "" : msg;
        this.statusError = error;
    }

    // ==================== 渲染 ====================

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 不调用 renderBackground()：它在 1.20.1 与 1.20.2+ 的签名不同，
        // 自己画遮罩层才能让同一份源码同时编译 1.20.1 / 1.21.1 / 1.21.8
        g.fill(0, 0, this.width, this.height, 0xC0101010);
        super.render(g, mouseX, mouseY, partialTick);

        g.drawString(this.font, "§aCyanCodex §7指令助手 §8· §7Java 版", 10, 9, 0xFFFFFF);
        if (tab == Tab.SETTINGS) {
            g.drawString(this.font, "§7接口地址", 10, ROW_CTRL_Y + 4, 0xA0A0A0);
            g.drawString(this.font, "§7API 密钥（开发者平台 → API 密钥，cyx_ 开头）", 10, ROW_CTRL_Y + 48, 0xA0A0A0);
        }

        int top = listTop();
        int rows = visibleRows();
        int start = (tab == Tab.LIBRARY) ? 0 : Math.max(0, (page - 1) * rows);
        int end = Math.min(lines.size(), start + rows);
        for (int i = start; i < end; i++) {
            int y = top + (i - start) * ROW_H;
            boolean sel = i == selected;
            g.fill(6, y - 2, this.width - 6, y + ROW_H - 4, sel ? 0x6633CC66 : 0x33000000);
            String text = lines.get(i);
            if (text.length() > 108) text = text.substring(0, 108) + "…";
            g.drawString(this.font, (sel ? "§a▶ " : "§7  ") + text, 10, y, 0xFFFFFF);
        }
        if (lines.isEmpty() && !loading) {
            g.drawString(this.font, "§8（没有内容：换个页签，或在设置里填写 API 密钥后点刷新）", 12, top + 4, 0x808080);
        }

        if (!status.isEmpty()) {
            g.drawString(this.font, (statusError ? "§c" : "§7") + status, 10, this.height - 40, 0xFFFFFF);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (tab != Tab.SETTINGS) {
            int top = listTop();
            int rows = visibleRows();
            int start = (tab == Tab.LIBRARY) ? 0 : Math.max(0, (page - 1) * rows);
            for (int i = start; i < Math.min(lines.size(), start + rows); i++) {
                int y = top + (i - start) * ROW_H;
                if (mouseX >= 6 && mouseX <= this.width - 6 && mouseY >= y - 2 && mouseY < y + ROW_H - 4) {
                    selected = i;
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    // 注意：这里不覆写 mouseScrolled —— 它在 1.20.1 是 3 参、1.20.2+ 变成 4 参，
    // 覆写会导致另一版本编译失败。翻屏统一交给底部 ◀ ▶ 按钮。

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreen(null);
    }
}
