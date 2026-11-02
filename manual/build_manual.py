# -*- coding: utf-8 -*-
"""生成《标签复刻 App 使用手册》单文件 HTML：截图压缩成 JPEG 后 base64 内嵌。"""
import base64
import io
import os

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
IMG = os.path.join(HERE, "img")
OUT = os.path.join(HERE, "标签复刻App使用手册.html")


def embed(name, width=520, quality=84):
    p = os.path.join(IMG, name)
    im = Image.open(p).convert("RGB")
    if im.width > width:
        h = int(im.height * width / im.width)
        im = im.resize((width, h), Image.LANCZOS)
    buf = io.BytesIO()
    im.save(buf, "JPEG", quality=quality, optimize=True)
    b64 = base64.b64encode(buf.getvalue()).decode()
    return "data:image/jpeg;base64," + b64, len(buf.getvalue())


SHOTS = {
    "camera": ("01_camera.png", "拍照页：顶部点选模板，中间取景框，下面是拍照/相册"),
    "quad": ("04_quad.png", "四点标定页：四个圆点默认内缩 8%，可点图放点或拖动微调"),
    "mag": ("05_magnifier.png", "拖动圆点时自动浮出 3× 放大镜（青色十字 + 白靶心）"),
    "result": ("02_result.png", "结果页：白纸预览上叠加检出框，下面是要素列表"),
    "elements": ("03_elements.png", "要素列表向下滚：文字、框线都在列表里，可逐个改/删"),
    "edit": ("09_edit.png", "编辑要素：改码制、改内容"),
    "printer": ("06_printer.png", "打印机页上半：标签纸尺寸、连接状态与三个操作按钮"),
    "ble": ("07_printer_ble.png", "打印机页下半：BLE 扫描、启动自动连、测试页与校准"),
}

imgs = {}
sizes = {}
for k, (f, cap) in SHOTS.items():
    try:
        imgs[k], sizes[k] = embed(f)
    except FileNotFoundError:
        imgs[k] = ""
print("embedded:", {k: round(v / 1024) for k, v in sizes.items()})

