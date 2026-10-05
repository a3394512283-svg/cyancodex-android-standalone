# -*- coding: utf-8 -*-
"""构建内置插件 JAR (javac -> d8 -> zip)
用法: python build-plugins.py

路径解析(不再硬编码机器路径):
  ANDROID_HOME / ANDROID_SDK_ROOT -> Android SDK (默认 ~/AppData/Local/Android/Sdk)
  JAVA_HOME                       -> JDK, 需含 bin/javac 与 bin/jar

产物:
  android/app/src/main/assets/plugins/background.jar   自定义背景插件
  android/app/src/main/assets/plugins/dev.jar          开发者示例插件
  android/app/src/main/assets/plugin_template.jar      插件工坊运行时模板 (不进 plugins 目录, 避免被当成空插件加载)
"""
import os
import shutil
import subprocess
import sys
import zipfile

_HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.environ.get('CYANCODEX_PLUGIN_SRC', _HERE)
OUT = os.path.join(ROOT, 'build')
STUB_OUT = os.path.join(OUT, 'stub')
CLASSES_OUT = os.path.join(OUT, 'classes')

_SDK = (os.environ.get('ANDROID_HOME')
        or os.environ.get('ANDROID_SDK_ROOT')
        or os.path.join(os.path.expanduser('~'), 'AppData', 'Local', 'Android', 'Sdk'))
_JAVA_BIN = os.path.join(os.environ['JAVA_HOME'], 'bin') if os.environ.get('JAVA_HOME') else ''

JAVAC = os.path.join(_JAVA_BIN, 'javac.exe') if _JAVA_BIN else 'javac'
ANDROID_JAR = os.path.join(_SDK, 'platforms', 'android-36', 'android.jar')
D8 = os.path.join(_SDK, 'build-tools', '36.1.0', 'd8.bat')
ASSETS = os.path.join(_HERE, '..', 'android', 'app', 'src', 'main', 'assets')
SERVICE = 'META-INF/services/com.canglan.cyancodex.plugin.CyanPlugin'
MANIFEST = 'Manifest-Version: 1.0\r\nCreated-By: CyanCodex plugin builder\r\n\r\n'

PLUGINS = [
    # (名字, 实现类, 类文件路径片段, 输出相对路径)
    ('background', 'dev.cyancodex.plugins.background.BackgroundPlugin', r'plugins\background\\', 'plugins/background.jar'),
    ('dev', 'dev.cyancodex.plugins.dev.DevExamplePlugin', r'plugins\dev\\', 'plugins/dev.jar'),
    ('builder', 'dev.cyancodex.blocks.GeneratedPlugin', r'blocks\\GeneratedPlugin', 'plugin_template.jar'),
]


def run(cmd, note):
    print('>>', note)
    p = subprocess.run(cmd, capture_output=True)
    out = (p.stdout or b'').decode('utf-8', 'replace') + (p.stderr or b'').decode('utf-8', 'replace')
    if out.strip():
        for line in out.strip().splitlines():
            if 'Warning' in line or 'error' in line.lower():
                print('   ', line.strip()[:160])
    if p.returncode != 0:
        print('FAILED:', note)
        print(out[-2500:])
        sys.exit(1)


def javas(sub):
    files = []
    for base, _, names in os.walk(os.path.join(ROOT, sub)):
        for n in names:
            if n.endswith('.java'):
                files.append(os.path.join(base, n))
    return files


def main():
    if not os.path.exists(ANDROID_JAR):
        print('ERROR: android.jar missing')
        return 1
    shutil.rmtree(STUB_OUT, ignore_errors=True)
    shutil.rmtree(CLASSES_OUT, ignore_errors=True)
    os.makedirs(STUB_OUT, exist_ok=True)
    os.makedirs(CLASSES_OUT, exist_ok=True)
    os.makedirs(os.path.join(ASSETS, 'plugins'), exist_ok=True)

    # 1) 接口桩
    run([JAVAC, '--release', '8', '-Xlint:-options', '-cp', ANDROID_JAR, '-d', STUB_OUT] + javas('stub'), '编译接口桩')
    # 2) 插件本体
    cp = STUB_OUT + ';' + ANDROID_JAR
    for sub, label in [('background', '自定义背景插件'), ('dev', '开发者示例插件'), ('builder', '插件工坊运行时')]:
        run([JAVAC, '--release', '8', '-Xlint:-options', '-cp', cp, '-d', CLASSES_OUT] + javas(sub), '编译 ' + label)

    # 3) d8 -> dex -> jar
    for name, impl, pattern, rel in PLUGINS:
        tmp = os.path.join(OUT, name)
        dex_out = os.path.join(tmp, 'dex')
        shutil.rmtree(tmp, ignore_errors=True)
        os.makedirs(dex_out, exist_ok=True)
        classes = []
        for base, _, names in os.walk(CLASSES_OUT):
            for n in names:
                if n.endswith('.class'):
                    full = os.path.join(base, n)
                    if pattern.replace('\\\\', '\\') in full:
                        classes.append(full)
        if not classes:
            print('ERROR: no class matched', name, pattern)
            return 1
        run([D8, '--release', '--lib', ANDROID_JAR, '--output', dex_out] + classes, 'd8 ' + name)
        dex = os.path.join(dex_out, 'classes.dex')
        if not os.path.exists(dex):
            print('ERROR: d8 produced no classes.dex for', name)
            return 1
        dest = os.path.join(ASSETS, rel.replace('/', os.sep))
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        with zipfile.ZipFile(dest, 'w', zipfile.ZIP_DEFLATED) as z:
            z.writestr('META-INF/MANIFEST.MF', MANIFEST)
            z.writestr(SERVICE, impl)
            z.write(dex, 'classes.dex')
        print('built %-28s %6d bytes  (%s)' % (rel, os.path.getsize(dest), impl))
    print('BUILD_PLUGINS_DONE')
    return 0


if __name__ == '__main__':
    sys.exit(main())
