#!/usr/bin/env python3
"""生成应用图标（4 个设计 × 黑/白底色 = 8 套）的 Android 矢量资源。

为什么母版是脚本、不是 SVG：
- Android 的 VectorDrawable **不支持虚线**（没有 stroke-dasharray），
  2 号那三层弧在 SVG 里是虚线圆，进矢量必须算成真正的圆弧路径。
- 每个设计要出两份（黑底/白底），白底不是"换个背景色"就完事——
  amber 在白色上几乎没有对比，暗晕那层也要翻成白色。手抄两份一定会漂移。

产出：
  res/values/icon_plates.xml                       两块底色
  res/drawable/fg_appicon_<slug>_<plate>.xml       前景层（108dp 画布，图形在 66dp 安全区里）
  res/mipmap-anydpi-v26/ic_appicon_<slug>_<plate>.xml  adaptive icon（背景色 + 前景）
  kotlin/.../ui/AppIconCatalog.kt                  设计/底色枚举 + 资源与 alias 名的唯一来源
  docs/ui/icons/app/manifest-aliases.xml           贴进 AndroidManifest 的 alias 段（供对照）

用法：python3 scripts/gen_app_icons.py
"""
import math
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "android/app/src/main/res")
KT = os.path.join(ROOT, "android/app/src/main/kotlin/com/nmoi/agent/ui")
DOCS = os.path.join(ROOT, "docs/ui/icons/app")

# 组件类名按 **namespace** 解析，不是 applicationId：两者故意不同（D102）——
# 一旦按 applicationId 拼，就得到 com.NMoi.agent.icon.X，而真正装进去的类是
# com.nmoi.agent.icon.X，运行期报 "Component class ... does not exist"。
NAMESPACE = "com.nmoi.agent"

CENTER = 256.0
# adaptive icon：108dp 画布，中间 72dp 参与裁切，66dp 是"任何形状都不会切到"的安全区。
# 0.145 让 512 的图形落到约 74dp，实际图形（约 340/512）落在 49dp 左右，稳稳在安全区内。
SCALE = 0.145
TRANSLATE = 54.0 - CENTER * SCALE

PLATES = {
    "dark": {
        "bg": "#14121C",
        "violet": "#7C5CFF",
        "cyan": "#2EC5E0",
        "pink": "#FF5FA2",
        "amber": "#FFC24B",
        "veil": "#14121C",
        "core": ("#FFE3A3", "#FFC24B", "#FF5FA2"),
    },
    "light": {
        "bg": "#FFFFFF",
        # 白底上原色几乎看不见：紫压深、青转墨绿、粉提浓、琥珀转赭黄。
        "violet": "#4B2FD6",
        "cyan": "#0E7F99",
        "pink": "#D62F72",
        "amber": "#C97A00",
        "veil": "#FFFFFF",
        "core": ("#FFC46B", "#FFA51F", "#E2376F"),
    },
}

DESIGNS = [
    ("c2", "WishingStar", "许愿星 · 环轨"),
    ("c4", "TerminalStars", "许愿终端 · 星群"),
    ("c5", "InfinityRing", "无限环"),
    ("c6", "CodeStardust", "代码星屑"),
]


# --------------------------------------------------------------------- 几何
def star4(cx, cy, outer, ratio=0.26):
    o, w = outer, outer * ratio
    return (
        f"M{cx:.1f},{cy - o:.1f} "
        f"C{cx + w:.1f},{cy - w * 1.6:.1f} {cx + w * 1.6:.1f},{cy - w:.1f} {cx + o:.1f},{cy:.1f} "
        f"C{cx + w * 1.6:.1f},{cy + w:.1f} {cx + w:.1f},{cy + w * 1.6:.1f} {cx:.1f},{cy + o:.1f} "
        f"C{cx - w:.1f},{cy + w * 1.6:.1f} {cx - w * 1.6:.1f},{cy + w:.1f} {cx - o:.1f},{cy:.1f} "
        f"C{cx - w * 1.6:.1f},{cy - w:.1f} {cx - w:.1f},{cy - w * 1.6:.1f} {cx:.1f},{cy - o:.1f} Z"
    )


