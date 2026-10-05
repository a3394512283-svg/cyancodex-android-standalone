package dev.cyancodex.blocks;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.widget.Toast;

import com.canglan.cyancodex.plugin.BackgroundProvider;
import com.canglan.cyancodex.plugin.CyanPlugin;
import com.canglan.cyancodex.plugin.PluginBackground;
import com.canglan.cyancodex.plugin.SpecPlugin;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * CyanCodex「插件工坊」生成的插件运行时
 *
 * 说明: 工坊里用积木拼出来的插件, 打包时共用这一个实现类 (classes.dex),
 * 每个插件真正的差异放在 JAR 内的 cyancodex-plugin.json (行为规格) 里,
 * 加载时由 PluginManager 通过 SpecPlugin.attachSpec 注入。
 *
 * 好处: 完全离线、零编译依赖、任何 Android 版本都能生成可用的插件 JAR。
 */
public class GeneratedPlugin implements CyanPlugin, BackgroundProvider, SpecPlugin {

    private static final String TAG = "CyanPlugin";

    private JSONObject spec = new JSONObject();
    private Context appContext = null;

    /** PluginManager 在实例化之后、读取 id 之前调用 */
    @Override
    public void attachSpec(String specJson) {
        try {
            spec = new JSONObject(specJson == null ? "{}" : specJson);
        } catch (Throwable t) {
            Log.w(TAG, "attachSpec: 规格 JSON 解析失败", t);
            spec = new JSONObject();
        }
    }

    private String str(String key, String def) {
        String v = spec.optString(key, def);
        return (v == null || v.length() == 0) ? def : v;
    }

    @Override public String getId() { return str("id", "dev.cyancodex.blocks.unnamed"); }
    @Override public String getName() { return str("name", "积木插件"); }
    @Override public String getVersion() { return str("version", "1.0.0"); }
    @Override public String getDescription() { return str("description", "由插件工坊生成的插件"); }

    @Override
    public void onEnable(Context context) {
        appContext = context;
        runActions(spec.optJSONArray("onEnable"), context);
        Log.i(TAG, "[" + getId() + "] onEnable: 积木插件已启用");
    }

    @Override
    public void onDisable() {
        runActions(spec.optJSONArray("onDisable"), appContext);
        Log.i(TAG, "[" + getId() + "] onDisable: 积木插件已禁用");
    }

    /** 按规格顺序执行积木动作 */
    private void runActions(JSONArray actions, Context ctx) {
        if (actions == null) return;
        for (int i = 0; i < actions.length(); i++) {
            JSONObject a = actions.optJSONObject(i);
            if (a == null) continue;
            String type = a.optString("type", "");
            try {
                if ("log".equals(type)) {
                    Log.i(TAG, "[" + getId() + "] " + a.optString("message", ""));
                } else if ("toast".equals(type)) {
                    if (ctx != null) Toast.makeText(ctx, a.optString("message", ""), Toast.LENGTH_SHORT).show();
                } else if ("setPref".equals(type)) {
                    if (ctx != null) prefs(ctx).edit().putString(a.optString("key", "k"), a.optString("value", "")).apply();
                } else if ("clearPref".equals(type)) {
                    if (ctx != null) prefs(ctx).edit().remove(a.optString("key", "k")).apply();
                } else if ("logPref".equals(type)) {
                    if (ctx != null) {
                        String key = a.optString("key", "k");
                        String v = prefs(ctx).getString(key, "(未设置)");
                        Log.i(TAG, "[" + getId() + "] " + a.optString("label", key + " = ") + v);
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "积木动作执行失败: " + type, t);
            }
        }
    }

    /** 插件的配置存储 (每个插件独立) */
    private SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("plugin_" + getId(), Context.MODE_PRIVATE);
    }

    /** 背景积木: 返回 null 表示不改背景 */
    @Override
    public PluginBackground background() {
        JSONObject bg = spec.optJSONObject("background");
        if (bg == null || !bg.optBoolean("enabled", false)) return null;
        String kind = bg.optString("kind", "gradient");
        String top = bg.optString("colorTop", "#0B1A14");
        String bottom = bg.optString("colorBottom", "#10352B");
        if ("color".equals(kind)) bottom = top;
        return new PluginBackground(kind, top, bottom);
    }
}
