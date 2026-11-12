# label-reprint V2.4 正式发布包

> PP-OCRv4 → **PP-OCRv6 small** 之后的第一个正式签名包。版式 / ZPL / 界面零改动，只换文字 OCR 模型与字典。

| 项 | 值 |
|---|---|
| 包名 | `com.tiaomabang.labelreprint` |
| 版本 | **versionName 2.4 / versionCode 24** |
| 文件 | `out/label-reprint-V2.4-release.apk` |
| 体积 | 469,752,038 B（448.0 MiB） |
| SHA-256 | `0685a25ac4f968a8a7a2f2829df19282ea6ee074ba2e792968341676cc1f3613` |
| ABI | arm64-v8a（仅） |
| debuggable | false |
| 签名 | v2，`CN=TiaoMaBang, OU=Label Reprint, O=TiaoMaBang, L=Nanjing, ST=Jiangsu, C=CN` |
| 证书 SHA-256 | `0fd8449731b02fbefad0c198638e2109981d2dcdb35afbf6c62edf62694bd161` |

**签名与 V1.9 及更早的正式包完全一致**（同一指纹），从正式版升级可直接覆盖安装；
只有从 **debug 测试包**（V2.3 及以前手工装的 `app-debug.apk`、以及昨天那个 V2.4 debug 包）切过来时才必须先卸载。

## 装机

```bash
adb install -r out/label-reprint-V2.4-release.apk
# 若报 INSTALL_FAILED_UPDATE_INCOMPATIBLE = 机器上是 debug 签名：
#   adb pull /sdcard/Android/data/com.tiaomabang.labelreprint/files ./files_backup
#   adb uninstall com.tiaomabang.labelreprint && adb install out/label-reprint-V2.4-release.apk
# 卸载会清掉 App 内设置（打印机地址/模板）与备份照片，模型在 assets 里、首启自动重装。
```

## 本版变更

1. **文字 OCR：PP-OCRv4 → PP-OCRv6 small**
   - det `ch_PP-OCRv6_det_small_infer.onnx` 9.9MB、rec `ch_PP-OCRv6_rec_small_infer.onnx` 21.2MB、
     字典 `ppocr_v6_dict.txt` 18708 字（+blank/space = 18710 类）；cls 不变。
   - 推理流水线**一行没改**：输入名/形状/归一化/CTC 布局与 v4 一致（已逐项核对 onnx 图与 `inference.yml`）。
   - 收益：EAN-13 人眼可读串从「少一位/多一个引号」变成 13 位全对；置信普遍 0.98~1.00；det 快 12~20%。
2. **APK 体积 +15.5MB**：v6 模型比 v4 大 15.5MB；v4 三个模型已移出 `assets/`（放 `android-app/models_v4/` 仅作 A/B 用）。
3. `tools/regress.py` 的 3 条 V1.0 时代常量断言按 V2.0 现行规格更新（模板2 76×70 → 607×559；字号 FONT_GAIN 1.15 → 19 号），
   回归门恢复可判；这 3 条自 V2.0 起就在误报，与本版无关。

## 验证（本包实测，TC201L / Android 15）

| 项 | 结果 |
|---|---|
| `apksigner verify --print-certs` | v2 签名通过，指纹同 V1.9 |
| `unzip -l` assets | v6 det / rec / cls / 字典四个文件齐全，无 v4 残留 |
| 干净安装（先 uninstall） | 成功，`versionName=2.4`，`pkgFlags` 无 DEBUGGABLE |
| 引擎冷启 | 首启解压 + 建 3 个 ONNX session 完成，OCR 可用 |
| 真实现场照片端到端 | `IMG_20261029_224147.jpg`：EAN-13 = **6934024512123**（全对），3 行文字 conf 0.88/1.00/1.00，`PLAN elements=4 errors=0`，OCR 548ms，^PW607/^LL559 |
| 回归 `device_zpl_check.py --ocr` | **mismatches: 0**（3 张样图要素与 PC 参考实现一致，坐标差 ≤2 点） |
| 回归 `device_zpl_check.py`（纯条码链） | mismatches: 3 —— 与 V2.3 记录的已知基线一致，数字未变 |
| `tools/regress.py` | demo 5/5、常量自检 12/12、ZPL 校验 3 条历史上传原件仍报 `^FD != ^FS`（V2.0 起既有，非生成器产物） |

## 已知限制

- 只打 arm64-v8a（TC73 / TC201L / ZD 系列扫描打印一体机），x86 模拟器装不了。
- Zebra AI Data Capture SDK 仍锁 **4.0.6**（4.1.0 在本机初始化抛 `AI Vision SDK is not supported`）。
- DB 后处理阈值沿用 v4 的 0.3 / 0.5 / 1.5（v6 官方推荐 0.2 / 0.45 / 1.4），留待单独 A/B。
- 未开混淆（`isMinifyEnabled=false`）：Zebra SDK 反射多，收益只是几十 MB。