def arc_path(cx, cy, r, sweep_deg, start_deg):
    """按 SVG 的约定（0° 在三点钟方向、顺时针为正）算一段圆弧。"""
    a0 = math.radians(start_deg)
    a1 = math.radians(start_deg + sweep_deg)
    x0, y0 = cx + r * math.cos(a0), cy + r * math.sin(a0)
    x1, y1 = cx + r * math.cos(a1), cy + r * math.sin(a1)
    large = 1 if sweep_deg > 180 else 0
    return f"M{x0:.1f},{y0:.1f} A{r:.1f},{r:.1f} 0 {large} 1 {x1:.1f},{y1:.1f}"


def circle_path(cx, cy, r):
    return (
        f"M{cx - r:.1f},{cy:.1f} "
        f"A{r:.1f},{r:.1f} 0 1 1 {cx + r:.1f},{cy:.1f} "
        f"A{r:.1f},{r:.1f} 0 1 1 {cx - r:.1f},{cy:.1f} Z"
    )


def rrect_path(x, y, w, h, r):
    return (
        f"M{x + r:.1f},{y:.1f} L{x + w - r:.1f},{y:.1f} "
        f"A{r:.1f},{r:.1f} 0 0 1 {x + w:.1f},{y + r:.1f} "
        f"L{x + w:.1f},{y + h - r:.1f} A{r:.1f},{r:.1f} 0 0 1 {x + w - r:.1f},{y + h:.1f} "
        f"L{x + r:.1f},{y + h:.1f} A{r:.1f},{r:.1f} 0 0 1 {x:.1f},{y + h - r:.1f} "
        f"L{x:.1f},{y + r:.1f} A{r:.1f},{r:.1f} 0 0 1 {x + r:.1f},{y:.1f} Z"
    )


def lemniscate(cx, cy, a, b, n=160):
    pts = []
    for i in range(n + 1):
        t = 2 * math.pi * i / n
        ct, st = math.cos(t), math.sin(t)
        denom = 1 + st * st
        pts.append((cx + a * ct / denom, cy + b * st * ct / denom))
    return "M" + " L".join(f"{x:.1f},{y:.1f}" for x, y in pts) + " Z"


# --------------------------------------------------------------- 矢量片段
def linear(name_suffix, x0, y0, x1, y1, stops, attr):
    items = "".join(
        f'<item android:offset="{o}" android:color="{c}"/>' for o, c in stops
    )
    return (
        f'<aapt:attr name="android:{attr}">'
        f'<gradient android:type="linear" android:startX="{x0}" android:startY="{y0}" '
        f'android:endX="{x1}" android:endY="{y1}">{items}</gradient></aapt:attr>'
    )


def radial(cx, cy, r, stops, attr):
    items = "".join(
        f'<item android:offset="{o}" android:color="{c}"/>' for o, c in stops
    )
    return (
        f'<aapt:attr name="android:{attr}">'
        f'<gradient android:type="radial" android:centerX="{cx}" android:centerY="{cy}" '
        f'android:gradientRadius="{r}">{items}</gradient></aapt:attr>'
    )


def solid(color):
    return f'android:fillColor="{color}"' if color else 'android:fillColor="#00000000"'


def p(d, fill=None, fill_extra="", stroke=None, sw=0, cap="round", join="round"):
    """一个 path。

    有 `fill_extra`（渐变）时，渐变是**子元素**，标签不能自闭合——
    第一版拼成 `<path … aapt:attr…/>`，XML 直接非法，aapt2 报
    "Element type path must be followed by ... > or />"，被 mergeDebugResources 拦下。
    """
    stroke_attrs = ""
    if stroke:
        stroke_attrs = (
            f' android:strokeColor="{stroke}" android:strokeWidth="{sw}"'
            f' android:strokeLineCap="{cap}" android:strokeLineJoin="{join}"'
        )
    if fill_extra:
        return f'<path android:pathData="{d}"{stroke_attrs}>{fill_extra}</path>'
    return f'<path android:pathData="{d}" android:fillColor="{fill or "#00000000"}"{stroke_attrs}/>'


