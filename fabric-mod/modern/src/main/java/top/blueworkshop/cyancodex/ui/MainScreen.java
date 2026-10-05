package top.blueworkshop.cyancodex.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import top.blueworkshop.cyancodex.api.CyanApi;
import top.blueworkshop.cyancodex.config.CyanConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * CyanCodex 指令助手主界面（Minecraft 26.2 分支）。
 *
 * 与 1.20.1~1.21.8 分支的实现差别：
 *   26.2 把 GUI 渲染模型换成了 GuiGraphicsExtractor，Screen 上已经没有 render(GuiGraphics,...)。
 *   为了保证在没法实机调试的情况下也能稳定可用，这一版**完全使用原版控件**
 *   （Button / EditBox / StringWidget），不做任何自定义绘制。
 *
 * 交互：每个列表行就是一个按钮 —— 点一下即复制该指令；
 *       底部按钮「执行 / 收藏」作用于最近点选的那一条。
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
    private static final int TABS_Y = 26;
    private static final int CTRL_Y = 50;
    private static final int EXTRA_Y = 74;

    private static final String[] GEN_IDS = {"scroller", "scoreboard", "enumerate", "music", "titleraw"};
    private static final String[] GEN_NAMES = {"滚动字幕", "T显/计分板", "穷举生成", "音乐生成器", "T显JSON"};
    private static final String[][] GEN_MODES = {
            {"roll", "banner"}, {"sidebar", "tdisplay"}, {"", ""}, {"true", "false"}, {"title", "subtitle"}
    };
    private static final String[] FIELD_A_LABEL = {"字幕内容", "计分项ID", "指令模板", "音符序列", "显示文字"};
    private static final String[] FIELD_B_LABEL = {"", "显示名称", "候选物品", "乐器", ""};

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
    private int genModeIndex = 0;
    private EditBox genFieldA;
    private EditBox genFieldB;

    private EditBox aiBox;
    private final List<String> aiMessages = new ArrayList<>();

    private EditBox apiBox;
    private EditBox keyBox;

    private StringWidget statusWidget;

    public MainScreen() {
        super(Component.literal("CyanCodex 指令助手"));
    }

    private int listTop() {
        return tab == Tab.GENERATOR ? EXTRA_Y + 68 : CTRL_Y + 26;
    }

    private int visibleRows() {
        int avail = this.height - listTop() - 38;
        return Math.max(2, Math.min(9, avail / ROW_H));
    }

    @Override
    protected void init() {
        clearWidgets();

        // 标题
        addRenderableWidget(new StringWidget(10, 8, 240, 12,
                Component.literal("CyanCodex 指令助手 · Java 版 26.2"), this.font));

        // 页签
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
            }).bounds(x, TABS_Y, tabW, 20).build());
            x += tabW + 4;
        }

        switch (tab) {
            case LIBRARY -> initLibrary();
            case GENERATOR -> initGenerator();
            case AI -> initAi();
            case FAVORITE -> showFavorites();
            case SETTINGS -> initSettings();
        }

        if (tab != Tab.SETTINGS) {
            initList();
            initActionBar();
        }
        initStatus();
    }

    private void initLibrary() {
        int bw = 58;
        searchBox = new EditBox(this.font, 10, CTRL_Y, Math.max(60, this.width - 20 - bw * 2 - 8), 20,
                Component.literal("搜索指令"));
        searchBox.setMaxLength(64);
        searchBox.setValue(query);
        addRenderableWidget(searchBox);
        addRenderableWidget(Button.builder(Component.literal("搜索"), b -> {
            query = searchBox.getValue();
            page = 1;
            loadCommands();
        }).bounds(this.width - 10 - bw * 2 - 4, CTRL_Y, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("刷新"), b -> loadCommands())
                .bounds(this.width - 10 - bw, CTRL_Y, bw, 20).build());
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
            }).bounds(x, CTRL_Y, bw, 20).build());
            x += bw + 4;
        }

        genFieldA = new EditBox(this.font, 10, EXTRA_Y, this.width - 20, 18,
                Component.literal(FIELD_A_LABEL[genIndex]));
        genFieldA.setMaxLength(400);
        genFieldA.setValue(defaultFieldA());
        addRenderableWidget(genFieldA);

        int y2 = EXTRA_Y + 22;
        if (!FIELD_B_LABEL[genIndex].isEmpty()) {
            genFieldB = new EditBox(this.font, 10, y2, Math.max(80, this.width - 130), 18,
                    Component.literal(FIELD_B_LABEL[genIndex]));
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
        aiBox = new EditBox(this.font, 10, CTRL_Y, Math.max(80, this.width - 20 - 92), 20,
                Component.literal("用一句话描述你要的指令"));
        aiBox.setMaxLength(400);
        addRenderableWidget(aiBox);
        addRenderableWidget(Button.builder(Component.literal("问 AI"), b -> askAi())
                .bounds(this.width - 10 - 86, CTRL_Y, 86, 20).build());
    }

    private void initSettings() {
        apiBox = new EditBox(this.font, 10, CTRL_Y + 12, this.width - 20, 20, Component.literal("接口地址"));
        apiBox.setMaxLength(200);
        apiBox.setValue(CyanConfig.get().apiBase);
        addRenderableWidget(apiBox);

        keyBox = new EditBox(this.font, 10, CTRL_Y + 56, this.width - 20, 20, Component.literal("API 密钥"));
        keyBox.setMaxLength(200);
        keyBox.setValue(CyanConfig.get().apiKey);
        addRenderableWidget(keyBox);

        addRenderableWidget(new StringWidget(10, CTRL_Y + 2, 200, 12, Component.literal("接口地址"), this.font));
        addRenderableWidget(new StringWidget(10, CTRL_Y + 46, 400, 12,
                Component.literal("API 密钥（开发者平台 → API 密钥，cyx_ 开头）"), this.font));

        addRenderableWidget(Button.builder(Component.literal("保存并测试"), b -> {
            CyanConfig.get().apiBase = apiBox.getValue().trim().replaceAll("/+$", "");
            CyanConfig.get().apiKey = keyBox.getValue().trim();
            CyanConfig.save();
            testKey();
        }).bounds(10, CTRL_Y + 86, 110, 20).build());
        addRenderableWidget(Button.builder(Component.literal("配置：config/cyancodex.json"), b ->
                setStatus("配置路径：.minecraft/config/cyancodex.json", false))
                .bounds(126, CTRL_Y + 86, 220, 20).build());
    }

    private void initList() {
        int top = listTop();
        int rows = visibleRows();
        int start = (tab == Tab.LIBRARY) ? 0 : Math.max(0, (page - 1) * rows);
        int end = Math.min(lines.size(), start + rows);
        for (int i = start; i < end; i++) {
            final int idx = i;
            String text = lines.get(i);
            if (text.length() > 70) text = text.substring(0, 70) + "…";
            addRenderableWidget(Button.builder(Component.literal((i == selected ? "▶ " : "") + text), b -> {
                selected = idx;
                copySelected();
                init();     // 刷新 ▶ 标记
            }).bounds(10, top + (i - start) * ROW_H, this.width - 20, ROW_H - 2).build());
        }
        if (lines.isEmpty() && !loading) {
            addRenderableWidget(new StringWidget(12, top + 4, 400, 12,
                    Component.literal("（没有内容：换个页签，或在设置里填 API 密钥后点刷新）"), this.font));
        }
    }

    private void initActionBar() {
        int by = this.height - 26;
        int gap = 4;
        int bw = Math.max(40, (this.width - 20 - gap * 4) / 5);
        int x = 10;
        addRenderableWidget(Button.builder(Component.literal("执行选中"), b -> runSelected()).bounds(x, by, bw, 20).build());
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
            else init();
        }).bounds(x, by, bw, 20).build());
    }

    private void initStatus() {
        statusWidget = new StringWidget(10, this.height - 42, this.width - 20, 12,
                Component.literal(status.isEmpty() ? "按 K 打开/关闭；单击列表项即复制指令" : status), this.font);
        addRenderableWidget(statusWidget);
    }

    private void turnPage(int delta) {
        page = Math.max(1, page + delta);
        selected = -1;
        if (tab == Tab.LIBRARY) loadCommands();
        else init();
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
        init();
        CyanConfig.get().lastArgs.put(type, args.toString());
        CyanConfig.save();
        CompletableFuture.supplyAsync(() -> CyanApi.generate(type, args, "java")).thenAccept(r ->
                Minecraft.getInstance().execute(() -> {
                    lines.clear();
                    rawValues.clear();
                    selected = -1;
                    page = 1;
                    if (!r.ok) {
                        setStatus(r.error, true);
                    } else if (r.data != null) {
                        lines.addAll(r.data.commands);
                        rawValues.addAll(r.data.commands);
                        setStatus("已生成 " + lines.size() + " 条 Java 版指令（单击某行即复制）", false);
                    }
                    init();
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
        init();
        final int p = page;
        final String q = query;
        CompletableFuture.supplyAsync(() -> CyanApi.commands(q, p, 9)).thenAccept(r ->
                Minecraft.getInstance().execute(() -> {
                    loading = false;
                    lines.clear();
                    rawValues.clear();
                    selected = -1;
                    if (!r.ok) {
                        setStatus(r.error, true);
                    } else if (r.data != null) {
                        total = r.data.total;
                        for (CyanApi.CommandItem c : r.data.list) {
                            String title = c.displayTitle();
                            if (title.length() > 44) title = title.substring(0, 44) + "…";
                            lines.add(title);
                            rawValues.add(c.command);
                        }
                        setStatus("第 " + p + " 屏 · 共 " + total + " 条" + (q.isBlank() ? "" : " · 搜索：" + q), false);
                    }
                    init();
                }));
    }

    private void showFavorites() {
        lines.clear();
        rawValues.clear();
        for (String cmd : CyanConfig.get().favorites) {
            lines.add(cmd.length() > 50 ? cmd.substring(0, 50) + "…" : cmd);
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
            init();
            return;
        }
        aiMessages.add(q);
        aiBox.setValue("");
        rebuildAiView();
        setStatus("AI 思考中…（上游限流会自动重试一次）", false);
        init();

        JsonArray msgs = new JsonArray();
        JsonObject sys = new JsonObject();
        sys.addProperty("role", "user");
        sys.addProperty("content", "你是《我的世界》Java 版指令专家。只输出可直接使用的 Java 版指令（1.21+ 语法），"
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
        }).thenAccept(r -> Minecraft.getInstance().execute(() -> {
            if (!r.ok) {
                setStatus(r.error, true);
            } else {
                aiMessages.add(r.data == null ? "" : r.data);
                rebuildAiView();
                setStatus("AI 已回复（单击行即复制）", false);
            }
            init();
        }));
    }

    private void rebuildAiView() {
        lines.clear();
        rawValues.clear();
        for (int i = 0; i < aiMessages.size(); i++) {
            String m = aiMessages.get(i);
            if (i % 2 == 0) {
                lines.add("你：" + shorten(m));
                rawValues.add("");
            } else {
                for (String one : m.split("\n")) {
                    if (one.isBlank()) continue;
                    lines.add(shorten(one));
                    rawValues.add(one.trim());
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
            setStatus("请填写 cyx_ 开头的 API 密钥", true);
            return;
        }
        setStatus("测试中…", false);
        CompletableFuture.supplyAsync(CyanApi::keyQuota).thenAccept(r ->
                Minecraft.getInstance().execute(() -> {
                    setStatus(r.ok ? r.data : r.error, !r.ok);
                    init();
                }));
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
            setStatus("先单击一条结果", true);
            return;
        }
        Minecraft.getInstance().keyboardHandler.setClipboard(v);
        CyanConfig.get().remember(v);
        setStatus("已复制：" + shorten(v), false);
    }

    private void runSelected() {
        String v = selectedValue();
        if (v == null) {
            setStatus("先单击一条结果", true);
            init();
            return;
        }
        if (v.startsWith("#")) {
            setStatus("这是注释行，不能执行", true);
            init();
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        String cmd = v.startsWith("/") ? v.substring(1) : v;
        try {
            mc.player.connection.sendCommand(cmd);
            CyanConfig.get().remember(v);
            setStatus("已执行（需要 OP 权限）", false);
        } catch (Exception e) {
            setStatus("执行失败：" + e.getMessage(), true);
        }
        init();
    }

    private void favoriteSelected() {
        String v = selectedValue();
        if (v == null) {
            setStatus("先单击一条结果", true);
            init();
            return;
        }
        boolean added = CyanConfig.get().toggleFavorite(v);
        setStatus(added ? "已加入收藏" : "已取消收藏", false);
        if (tab == Tab.FAVORITE) showFavorites();
        init();
    }

    private void setStatus(String msg, boolean error) {
        this.status = msg == null ? "" : msg;
        this.statusError = error;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreenAndShow(null);
    }
}
