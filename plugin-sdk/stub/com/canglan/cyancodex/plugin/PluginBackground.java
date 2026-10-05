package com.canglan.cyancodex.plugin;

/** 编译期桩: 与 App 内 Kotlin data class PluginBackground 一致 */
public final class PluginBackground {
    private final String kind;
    private final String colorTop;
    private final String colorBottom;

    public PluginBackground(String kind, String colorTop, String colorBottom) {
        this.kind = kind;
        this.colorTop = colorTop;
        this.colorBottom = colorBottom;
    }

    public String getKind() { return kind; }
    public String getColorTop() { return colorTop; }
    public String getColorBottom() { return colorBottom; }
}