# ----------------------------------------------------------------- 各设计
def draw_c2(c):
    out = []
    for r, sweep, start, w, color in (
        (150, 100, -128, 13, c["violet"]),
        (176, 92, 24, 11, c["cyan"]),
        (202, 84, 176, 9, c["pink"]),
    ):
        out.append(p(arc_path(CENTER, CENTER, r, sweep, start), stroke=color, sw=w))
    out.append(p(circle_path(CENTER, CENTER, 112),
                 fill_extra=radial(CENTER, CENTER, 112,
                                   [(0, c["pink"] + "8C"), (1, c["pink"] + "00")],
                                   "fillColor")))
    out.append(p(star4(CENTER, CENTER, 112),
                 fill_extra=radial(CENTER, CENTER, 112,
                                   [(0, c["core"][0]), (0.45, c["core"][1]),
                                    (1, c["core"][2])], "fillColor")))
    return out


def draw_c4(c):
    # 提示符用一条渐变描边：从粉到青，比纯色更像"从一句话走到结果"。
    chevron = (
        f'<path android:pathData="M132,176 L214,256 L132,336" '
        f'android:fillColor="#00000000" android:strokeWidth="46" '
        f'android:strokeLineCap="round" android:strokeLineJoin="round">'
        + linear("c4", 132, 336, 214, 176, [(0, c["pink"]), (1, c["cyan"])], "strokeColor")
        + "</path>"
    )
    out = [
        chevron,
        p(circle_path(352, 348, 116),
          fill_extra=radial(352, 348, 116,
                            [(0, c["veil"] + "FA"), (0.72, c["veil"] + "B8"),
                             (1, c["veil"] + "00")], "fillColor")),
        p(star4(352, 348, 84),
          fill_extra=radial(352, 348, 84,
                            [(0, c["core"][0]), (0.45, c["core"][1]),
                             (1, c["core"][2])], "fillColor")),
        p(star4(322, 150, 21), fill=c["amber"]),
        p(star4(378, 112, 14), fill=c["cyan"]),
        p(star4(424, 168, 10), fill=c["pink"]),
        p(star4(276, 100, 8), fill=c["violet"]),
    ]
    return out


def draw_c5(c):
    out = [
        f'<path android:pathData="{lemniscate(CENTER, CENTER, 180, 300)}" '
        f'android:fillColor="#00000000" android:strokeWidth="40" '
        f'android:strokeLineCap="round" android:strokeLineJoin="round">'
        + linear("c5", 76, 256, 436, 256,
                 [(0, c["violet"]), (0.5, c["cyan"]), (1, c["violet"])], "strokeColor")
        + "</path>",
        p(circle_path(CENTER, CENTER, 108),
          fill_extra=radial(CENTER, CENTER, 108,
                            [(0, c["veil"] + "FA"), (0.7, c["veil"] + "C0"),
                             (1, c["veil"] + "00")], "fillColor")),
        p(star4(CENTER, CENTER, 74),
          fill_extra=radial(CENTER, CENTER, 74,
                            [(0, c["core"][0]), (0.45, c["core"][1]),
                             (1, c["core"][2])], "fillColor")),
    ]
    return out


def draw_c6(c):
    bars = [
        (110, 126, 156, 18, c["violet"]),
        (138, 172, 92, 18, c["cyan"]),
        (246, 172, 70, 18, c["pink"]),
        (138, 218, 128, 18, c["amber"]),
        (110, 264, 86, 18, c["cyan"]),
    ]
    out = [p(rrect_path(x, y, w, h, h / 2), fill=color) for x, y, w, h, color in bars]
    out += [
        p(circle_path(356, 352, 118),
          fill_extra=radial(356, 352, 118,
                            [(0, c["veil"] + "FA"), (0.72, c["veil"] + "B8"),
                             (1, c["veil"] + "00")], "fillColor")),
        p(star4(356, 352, 86),
          fill_extra=radial(356, 352, 86,
                            [(0, c["core"][0]), (0.45, c["core"][1]),
                             (1, c["core"][2])], "fillColor")),
        p(star4(300, 452, 12), fill=c["cyan"]),
        p(star4(404, 462, 9), fill=c["pink"]),
    ]
    return out


