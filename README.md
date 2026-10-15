# 标签复刻机 label-reprint-android

**当前版本：V1.1（2026-10-15）** — 版本记录见 `docs/V1.0-版本记录.md`、`docs/V1.1-版本记录.md`；
封版回归 `python tools/regress.py`（退出码 0 = 通过）。

拍照 ↓ 识别 ↓ 重新生成 ZPL ↓ 打印。目标是「看到一张标签，把它重新打出来」。

```
照片 ──► 标定基准(label/full) ──► 条码识别 ──┐
                                    文字 OCR ──┴─► 元素表 ──► 版面映射 ──► ZPL ──► 打印/导出
```

## 条码识别引擎（调研结论）

| 方案 | 性质 | 结论 |
|---|---|---|
| Zebra AI Data Capture SDK `barcode-decoder` 5.0.4 | 真·DL（检测+解码一体，旋转框） | **首选**。已在本机 `D:\AISuite_Android_Samples\models` 与 `~/.m2`；官方免费永久授权；仅限 Zebra TC53/58/73/78、ET60/65、EM45、MC9400/9450（Android 14+） |
| zxing-cpp `io.github.zxing-cpp:android:3.1.1` | 传统算法（C++/JNI，抗畸变） | **兜底**。Maven Central 可达；无 GMS 依赖，任何安卓机可用 |
| ML Kit Barcode | 依赖 Google Play Services | 企业机常无 GMS，不作主通道 |
| Scandit / Scanbot | 商业授权 | 不采用 |

**LLM 路线不通**：多模态大模型读不出 Code128 的条宽序列，必须「专用检测 + 解码」。

## 目录

```
tools/             PC 端 Python 参考实现（一切算法的基线，Kotlin 逐条对齐它）
  zpl_geometry.py  标签尺寸/字号度量/锚点偏移/码制容量 —— 唯一真源，Kotlin 必须 1:1 镜像
  label_frame.py   照片像素→标签点阵的「基准」：label(找标签四边形并矫正) / full / content
  label_to_zpl.py  主链：解码 → OCR → 元素表 → 映射 → ZPL
  ppocr_ref.py     PP-OCRv4 det/rec 后处理（与安卓端同款模型，取自 paddleocr-android-app）
  validate_zpl.py  机器校验：^XA/^XZ、^FD/^FS、^PW/^LL、坐标越界、条码估算宽度、禁用指令
  render_check.py  用 Labelary 渲染生成结果，做「原图 vs 重打」并排对照
  fidelity.py      定量评估：墨迹 IoU + 逐行带位置偏差（点）
  calib_text.py    用真实渲染端标定 ^A0/^A@ 字宽字高
  regress.py       封版回归：demo + 逐个成品 ZPL 校验 + 关键常量自检，一条命令出结论
  demo.py          一键跑全程
samples/ out/ shots/ docs/
android-app/       (第 2 步) Kotlin + Compose
```

## 已实测钉死的结论

1. **`^FT` 锚点 = 要素的下边缘**，不是左上角。字高 20/40/80/120 点时渲染实测字高 = 0.775×字号，
   而把锚点放在 y=20 时大于 20 点的字全部被裁掉 —— 这就是锚点语义的直接证据。
2. **字号必须同时受宽高约束**。只按高度推字号，`NANJING` 会超出 `^FB` 可用宽度并**被静默截断**
   （第一次实测就出现 "NANJIN"+折行）。`font_size_for()` 取 `min(高推出, 宽推出)`。
3. **映射基准不能是「要素包围盒铺满」**（`content`）：实测逐行带位置偏差 30~129 点 = 4~16mm；
   `full`（照片本身就是裁到标签边缘的）偏差 0~12 点。两者 IoU 0.16 vs 0.41~0.56。
   → 所以 A 必须支持「找标签四边形矫正」；`content` 只能当显式选项，且在 UI 上要提示会改绝对尺寸。
4. **基准已是整张标签时，映射不能再加自己的边距**：加了 `MARGIN/TOP_OFFSET` 会整体偏移 16~22 点。
5. **条码下方的人眼可读串（HRI）不能既当条码又当文字打两遍**：内容与条码一致、位于条码正下方且横向重叠
   → 归并成 `^BC...,Y`（`elements[].hri = true`），并从文字元素里删除。
