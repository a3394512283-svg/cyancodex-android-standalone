package com.canglan.cyancodex.plugin;

import android.content.Context;

/** 编译期桩: 与 App 内 Kotlin 接口 CyanPlugin 字节码签名一致 */
public interface CyanPlugin {
    String getId();
    String getName();
    String getVersion();
    String getDescription();
    void onEnable(Context context);
    void onDisable();
}