DRAW = {"c2": draw_c2, "c4": draw_c4, "c5": draw_c5, "c6": draw_c6}


def foreground_xml(slug, plate):
    body = "".join(DRAW[slug](PLATES[plate]))
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- 由 scripts/gen_app_icons.py 生成，别手改：改脚本再跑。 -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <group
        android:translateX="{TRANSLATE:.2f}"
        android:translateY="{TRANSLATE:.2f}"
        android:scaleX="{SCALE}"
        android:scaleY="{SCALE}">
        {body}
    </group>
</vector>
'''


def plate_bg_xml():
    """白底不用纯色，用"中心纯白 → 外圈淡银"的径向渐变。

    强度刻意压得很低（最外 #E4E5EC）：银感只作为材质，不要盖过前景图形。
    渐变半径取 76，是因为从中心到圆角的距离是 54√2 ≈ 76.4，再小就会在角上出现硬边。
    """
    return '''<?xml version="1.0" encoding="utf-8"?>
<!-- 由 scripts/gen_app_icons.py 生成。白底的背景层：中心纯白，往外一层很淡的银。 -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:pathData="M0,0 H108 V108 H0 Z">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="radial"
                android:centerX="54"
                android:centerY="54"
                android:gradientRadius="76">
                <item android:offset="0" android:color="#FFFFFFFF"/>
                <item android:offset="0.45" android:color="#FFFBFCFF"/>
                <item android:offset="0.78" android:color="#FFF0F1F6"/>
                <item android:offset="1" android:color="#FFE4E5EC"/>
            </gradient>
        </aapt:attr>
    </path>
</vector>
'''


def adaptive_xml(slug, plate):
    background = (
        "@color/appicon_plate_dark" if plate == "dark" else "@drawable/bg_appicon_light"
    )
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- 由 scripts/gen_app_icons.py 生成，别手改。 -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="{background}"/>
    <foreground android:drawable="@drawable/fg_appicon_{slug}_{plate}"/>
</adaptive-icon>
'''


def catalog_kt():
    design_rows = "\n".join(
        f'    {name}("{label}", "{slug}"),' for slug, name, label in DESIGNS
    )
    return f'''package com.nmoi.agent.ui

// 这个文件在 ui 子包里，R 在 com.nmoi.agent：不写全就等于 Unresolved reference 'R'（CI 抓过）。
import com.nmoi.agent.R
import androidx.annotation.DrawableRes

// 由 scripts/gen_app_icons.py 生成，别手改：设计、资源名、alias 名是同一份来源。
// 加一个设计只需要改脚本里的 DESIGNS，然后重跑，资源与这份表一起出来。

internal enum class IconDesign(val label: String, val slug: String) {{
{design_rows}
}}

internal enum class IconPlate(val label: String, val slug: String) {{
    Black("黑色", "dark"),
    White("白色", "light"),
}}

/** 默认图标：4 号（许愿终端 · 星群）+ 黑底，用户没选过就是这个。 */
internal val DEFAULT_DESIGN = IconDesign.TerminalStars
internal val DEFAULT_PLATE = IconPlate.Black

/**
 * 前景层资源。Compose 里画预览也用它——预览不是另画一套，
 * 用的是进包的同两张图（前景叠在底色上），所见即所装。
 */
@DrawableRes
internal fun IconDesign.foregroundFor(plate: IconPlate): Int = when (this) {{
    IconDesign.WishingStar -> if (plate == IconPlate.Black) R.drawable.fg_appicon_c2_dark else R.drawable.fg_appicon_c2_light
    IconDesign.TerminalStars -> if (plate == IconPlate.Black) R.drawable.fg_appicon_c4_dark else R.drawable.fg_appicon_c4_light
    IconDesign.InfinityRing -> if (plate == IconPlate.Black) R.drawable.fg_appicon_c5_dark else R.drawable.fg_appicon_c5_light
    IconDesign.CodeStardust -> if (plate == IconPlate.Black) R.drawable.fg_appicon_c6_dark else R.drawable.fg_appicon_c6_light
}}

/** activity-alias 的全名，manifest 里 android:name 必须跟它一字不差（有脚本对账）。 */
internal fun IconDesign.aliasFor(plate: IconPlate): String =
    "{NAMESPACE}.icon.${{name}}${{if (plate == IconPlate.Black) "Dark" else "Light"}}"

internal val ALL_ICON_ALIASES: List<String> =
    IconDesign.entries.flatMap {{ design -> IconPlate.entries.map {{ design.aliasFor(it) }} }}
'''


