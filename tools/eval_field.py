"""14 张现场照片批量评估（PC 侧）：输出每张图的 H/V 线与接触表，结果写 result.txt。"""
import cv2
import glob
import os
import sys

import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(__file__)))
import proto_rules4 as R
import eval_rules as E

rows = []
pics = []
for p in sorted(glob.glob("samples_field/*.jpg")):
    g = cv2.imread(p, cv2.IMREAD_GRAYSCALE)
    q = E.paper_quad(g)
    w = E.warp(g, q) if q is not None else None
    if w is None:
        rows.append((os.path.basename(p), "no-quad"))
        continue
    grid, hs, vs = R.detect(w)
    nh = len([i for i in grid if i["kind"] == "H"])
    nv = len([i for i in grid if i["kind"] == "V"])
    rows.append((os.path.basename(p), f"H{nh} V{nv}", [(i["kind"], round(i["pos"])) for i in grid]))
    vis = cv2.cvtColor(w, cv2.COLOR_GRAY2BGR)
    for it in grid:
        if it["kind"] == "H":
            cv2.rectangle(vis, (int(it["a"]), int(it["pos"] - 2)), (int(it["b"]), int(it["pos"] + 2)), (0, 0, 255), 2)
        else:
            cv2.rectangle(vis, (int(it["pos"] - 2), int(it["a"])), (int(it["pos"] + 2), int(it["b"])), (255, 0, 0), 2)
    hh, ww = vis.shape[:2]
    s = 440.0 / ww
    vis = cv2.resize(vis, (440, int(hh * s)))
    cv2.putText(vis, os.path.basename(p)[-10:-4], (4, 16), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 255, 255), 1)
    pics.append(vis)

th = max(p.shape[0] for p in pics) + 6
cols = 4
r = (len(pics) + cols - 1) // cols
sheet = np.full((r * th, cols * 440, 3), 30, np.uint8)
for i, p in enumerate(pics):
    rr, cc = divmod(i, cols)
    sheet[rr * th:rr * th + p.shape[0], cc * 440:cc * 440 + p.shape[1]] = p
cv2.imwrite("shots/eval_sheet_v4.png", sheet)

with open("shots/eval_result.txt", "w", encoding="utf-8") as f:
    for name, tag, detail in rows:
        f.write(f"{name}\t{tag}\t{detail}\n")
print("done")
for name, tag, detail in rows:
    print(name, tag, detail)