CSS = """
:root{
  --ink:#0b0d0b; --panel:#141814; --panel2:#1a1f1a; --line:#2a312a;
  --fg:#eef2ea; --dim:#a8afe5; --dim2:#9aa79a; --lime:#a8f932; --lime2:#86c727;
}
*{box-sizing:border-box}
body{margin:0;background:var(--ink);color:var(--fg);
  font:16px/1.75 -apple-system,"Segoe UI","Microsoft YaHei",system-ui,sans-serif}
.wrap{max-width:1000px;margin:0 auto;padding:0 22px 90px}
header{padding:64px 0 34px;border-bottom:1px solid var(--line);margin-bottom:38px}
h1{margin:0 0 10px;font-size:34px;letter-spacing:-.5px}
.sub{color:var(--dim2);font-size:15px}
.badge{display:inline-block;margin-left:10px;padding:2px 10px;border-radius:20px;
  background:rgba(168,249,50,.14);color:var(--lime);font-size:13px;font-weight:600;
  vertical-align:middle}
h2{margin:56px 0 16px;font-size:24px;padding-left:12px;border-left:4px solid var(--lime)}
h3{margin:34px 0 10px;font-size:18px;color:var(--lime2)}
p{margin:10px 0}
code{background:#20261f;padding:1px 6px;border-radius:4px;font-size:14px;
  font-family:ui-monospace,Consolas,monospace;color:#d8f0b0}
.card{background:var(--panel);border:1px solid var(--line);border-radius:14px;
  padding:18px 20px;margin:16px 0}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:16px}
.row{display:grid;grid-template-columns:repeat(auto-fit,minmax(300px,1fr));gap:14px;align-items:start}
figure{margin:22px 0;background:var(--panel);border:1px solid var(--line);
  border-radius:14px;padding:14px;text-align:center}
figure img{width:100%;max-width:340px;border-radius:9px;display:block;margin:0 auto}
figcaption{margin-top:12px;color:var(--dim2);font-size:14px;text-align:left;
  padding:0 4px;line-height:1.6}
table{width:100%;border-collapse:collapse;margin:16px 0;font-size:15px}
th,td{text-align:left;padding:9px 12px;border-bottom:1px solid var(--line);vertical-align:top}
th{color:var(--lime2);font-weight:600;white-space:nowrap}
tbody tr:hover{background:rgba(168,249,50,.04)}
ol.steps{counter-reset:s;list-style:none;padding:0;margin:18px 0}
ol.steps>li{counter-increment:s;position:relative;padding:14px 16px 14px 58px;
  background:var(--panel);border:1px solid var(--line);border-radius:12px;margin-bottom:12px}
ol.steps>li::before{content:counter(s);position:absolute;left:16px;top:13px;
  width:28px;height:28px;border-radius:50%;background:var(--lime);color:#0b0d0b;
  font-weight:700;display:grid;place-items:center;font-size:15px}
ol.steps b{color:var(--lime)}
.toc{background:var(--panel2);border:1px solid var(--line);border-radius:14px;padding:16px 22px}
.toc a{color:var(--fg);text-decoration:none;border-bottom:1px dashed transparent}
.toc a:hover{color:var(--lime);border-bottom-color:var(--lime)}
.toc ol{margin:8px 0;padding-left:22px}
.kbd{display:inline-block;padding:1px 7px;border:1px solid var(--line);
  border-bottom-width:2px;border-radius:5px;font-size:13px;color:var(--dim2)}
.note{border-left:3px solid var(--lime);background:rgba(168,249,50,.06);
  padding:12px 16px;border-radius:0 8px 8px 0;margin:16px 0;font-size:15px}
.warn{border-left-color:#ffb454;background:rgba(255,180,84,.07)}
.flow{display:flex;flex-wrap:wrap;gap:10px;align-items:center;margin:18px 0;font-size:14px}
.flow span{background:var(--panel2);border:1px solid var(--line);border-radius:20px;
  padding:6px 14px}
.flow i{color:var(--lime);font-style:normal}
footer{margin-top:70px;padding-top:22px;border-top:1px solid var(--line);
  color:var(--dim2);font-size:13px}
@media print{body{background:#fff;color:#111}figure,.card{break-inside:avoid}}
"""