def manifest_aliases():
    rows = []
    for slug, name, label in DESIGNS:
        for plate in ("dark", "light"):
            suffix = "Dark" if plate == "dark" else "Light"
            enabled = "true" if (slug == "c4" and plate == "dark") else "false"
            rows.append(
                f'''        <!-- {label} · {"黑底" if plate == "dark" else "白底"} -->\n'''
                f'''        <activity-alias\n'''
                f'''            android:name="{NAMESPACE}.icon.{name}{suffix}"\n'''
                f'''            android:targetActivity=".MainActivity"\n'''
                f'''            android:icon="@mipmap/ic_appicon_{slug}_{plate}"\n'''
                f'''            android:exported="true"\n'''
                f'''            android:enabled="{enabled}">\n'''
                f'''            <intent-filter>\n'''
                f'''                <action android:name="android.intent.action.MAIN" />\n'''
                f'''                <category android:name="android.intent.category.LAUNCHER" />\n'''
                f'''            </intent-filter>\n'''
                f'''        </activity-alias>'''
            )
    return "\n".join(rows)


def main():
    for d in (RES + "/drawable", RES + "/mipmap-anydpi-v26", RES + "/values", KT, DOCS):
        os.makedirs(d, exist_ok=True)

    plates = '<?xml version="1.0" encoding="utf-8"?>\n<!-- 由 scripts/gen_app_icons.py 生成。 -->\n<resources>\n'
    plates += '    <color name="appicon_plate_dark">#14121C</color>\n'
    plates += '    <!-- 白底不走纯色：见 res/drawable/bg_appicon_light.xml（中心白 → 外圈淡银的渐变） -->\n'
    plates += "</resources>\n"
    open(RES + "/values/icon_plates.xml", "w", encoding="utf-8").write(plates)

    open(RES + "/drawable/bg_appicon_light.xml", "w", encoding="utf-8").write(plate_bg_xml())

    count = 0
    for slug, _, _ in DESIGNS:
        for plate in ("dark", "light"):
            open(f"{RES}/drawable/fg_appicon_{slug}_{plate}.xml", "w", encoding="utf-8").write(
                foreground_xml(slug, plate)
            )
            open(
                f"{RES}/mipmap-anydpi-v26/ic_appicon_{slug}_{plate}.xml",
                "w",
                encoding="utf-8",
            ).write(adaptive_xml(slug, plate))
            count += 2

    open(KT + "/AppIconCatalog.kt", "w", encoding="utf-8").write(catalog_kt())
    open(DOCS + "/manifest-aliases.xml", "w", encoding="utf-8").write(
        "<!-- 由 scripts/gen_app_icons.py 生成：贴进 AndroidManifest.xml 的 <application> 里 -->\n"
        + manifest_aliases()
        + "\n"
    )
    print(f"写出 {count} 个矢量/自适应图标、1 份 Kotlin 目录、1 段 manifest alias")


if __name__ == "__main__":
    main()
