package top.blueworkshop.cyancodex;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 共享日志（放在 shared 里，避免共享代码反向依赖某个版本分支的入口类）。
 */
public final class CyanLog {
    public static final String MOD_ID = "cyancodex";
    public static final Logger LOGGER = LoggerFactory.getLogger("CyanCodex");

    private CyanLog() {
    }
}