6. **纸面尺寸＝模板唯一真源**：`zpl_geometry.TEMPLATES`（模板1 60×40 = 480×320、模板2 70×70 = 559×559、
   模板3 70×100 = 559×799，203dpi；模板3 是**纵向**标签）。
   两端都从这里推 `^PW/^LL/^FB/布局`，不许在别处写尺寸字面量。
7. 取整用 `round_half`（半进上），Python 的 `round()` 是银行家舍入，在 .5 边界会和 Kotlin `Math.round` 差 1 点。
8. **`^BY` 模块宽下限 2 点**（1 点 = 0.125mm 在 203dpi 下印糊；ISO 对 Code128 要求 X ≥ 0.19mm）。
   印糊的后果不是读不出，而是**扫描器按条宽反推码制，把 Code128 判成 Code39** —— 用户报的就是这个。
   纯数字内容按 **Code128C** 算模块数（`(n+1)/2` 个符号，20 位 = 145 模块而不是 255），否则会误判"放不下"。
9. **汉字（`^A@` + SimSun）占 1.0 em 宽、字高 ≈ 字号本身**；ASCII 才是 0.51 em / 0.775 字高比。
   两个比值混用 → 中文字号偏大 → 超 `^FB` 字段宽时**打印机不是截断，而是把字符挤着叠印**
   （用户报的"文字重合"）。另加硬兜底：字号减到实测宽度装得进字段为止。
10. **基准取"标签"而不是"照片"**：照带背景时按整幅映射会让内容整体缩一半，且模块宽会算成 1 点。
    `label_frame.detect_label_quad` 检出的候选面积 ≥92% 画面时要判为背景（深色桌面是更大的连通域）。

## 用法

```bash
PY=/c/Users/shenyf/AppData/Local/Programs/Python/Python314/python.exe
$PY tools/label_to_zpl.py samples/label_sample_1.png --template 1 --frame full
$PY tools/validate_zpl.py "out/*.zpl" --template 1
$PY tools/render_check.py out/label_sample_1.zpl samples/label_sample_1.png --template 1
$PY tools/fidelity.py out/label_sample_1.zpl samples/label_sample_1.png --template 1
$PY tools/demo.py --template 1 --frame full
```

`--frame`：`label`（照片里有标签，自动找四边形矫正）/ `full`（已裁到标签边）/ `content`（按内容铺满）/ `auto`。

## 进度（V1.0 已封版）

- [x] Android 工程：CameraX 拍照/选图 → 引擎 → 可编辑元素列表 → 照片上叠框 → ZPL 预览/复制/导出
- [x] `BarcodeEngine` 双实现 + `HybridBarcodeEngine`（二维码走 DL、一维码走 zxing）
- [x] 文字 OCR：PP-OCRv4（ORT + OpenCV）在端侧跑通
- [x] 透视校正：`label_frame.detect_label_quad` 的 Kotlin 版（`LabelFrame.kt`，候选面积 ≥92% 判背景）
- [x] 打印：Zebra Link-OS 蓝牙直出（secure→insecure 降级），真机 TC73 → ZR668 实测通
- [x] 两端逐字节比对回归（`tools/device_zpl_check.py`，条码-only 3/3；OCR 链路 ±2 点）
- [x] 真机实拍（TC73，12MP）+ 用户实拍药品追溯码标签 → 打印，几何对齐通过
- [x] V1.0 之后：拍摄取景指示框（长宽比随模板）、模板3 改为 70×100 纵向标签
- [ ] V1.1 之后：打后自动拍照比对验收、APK 体积（450MiB → release+minify）

## 条码引擎：实测短板与混合策略

| 引擎 | 强项 | 短板（实测） |
|---|---|---|
| Zebra DL `barcode-decoder-5.0.4` | 二维码稳、给真包围盒/四角 | 一维码在 <~4px/模块 时解不出（检测到、value 空） |
| zxing-cpp 3.1.1（`tryHarder=true`） | 一维码能解，并回完整符号框（与 PC 端逐点一致） | 糊图上的二维码不如 DL 稳 |

`HybridBarcodeEngine`：zxing 先入、DL 后入，同一物理符号（文本 + IoU>0.3）只在"新框大 10% 以上"时被顶替
——这样端侧与 PC 参考实现（用的就是 zxing）保持逐字节可比。DL 还有预算闸（单张 25s；原图 >4MP 或
候选区域 >1MP 就跳过"放大重扫"），否则真机 12MP 照片上会一直空转。