HTML = f"""<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>标签复刻 App 使用手册 · V2.2</title>
<style>{CSS}</style></head><body><div class="wrap">

<header>
  <h1>标签复刻 App 使用手册<span class="badge">V2.2</span></h1>
  <div class="sub">拍一张标签照片 → 四点标定 → 自动识别条码/文字/框线 → 生成 ZPL → 蓝牙打印<br>
  适用机型：Zebra TC201（Android 15，1080×2160）· 打印机 ZR668（203dpi）</div>
</header>

<div class="toc">
<b>目录</b>
<ol>
<li><a href="#what">它做什么</a></li>
<li><a href="#quick">五步上手</a></li>
<li><a href="#ui">界面逐页说明</a></li>
<li><a href="#algo">识别原理（框线为什么难）</a></li>
<li><a href="#spec">参数与规格</a></li>
<li><a href="#faq">常见问题</a></li>
<li><a href="#ver">版本变化</a></li>
</ol>
</div>

<h2 id="what">1. 它做什么</h2>
<p>给一张已有标签的照片，App 把上面的内容"读出来"重新排成 ZPL，让斑马打印机打出一张新的。
<b>不是识别文字抄下来，而是复刻版面</b>：表格线、条码位置、文字字号都要还原。</p>

<div class="flow">
<span>拍照</span><i>→</i><span>四点标定</span><i>→</i><span>透视矫正</span><i>→</i>
<span>条码 + OCR + 框线</span><i>→</i><span>ZPL</span><i>→</i><span>打印</span>
</div>

<div class="note">识别结果分三类要素：<b>一维码/二维码</b>、<b>文字</b>、<b>框线</b>。
结果页里每条都能单独改内容、改码制或删除，改完 ZPL 立即跟着变。</div>

<h2 id="quick">2. 五步上手</h2>
<ol class="steps">
<li><b>对准拍摄</b>：把标签四边放进绿色取景框，尽量占满画面。底部点<span class="kbd">拍照识别</span>；
已拍好的照片走右侧<span class="kbd">相册</span>。</li>
<li><b>点四个角</b>：进入标定页后，<b>按 ①→②→③→④ 顺序依次点图片上标签的四个角</b>，
点错了就拖动圆点微调——拖的时候旁边会浮出放大镜，看得清有没有压线。</li>
<li><b>矫正并识别</b>：点右下绿色按钮。App 先把斜拍的照片拉成正视图，再识别。</li>
<li><b>核对结果</b>：白纸预览上会用彩色框标出识别到的位置；下面列表逐条列出要素。
有错就点<span class="kbd">改</span>修正，多余的点<span class="kbd">✕</span>删掉。</li>
<li><b>打印</b>：切到底部<span class="kbd">打印机</span>选设备（老机走经典蓝牙 SPP，ZD421 这类走 BLE），
首次建议先打一张测试页。</li>
</ol>

<div class="note warn"><b>易错点</b>：标定页不是"拖住小圆点才能动"，而是<b>点图片任意位置就放一个角点</b>。
只拖一个角、其余三个不动，出来的就不是你要的区域——必须四个角都落到位。</div>

<h2 id="ui">3. 界面逐页说明</h2>

<h3>3.1 拍照页</h3>
<figure><img src="{imgs['camera']}" alt="拍照页"><figcaption>{SHOTS['camera'][1]}</figcaption></figure>
<table>
<tbody>
<tr><th>模板 chip</th><td>决定 ZPL 画布尺寸。<code>模板1</code> 60×40、<code>模板2</code> 76×70、<code>自定义</code> 在打印机页填宽高。识别前选好，识别后再切换会按新尺寸重排。</td></tr>
<tr><th>取景框</th><td>只是参考线和"对齐提示"，<b>不裁像素</b>；识别范围由第 2 步的四点决定。</td></tr>
<tr><th>拍照识别</th><td>调系统相机拍一张，立刻进标定页。</td></tr>
<tr><th>相册</th><td>选已有照片（现场补拍不用跑回机器）。</td></tr>
<tr><th>跑内置样图（自检）</th><td>不接相机也能验证整条链路，排查"是相机问题还是算法问题"。</td></tr>
</tbody></table>

<h3>3.2 四点标定页</h3>
<div class="row">
<figure><img src="{imgs['quad']}" alt="四点标定"><figcaption>{SHOTS['quad'][1]}</figcaption></figure>
<figure><img src="{imgs['mag']}" alt="放大镜"><figcaption>{SHOTS['mag'][1]}</figcaption></figure>
</div>
<table>
<tbody>
<tr><th>① → ④ 点按</th><td>点一下图片放一个角点，顺序是左上、右上、右下、左下；点满四个后第 5 下回到 ①。</td></tr>
<tr><th>拖圆点</th><td>微调。拖动时会校验四点是否交叉/凹陷，非法会提示"请重新点"，不会白算一次透视。</td></tr>
<tr><th>放大镜</th><td>132dp 方形视窗、3× 放大，直接从原图裁局部（不是放大屏幕像素），靠近顶部会自动翻到下方。</td></tr>
<tr><th>自动预填</th><td>让算法自己找标签轮廓。<b>背景复杂时不靠谱</b>（纸箱场景会框到整个画面），所以默认不自动跑，只留按钮。</td></tr>
<tr><th>重置四角</th><td>回到"四边内缩 8%"的默认框，重新点。</td></tr>
<tr><th>矫正并识别</th><td>透视变换 → 条码 → OCR → 框线 → 排版 → ZPL，约 8~12 秒（含 OCR）。</td></tr>
</tbody></table>

<h3>3.3 结果页</h3>
<figure><img src="{imgs['result']}" alt="结果页"><figcaption>{SHOTS['result'][1]}</figcaption></figure>
<p>预览上的框色含义：<b>黄框</b>=框线（表格线/外框）、<b>绿框</b>=条码、<b>灰框</b>=文字。
"完成：N 个要素，M 个错误"里，<b>错误</b>指放不下/数据不合规这类必须处理的问题，正常是 0。</p>
<table>
<tbody>
<tr><th>模板</th><td>三个 chip 可随时切换，预览与 ZPL 同步重排。</td></tr>
<tr><th>含文字 OCR / 仅条码</th><td>只想复刻条码内容时切"仅条码"，省时间也不会带入错字。</td></tr>
<tr><th>基准=label</th><td>表示位置以整张标签为基准（另有点位基准，用于只贴小区域的场景）。</td></tr>
<tr><th>重新识别 / 调四角 / 重拍</th><td>顶部三个按钮对应三种返工：换算法参数、重标四点、重新拍。</td></tr>
</tbody></table>

<figure><img src="{imgs['elements']}" alt="要素列表"><figcaption>{SHOTS['elements'][1]}</figcaption></figure>

<h3>3.4 编辑要素</h3>
<figure><img src="{imgs['edit']}" alt="编辑要素"><figcaption>{SHOTS['edit'][1]}</figcaption></figure>
<p>点列表里的<span class="kbd">改</span>弹出此窗。上面是码制（Code 128 / Code 39 / EAN-13 / 二维码…），下面是内容。
<b>条码的数据原样下发给打印机</b>，App 不做补位、不算校验位、不改大小写——要改就改这里的原文。</p>
<div class="note">唯一例外：EAN-13 / UPC-A / 交叉25 这类只收数字的制式，会在识别阶段就把非数字字符过滤掉并给出警告，
保证"预览 = 日志 = 下发给打印机"三者一致。</div>

<h3>3.5 打印机页</h3>
<div class="row">
<figure><img src="{imgs['printer']}" alt="打印机页"><figcaption>{SHOTS['printer'][1]}</figcaption></figure>
<figure><img src="{imgs['ble']}" alt="打印机页下半"><figcaption>{SHOTS['ble'][1]}</figcaption></figure>
</div>
<table>
<tbody>
<tr><th>标签纸尺寸</th><td>按<b>整张纸</b>的实际毫米数填（不是可打印宽）。203dpi 下 8 点/mm：76mm → <code>^PW608</code>。</td></tr>
<tr><th>经典蓝牙 SPP</th><td>要在系统蓝牙里先配对。ZR668 / ZT 系列走这条。</td></tr>
<tr><th>低功耗蓝牙 BLE</th><td>不用配对，点"扫描附近打印机"。ZD421 这类只开 BLE 的机器走这条。</td></tr>
<tr><th>启动时自动连这台</th><td>车间每天开机就打印的场景，省掉每次重连。</td></tr>
<tr><th>打印测试页</th><td>验通道。纸张出来但内容不对，先看尺寸是不是填错。</td></tr>
<tr><th>校准（换纸后）</th><td>下发 <code>~jc^xa^jus^xz</code>，让打印机重新测纸。</td></tr>
</tbody></table>
<div class="note warn">同一条蓝牙链路同一时间只能被一台手机占用。BLE 打印机被别的手机连着时，这里会一直连不上——
先把那台手机的 App 断开。</div>

<h2 id="algo">4. 识别原理（框线为什么难）</h2>
<p>条码和文字靠现成引擎（Zebra AI Data Capture + PP-OCR），<b>框线只能自己算</b>：
它是几条细直线，斜一点就"消失"。</p>

<div class="card">
<p><b>问题：透视矫正之后，印刷表格线仍然斜 2~6°。</b></p>
<p>一条 8 点厚、1600 像素宽的横线斜 3°，在单独一行里只剩约 <code>8/sin3° ≈ 150px</code> 连续墨迹——
只占整宽的 9%。旧算法判据是"某一行最长墨迹段 ≥22% 图宽"，于是 4 条表格线全漏；
反过来，纸箱、托盘那些连续暗行满足条件，纸上打出一堆乱框。<b>实测旧版一张图只出 1~2 条错线，要素数多达 71 个。</b></p>
</div>

<h3>现在的做法（Hough 找线）</h3>
<table>
<tbody>
<tr><th>① 取墨迹</th><td>自适应阈值（局部均值 −10），抗反光抗阴影，比全局阈值稳。</td></tr>
<tr><th>② Hough 累加</th><td>在 (±10°, 0.5° 步长) 角度上累加 <code>rho = y − tanθ·x</code>。
同一根线无论斜多少、中间断多少，都会汇到同一个峰上。横线和竖线<b>分开累加</b>（同一张图左竖框 −7.3°、右竖框 +4.9°）。</td></tr>
<tr><th>③ 定端点</th><td>每个峰按 rho 收像素，取<b>最长连续段</b>当线段。不用分位数——条码、反光、纸边这些孤立墨点会把跨度拉宽两三百像素。</td></tr>
<tr><th>④ 校验</th><td>沿线段垂直方向采样：线芯要比两侧暗 ≥12，两侧都要亮于本图纸面亮度。
纸边/折痕的特征是"线芯并不比两侧暗"，正好被这条挡掉。</td></tr>
<tr><th>⑤ 聚成表格</th><td>按中线位置合并（同一根线被不同角度检出时 rho 会差二十几像素，只比 rho 合不掉，一条线会变三条），
再聚簇、剔掉贴图纸边缘的那条、补齐缺失的左右边框。</td></tr>
<tr><th>⑥ 排除条码区</th><td>条码上下沿是又长又黑的假横线，与条码框重叠超 50% 的线一律剔除。</td></tr>
</tbody></table>

<h3>实测数据（14 张不同背景的现场照片）</h3>
<table>
<thead><tr><th>结果</th><th>张数</th><th>说明</th></tr></thead>
<tbody>
<tr><td>精确 4 横 + 2 竖（与图纸真值一致）</td><td>11</td><td>纸箱、木桌、托盘、白纸各种背景，单张耗时 420~590ms</td></tr>
<tr><td>漏线</td><td>2</td><td>自动预填四点把标签截断，标定范围本身不含那几条线</td></tr>
<tr><td>无线</td><td>1</td><td>自动预填框到整个场景；此时返回空，<b>不会</b>退化成乱框</td></tr>
</tbody></table>
<div class="note">漏线/无线的三张都出自<b>自动预填</b>，手动四点标定不受影响。
这也是"自动预填只保留按钮、不默认调用"的原因。</div>

<h2 id="spec">5. 参数与规格</h2>
<table>
<thead><tr><th>项目</th><th>值</th><th>备注</th></tr></thead>
<tbody>
<tr><td>默认纸张</td><td>76 × 70 mm</td><td>按整张纸算，203dpi 8 点/mm → <code>^PW608</code> / <code>^LL560</code></td></tr>
<tr><td>模板1</td><td>60 × 40 mm</td><td>—</td></tr>
<tr><td>模板3（自定义）</td><td>打印机页填宽高</td><td>写进模板表本身，预览/排版/画布全线跟随</td></tr>
<tr><td>框线厚度</td><td>统一 5 点（0.63mm）</td><td>3 点打出来发虚，下张照片更难检出，会形成"细线→更难点"的循环</td></tr>
<tr><td>EAN-13</td><td><code>^BE</code>，95 模块</td><td>按 Code128 的 11n+35 估宽会把模块压小 15%，扫不出</td></tr>
<tr><td>Code 128 / 39 / QR</td><td><code>^BC</code> / <code>^B3</code> / <code>^BQ</code></td><td>QR 放大倍数 6 是工业扫码枪的稳妥值</td></tr>
<tr><td>中文字体</td><td><code>E:SIMSUN.TTF</code> + <code>^CI28</code></td><td>ZPL 内置字库没有汉字</td></tr>
<tr><td>识别耗时</td><td>约 8~12 秒</td><td>条码 ≈1.6s、OCR ≈0.9s、框线 ≈0.5s，其余是引擎初始化</td></tr>
</tbody></table>

<h2 id="faq">6. 常见问题</h2>
<table>
<thead><tr><th>现象</th><th>原因</th><th>处理</th></tr></thead>
<tbody>
<tr><td>拍照页底部提示"文件读不到"</td><td>Android 分区存储不允许读 <code>/sdcard/Download</code></td><td>用相册选图；自动化测试把文件推到 App 自己的 <code>files</code> 目录</td></tr>
<tr><td>校正出来的不是我要的区域</td><td>只拖了一个角，其余三个还是默认框</td><td>四个角都点到位（①→④），再点"矫正并识别"</td></tr>
<tr><td>框线少一条 / 多一条</td><td>少：那张被反光截断或贴纸边；多：条码上下沿或纸边被当线</td><td>先在结果页点 <span class="kbd">✕</span> 删多余；反复出现就回到"调四角"重新标定</td></tr>
<tr><td>自动预填框到整个场景</td><td>背景是纸箱/托盘，白纸边界被背景干扰</td><td>手动点四个角，不要用自动预填</td></tr>
<tr><td>条码打得扫不出</td><td>码制发错（EAN-13 被当 Code128 估宽）或模块宽被压小</td><td>结果页点"改"确认码制；别手动改内容位数</td></tr>
<tr><td>打印出来文字重合/糊</td><td>字号按 ASCII 比值算，汉字实际占 1 em 宽，溢出后打印机把字挤着叠印</td><td>缩短该行文字，或换更宽的模板</td></tr>
<tr><td>经典蓝牙连不上（read failed）</td><td>打印机是 BLE 配对，没有 SPP 串口服务记录</td><td>改用 BLE 通道；或让打印机把 <code>bluetooth.le.controller_mode</code> 设为 both/classic 后重配</td></tr>
<tr><td>BLE 扫描不到任何设备</td><td>缺 <code>BLUETOOTH_SCAN</code> 权限（不报错，静默无回调）</td><td>在系统设置里给 App 蓝牙权限；或用"手填 MAC"直连</td></tr>
<tr><td>打印"成功"但纸上没内容</td><td>没连打印机时是干跑；或上次的作业还挂在队列里</td><td>先打测试页确认通道；验收要拍新出纸的照片比对</td></tr>
</tbody></table>

<h2 id="ver">7. 版本变化</h2>
<table>
<thead><tr><th>版本</th><th>变化</th></tr></thead>
<tbody>
<tr><td>V2.2</td><td>四点标定支持"点图片放角点"①→④；拖动时浮出 3× 放大镜；四点几何自检（交叉/凹陷直接拦）；
<b>框线识别改用 Hough</b>（14 张现场图 11 张精确 4 横 2 竖，耗时从 14.8s 降到 0.5s）；条码区排除防止假横线</td></tr>
<tr><td>V2.0</td><td>框线成为一等要素（<code>^GB</code>）；EAN-13 改发 <code>^BE</code>；文字基线校准；纸张按整张纸 76×70；模板3 自定义尺寸进设置页</td></tr>
<tr><td>V1.x</td><td>条码 + OCR + 自动排版 + SPP/BLE 双通道打印</td></tr>
</tbody></table>

<footer>
本手册截图取自 Zebra TC201（1080×2160）实机，版本 V2.2 / versionCode 22。<br>
工程：<code>E:\\workspace\\label-reprint-android</code> · 生成时间：2026-10-30
</footer>
</div></body></html>
"""

with open(OUT, "w", encoding="utf-8") as f:
    f.write(HTML)
print("written:", OUT, round(os.path.getsize(OUT) / 1024 / 1024, 2), "MB")
