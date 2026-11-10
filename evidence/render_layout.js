#!/usr/bin/env node
/*
 * 取景页布局渲染器：不运行 Android，而是按相机页实际的视图层级、dp 尺寸与颜色令牌，
 * 把两种状态画成 PNG（修改前 = main 49ad844 的布局代码；修改后 = 本 PR 的布局代码）。
 *
 * 用途：在没有 Android 运行时的环境里提供可复核的 Before / After 排版示意。
 * 这不是应用截图；取景画面用中性渐变占位，模拟器里的材质与阴影不会出现。
 *
 * 用法：node render_layout.js <before|after> <out.png>
 */
"use strict";
const fs = require("fs");
const zlib = require("zlib");

// ---------------------------------------------------------------- 画布
const DENSITY = 3; // 360dp x 800dp @ xxhdpi，与 README 截图的 1080x2400 一致
const W_DP = 360;
const H_DP = 890;
const SCREEN_H_DP = 800;
const W = W_DP * DENSITY;
const H = H_DP * DENSITY;
const SCREEN_H = SCREEN_H_DP * DENSITY;
const STATUS_BAR_DP = 24;
const NAV_INSET_DP = 24; // 手势导航条

// ---------------------------------------------------------------- 颜色
const CLR = {
  previewTop: [74, 67, 58],
  previewBottom: [34, 31, 28],
  black: [0, 0, 0],
  white: [255, 255, 255],
  canvas: [10, 13, 18],
  // 修改前（main）的颜色
  oldNav: [13, 16, 22],
  oldPillSelected: [39, 49, 67],
  // 修改后（本 PR）的令牌
  textPrimary: [244, 245, 247],
  textSecondary: [164, 171, 183],
  textTertiary: [138, 147, 163],
  focusAccent: [213, 228, 255],
  aiAccent: [204, 190, 255],
  aiTile: [27, 33, 48],
  glassCard: [18, 22, 33],
  glassButton: [12, 16, 24],
  surfaceSelected: [39, 49, 67],
};

// ---------------------------------------------------------------- 光栅画布
class Canvas {
  constructor(w, h, bg) {
    this.w = w;
    this.h = h;
    this.data = Buffer.alloc(w * h * 4);
    if (bg) this.fillRect(0, 0, w, h, toRGBA(bg));
  }

  fillRect(x, y, w, h, rgba) {
    const x0 = Math.max(0, Math.round(x));
    const y0 = Math.max(0, Math.round(y));
    const x1 = Math.min(this.w, Math.round(x + w));
    const y1 = Math.min(this.h, Math.round(y + h));
    const [r, g, b, a] = rgba;
    for (let yy = y0; yy < y1; yy++) {
      for (let xx = x0; xx < x1; xx++) this.blend(xx, yy, r, g, b, a);
    }
  }

  blend(x, y, r, g, b, a) {
    if (x < 0 || y < 0 || x >= this.w || y >= this.h || a === 0) return;
    const i = (y * this.w + x) * 4;
    const d = this.data;
    const sa = a / 255;
    const da = d[i + 3] / 255;
    const oa = sa + da * (1 - sa);
    if (oa === 0) return;
    d[i] = Math.round((r * sa + d[i] * da * (1 - sa)) / oa);
    d[i + 1] = Math.round((g * sa + d[i + 1] * da * (1 - sa)) / oa);
    d[i + 2] = Math.round((b * sa + d[i + 2] * da * (1 - sa)) / oa);
    d[i + 3] = Math.round(oa * 255);
  }

  /** 圆角矩形，radius 以像素计。 */
  roundRect(x, y, w, h, radius, fill, stroke, strokeWidth = 0) {
    const x0 = Math.max(0, Math.floor(x - strokeWidth - 1));
    const y0 = Math.max(0, Math.floor(y - strokeWidth - 1));
    const x1 = Math.min(this.w, Math.ceil(x + w + strokeWidth + 1));
    const y1 = Math.min(this.h, Math.ceil(y + h + strokeWidth + 1));
    const r = Math.min(radius, w / 2, h / 2);
    for (let yy = y0; yy < y1; yy++) {
      for (let xx = x0; xx < x1; xx++) {
        const d = distToRoundRect(xx + 0.5, yy + 0.5, x, y, w, h, r);
        if (fill && d <= 0) this.blend(xx, yy, fill[0], fill[1], fill[2], fill[3]);
        if (stroke && strokeWidth > 0 && d <= 0 && d > -strokeWidth) {
          this.blend(xx, yy, stroke[0], stroke[1], stroke[2], stroke[3]);
        }
      }
    }
  }

  circle(cx, cy, radius, fill, stroke, strokeWidth = 0) {
    this.roundRect(cx - radius, cy - radius, radius * 2, radius * 2, radius, fill, stroke, strokeWidth);
  }

  /** 只画描边环（用于快门、圆形图标按钮的外框）。 */
  ring(cx, cy, radius, strokeWidth, color) {
    this.roundRect(cx - radius, cy - radius, radius * 2, radius * 2, radius, null, color, strokeWidth);
  }

  verticalGradient(x, y, w, h, stops) {
    for (let yy = Math.max(0, y); yy < Math.min(this.h, y + h); yy++) {
      const t = (yy - y) / h;
      const c = sampleStops(stops, t);
      this.fillRect(x, yy, w, 1, c);
    }
  }

  writePng(path) {
    // 预乘 alpha -> 直通 alpha
    const raw = Buffer.alloc((this.w * 3 + 1) * this.h);
    for (let y = 0; y < this.h; y++) {
      raw[y * (this.w * 3 + 1)] = 0;
      for (let x = 0; x < this.w; x++) {
        const i = (y * this.w + x) * 4;
        const o = y * (this.w * 3 + 1) + 1 + x * 3;
        raw[o] = this.data[i];
        raw[o + 1] = this.data[i + 1];
        raw[o + 2] = this.data[i + 2];
      }
    }
    const ihdr = Buffer.alloc(13);
    ihdr.writeUInt32BE(this.w, 0);
    ihdr.writeUInt32BE(this.h, 4);
    ihdr[8] = 8;
    ihdr[9] = 2;
    const chunks = [chunk("IHDR", ihdr), chunk("IDAT", zlib.deflateSync(raw, { level: 9 })), chunk("IEND", Buffer.alloc(0))];
    fs.writeFileSync(path, Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), ...chunks]));
  }
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length, 0);
  const body = Buffer.concat([Buffer.from(type, "ascii"), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body) >>> 0, 0);
  return Buffer.concat([len, body, crc]);
}

let CRC_TABLE = null;
function crc32(buf) {
  if (!CRC_TABLE) {
    CRC_TABLE = new Int32Array(256);
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      CRC_TABLE[n] = c;
    }
  }
  let c = -1;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ -1) >>> 0;
}

function toRGBA(c, alpha = 255) {
  return [c[0], c[1], c[2], alpha];
}

function sampleStops(stops, t) {
  for (let i = 1; i < stops.length; i++) {
    if (t <= stops[i][0]) {
      const [t0, c0] = stops[i - 1];
      const [t1, c1] = stops[i];
      const k = t1 === t0 ? 0 : (t - t0) / (t1 - t0);
      return [
        Math.round(c0[0] + (c1[0] - c0[0]) * k),
        Math.round(c0[1] + (c1[1] - c0[1]) * k),
        Math.round(c0[2] + (c1[2] - c0[2]) * k),
        Math.round(c0[3] + (c1[3] - c0[3]) * k),
      ];
    }
  }
  return stops[stops.length - 1][1];
}

function distToRoundRect(px, py, x, y, w, h, r) {
  const cx = x + w / 2;
  const cy = y + h / 2;
  const qx = Math.abs(px - cx) - (w / 2 - r);
  const qy = Math.abs(py - cy) - (h / 2 - r);
  const ax = Math.max(qx, 0);
  const ay = Math.max(qy, 0);
  return Math.sqrt(ax * ax + ay * ay) + Math.min(Math.max(qx, qy), 0) - r;
}

// ---------------------------------------------------------------- 5x7 点阵字体
const FONT = {
  " ": ["00000", "00000", "00000", "00000", "00000", "00000", "00000"],
  A: ["01110", "10001", "10001", "11111", "10001", "10001", "10001"],
  B: ["11110", "10001", "10001", "11110", "10001", "10001", "11110"],
  C: ["01110", "10001", "10000", "10000", "10000", "10001", "01110"],
  D: ["11110", "10001", "10001", "10001", "10001", "10001", "11110"],
  E: ["11111", "10000", "10000", "11110", "10000", "10000", "11111"],
  F: ["11111", "10000", "10000", "11110", "10000", "10000", "10000"],
  G: ["01110", "10001", "10000", "10111", "10001", "10001", "01111"],
  H: ["10001", "10001", "10001", "11111", "10001", "10001", "10001"],
  I: ["01110", "00100", "00100", "00100", "00100", "00100", "01110"],
  J: ["00111", "00010", "00010", "00010", "00010", "10010", "01100"],
  K: ["10001", "10010", "10100", "11000", "10100", "10010", "10001"],
  L: ["10000", "10000", "10000", "10000", "10000", "10000", "11111"],
  M: ["10001", "11011", "10101", "10101", "10001", "10001", "10001"],
  N: ["10001", "11001", "10101", "10011", "10001", "10001", "10001"],
  O: ["01110", "10001", "10001", "10001", "10001", "10001", "01110"],
  P: ["11110", "10001", "10001", "11110", "10000", "10000", "10000"],
  Q: ["01110", "10001", "10001", "10001", "10101", "10010", "01101"],
  R: ["11110", "10001", "10001", "11110", "10100", "10010", "10001"],
  S: ["01111", "10000", "10000", "01110", "00001", "00001", "11110"],
  T: ["11111", "00100", "00100", "00100", "00100", "00100", "00100"],
  U: ["10001", "10001", "10001", "10001", "10001", "10001", "01110"],
  V: ["10001", "10001", "10001", "10001", "10001", "01010", "00100"],
  W: ["10001", "10001", "10001", "10101", "10101", "10101", "01010"],
  X: ["10001", "10001", "01010", "00100", "01010", "10001", "10001"],
  Y: ["10001", "10001", "01010", "00100", "00100", "00100", "00100"],
  Z: ["11111", "00001", "00010", "00100", "01000", "10000", "11111"],
  0: ["01110", "10001", "10011", "10101", "11001", "10001", "01110"],
  1: ["00100", "01100", "00100", "00100", "00100", "00100", "01110"],
  2: ["01110", "10001", "00001", "00010", "00100", "01000", "11111"],
  3: ["11111", "00010", "00100", "00010", "00001", "10001", "01110"],
  4: ["00010", "00110", "01010", "10010", "11111", "00010", "00010"],
  5: ["11111", "10000", "11110", "00001", "00001", "10001", "01110"],
  6: ["00110", "01000", "10000", "11110", "10001", "10001", "01110"],
  7: ["11111", "00001", "00010", "00100", "01000", "01000", "01000"],
  8: ["01110", "10001", "10001", "01110", "10001", "10001", "01110"],
  9: ["01110", "10001", "10001", "01111", "00001", "00010", "01100"],
  ".": ["00000", "00000", "00000", "00000", "00000", "01100", "01100"],
  ",": ["00000", "00000", "00000", "00000", "01100", "00100", "01000"],
  "%": ["11001", "11010", "00010", "00100", "01000", "01011", "10011"],
  ":": ["00000", "01100", "01100", "00000", "01100", "01100", "00000"],
  "-": ["00000", "00000", "00000", "11111", "00000", "00000", "00000"],
  "+": ["00000", "00100", "00100", "11111", "00100", "00100", "00000"],
  "/": ["00001", "00010", "00010", "00100", "01000", "01000", "10000"],
  "·": ["00000", "00000", "00000", "01100", "01100", "00000", "00000"],
  "×": ["00000", "10001", "01010", "00100", "01010", "10001", "00000"],
  "é": ["00010", "00100", "01110", "10001", "11111", "10000", "01110"],
  "▾": ["00000", "11111", "01110", "00100", "00000", "00000", "00000"],
  "−": ["00000", "00000", "00000", "11111", "00000", "00000", "00000"],
  "?": ["01110", "10001", "00001", "00010", "00100", "00000", "00100"],
};

// CJK 字形：只画固定宽度的“语言条”方块，表示这里有一段中文文案。
function drawText(canvas, text, x, y, sizePx, colorRGBA, opts = {}) {
  const pixel = Math.max(1, Math.round(sizePx / 7));
  const advance = pixel * 6;
  const ascent = pixel * 7;
  const cjkWidth = pixel * 6;
  let cursor = x;
  const limitRight = opts.maxRight === undefined ? canvas.w : opts.maxRight;
  for (const ch of text) {
    const isCJK = /[\u3000-\u9fff\uff00-\uffef]/.test(ch);
    if (ch === " ") {
      cursor += advance;
      continue;
    }
    if (cursor + cjkWidth > limitRight) {
      if (opts.ellipsis !== false) drawLatin(canvas, "…", cursor, y + ascent - pixel * 1, pixel, colorRGBA);
      break;
    }
    if (isCJK) {
      // 中文占位：字形高度与拉丁小写相近，笔画用横条表示
      const barH = Math.max(2, Math.round(pixel * 1.1));
      const top = y + (sizePx - 4 * pixel) / 2;
      for (let i = 0; i < 2; i++) {
        canvas.fillRect(cursor + pixel * 0.8, top + i * pixel * 2.2, cjkWidth - pixel * 1.6, barH, colorRGBA);
      }
      cursor += cjkWidth + pixel;
      continue;
    }
    drawLatin(canvas, ch, cursor, y, pixel, colorRGBA);
    cursor += advance;
  }
  return cursor - x;
}

function drawLatin(canvas, ch, x, y, pixel, colorRGBA, glyphOverride) {
  const sizePx = pixel * 7;
  if (ch === "…") {
    for (let i = 0; i < 3; i++) canvas.fillRect(x + i * pixel * 2, y + pixel * 6, pixel, pixel, colorRGBA);
    return;
  }
  const glyph = glyphOverride || FONT[ch] || FONT[ch.toUpperCase()];
  if (!glyph) return;
  // y 是假想行框顶部；点阵字形按 7 像素行居中到行框中
  const top = Math.round(y + (sizePx - 7 * pixel) / 2);
  for (let row = 0; row < 7; row++) {
    for (let col = 0; col < 5; col++) {
      if (glyph[row][col] === "1") canvas.fillRect(x + col * pixel, top + row * pixel, pixel, pixel, colorRGBA);
    }
  }
}

function textWidth(text, sizePx) {
  const pixel = Math.max(1, Math.round(sizePx / 7));
  let w = 0;
  for (const ch of text) {
    w += /[\u3000-\u9fff\uff00-\uffef]/.test(ch) ? pixel * 7 : pixel * 6;
  }
  return w;
}

function centeredText(canvas, text, cx, y, sizePx, color) {
  drawText(canvas, text, cx - textWidth(text, sizePx) / 2, y, sizePx, color);
}

// ---------------------------------------------------------------- 公共元素
function preview(canvas, topDp = 0) {
  canvas.verticalGradient(0, 0, W, SCREEN_H, [
    [0, [...CLR.previewTop, 255]],
    [1, [...CLR.previewBottom, 255]],
  ]);
  // 三分构图辅助线
  const line = [255, 255, 255, 90];
  for (let k = 1; k <= 2; k++) {
    canvas.fillRect(Math.round((W * k) / 3), 0, DENSITY, SCREEN_H, line);
    canvas.fillRect(0, Math.round((SCREEN_H * k) / 3), W, DENSITY, line);
  }
}

function statusBar(canvas, dp = (v) => v * DENSITY) {
  drawText(canvas, "7:58", dp(18), dp(6), dp(11), [255, 255, 255, 255]);
  drawText(canvas, "5G", W - dp(60), dp(6), dp(11), [255, 255, 255, 255]);
  canvas.roundRect(W - dp(38), dp(9), dp(20), dp(10), dp(2), [255, 255, 255, 255]);
}

function glassCard(canvas, x, y, w, h, radiusDp, dp, opts = {}) {
  const fill = opts.fill || [...CLR.glassCard, 224];
  canvas.roundRect(x, y, w, h, dp(radiusDp), fill, [255, 255, 255, 51], dp(1));
}

function iconButton(canvas, cx, cy, d, dp, opts = {}) {
  const r = dp(d) / 2;
  if (opts.filled !== false) {
    canvas.circle(cx, cy, r, [...CLR.glassButton, 140], [255, 255, 255, 51], dp(1));
  }
  const color = opts.color || [...CLR.textPrimary, 255];
  const glyphSize = opts.glyphSize || dp(d) * 0.42;
  const kind = opts.kind || "gear";
  if (kind === "sliders") {
    drawText(canvas, "H", cx - textWidth("H", glyphSize) / 2, cy - glyphSize * 0.5, glyphSize, color);
  } else if (kind === "gear") {
    canvas.ring(cx, cy, glyphSize * 0.42, Math.max(2, glyphSize * 0.14), color);
    canvas.circle(cx, cy, glyphSize * 0.1, color);
  } else if (kind === "close") {
    const g = glyphSize * 0.32;
    for (let i = -g; i <= g; i++) {
      canvas.fillRect(cx + i, cy + i, 2, 2, color);
      canvas.fillRect(cx + i, cy - i, 2, 2, color);
    }
  }
}

function sparkle(canvas, cx, cy, s, color) {
  // 四角星
  for (let dy = -s; dy <= s; dy++) {
    const half = Math.round((1 - Math.abs(dy) / s) * s * 0.5);
    canvas.fillRect(cx - half, cy + dy, Math.max(1, half * 2), 1, color);
  }
  for (let dx = -s; dx <= s; dx++) {
    const half = Math.round((1 - Math.abs(dx) / s) * s * 0.5);
    canvas.fillRect(cx + dx, cy - half, 1, Math.max(1, half * 2), color);
  }
}

function shutter(canvas, cx, cy, dDp, dp) {
  const r = dp(dDp) / 2;
  canvas.circle(cx, cy, r, [255, 255, 255, 34]);
  canvas.ring(cx, cy, r - dp(2), dp(4), [255, 255, 255, 255]);
}

function navRow(canvas, yTop, h, dp, opts) {
  const itemH = h;
  const thirds = [W / 6, W / 2, (W * 5) / 6];
  const labels = ["HOME", "STYLE", "SETTINGS"];
  thirds.forEach((cx, i) => {
    const selected = i === 0;
    const color = selected ? [...CLR.focusAccent, 255] : [...CLR.textSecondary, 255];
    if (selected) {
      canvas.roundRect(cx - W / 6 + dp(opts.pillInset), yTop, W / 3 - dp(opts.pillInset * 2), itemH, dp(18),
        [...CLR.surfaceSelected, 255], null, 0);
    }
    const iconY = yTop + dp(opts.iconTop);
    if (i === 0) {
      canvas.roundRect(cx - dp(6), iconY + dp(2), dp(12), dp(8), dp(2), null, color, dp(1.5));
      canvas.circle(cx, iconY + dp(6), dp(3), null, color, dp(1.5));
    } else if (i === 1) {
      iconButton(canvas, cx, iconY + dp(5.5), 11, dp, { filled: false, color, kind: "sliders" });
    } else {
      canvas.ring(cx, iconY + dp(5.5), dp(3.2), dp(1.2), color);
    }
    centeredText(canvas, labels[i], cx, iconY + dp(opts.iconTop + opts.labelGap), dp(6), color);
  });
}

// ---------------------------------------------------------------- 修改前（main 49ad844）
function renderBefore() {
  const c = new Canvas(W, H);
  const S = SCREEN_H;
  const dp = (v) => v * DENSITY;
  preview(c);

  // 顶栏：FrameLayout，padding(top 44dp)；左右 48dp 玻璃按钮，中间 188dp 固定宽状态卡
  const topPad = dp(44);
  iconButton(c, dp(16) + dp(24), topPad + dp(24), 48, dp, { kind: "sliders" });
  iconButton(c, W - dp(16) - dp(24), topPad + dp(24), 48, dp, { kind: "gear" });

  const cardW = dp(188);
  const cardX = (W - cardW) / 2;
  const cardY = topPad;
  const cardH = dp(76);
  glassCard(c, cardX, cardY, cardW, cardH, 20, dp, { fill: [...CLR.glassCard, 238] });
  const pad = dp(12);
  centeredText(c, "CAMERA AGENT", W / 2, cardY + dp(7), dp(9), [183, 204, 239, 255]);
  drawText(c, "通用场景 · 未分类", cardX + pad, cardY + dp(19), dp(11), [255, 255, 255, 255], { maxRight: cardX + cardW - pad });
  drawText(c, "让主要主体靠近三分线交点。", cardX + pad, cardY + dp(33), dp(11), [255, 255, 255, 255], { maxRight: cardX + cardW - pad });
  drawText(c, "拍摄意图：自动判断", cardX + pad, cardY + dp(51), dp(11), [231, 223, 255, 255], { maxRight: cardX + cardW - pad });

  // 底部拍摄区：预设条 52dp（胶囊 48dp）+ 快门行
  const navH = dp(56);
  const bottomY = SCREEN_H - navH;
  const bottomGradTop = bottomY - dp(190);
  c.verticalGradient(0, bottomGradTop, W, bottomY - bottomGradTop, [
    [0, [0, 0, 0, 0]],
    [1, [0, 0, 0, 221]],
  ]);
  const stripY = bottomY - dp(52) - dp(100);
  drawPresetChips(c, dp, stripY, dp(48), {
    selectedFill: [...CLR.oldPillSelected, 255],
    unselectedFill: [12, 16, 24, 174],
    selectedStroke: [213, 228, 255, 150],
    unselectedStroke: [255, 255, 255, 74],
    selectedText: [255, 255, 255, 255],
    unselectedText: [205, 211, 221, 255],
    padX: 14,
    gap: 8,
    radius: 18,
  });

  const barH = dp(100);
  const barY = bottomY - barH;
  const centerY = barY + dp(10) + dp(36);
  // 左：82x52dp 文本按钮；中：72dp 快门；右：60dp 圆形
  const quickX = dp(24);
  glassPill(c, quickX, centerY - dp(26), dp(82), dp(52), dp(18), [...CLR.oldPillSelected, 255], [213, 228, 255, 150]);
  centeredText(c, "自然标准", quickX + dp(41), centerY - dp(9), dp(6), [255, 255, 255, 255]);
  const shutterCx = quickX + dp(82) + dp(18) + dp(36);
  shutter(c, shutterCx, centerY, 72, dp);
  const agentCx = shutterCx + dp(36) + dp(18) + dp(30);
  c.circle(agentCx, centerY, dp(30), [58, 12, 163, 255], [160, 255, 255, 160], dp(2.5));
  sparkle(c, agentCx, centerY, dp(12), [255, 255, 255, 255]);

  // 一级导航：64dp + 48dp 图标
  c.fillRect(0, bottomY, W, navH, [...CLR.oldNav, 255]);
  navRow(c, bottomY + dp(5), dp(54), dp, { pillInset: 4, iconTop: 0, iconSize: 20, labelGap: 3 });
  // 手势导航条
  c.roundRect(W / 2 - dp(54), SCREEN_H - dp(7), dp(108), dp(4), dp(2), [255, 255, 255, 200]);
  statusBar(c, dp);
  legend(c, dp, "before");
  return c;
}

// ---------------------------------------------------------------- 修改后（本 PR）
function renderAfter() {
  const c = new Canvas(W, H);
  const S = SCREEN_H;
  const dp = (v) => v * DENSITY;
  preview(c);

  // 顶部：状态栏安全区 24dp + 8dp 间距
  const top = dp(STATUS_BAR_DP) + dp(8);
  iconButton(c, dp(16) + dp(24), top + dp(24), 48, dp, { kind: "sliders" });
  iconButton(c, W - dp(16) - dp(24), top + dp(24), 48, dp, { kind: "gear" });

  // 状态卡：占用两侧按钮之间的剩余宽度（重排后与顶栏同一基线，圆角 24dp）
  const cardX = dp(16) + dp(48) + dp(12);
  const cardW = W - cardX * 2;
  const cardY = top;
  const cardH = dp(76);
  glassCard(c, cardX, cardY, cardW, cardH, 24, dp);
  const pad = dp(14);
  centeredText(c, "CAMERA AGENT", W / 2, cardY + dp(8), dp(9), [...CLR.focusAccent, 255]);
  centeredText(c, "通用场景 · 未分类", W / 2, cardY + dp(22), dp(11), [...CLR.textPrimary, 255]);
  centeredText(c, "让主要主体靠近三分线交点", W / 2, cardY + dp(38), dp(11), [...CLR.textPrimary, 255]);
  centeredText(c, "拍摄意图：自动判断  ▾", W / 2, cardY + dp(54), dp(9), [...CLR.textSecondary, 255]);

  // 底部：拍摄区 + 导航（导航让出手势条）
  const navH = dp(64) + dp(NAV_INSET_DP);
  const bottomY = SCREEN_H - navH;
  const scrimH = dp(210);
  c.verticalGradient(0, bottomY - scrimH, W, scrimH, [
    [0, [0, 0, 0, 0]],
    [0.25, [0, 0, 0, 166]],
    [1, [0, 0, 0, 242]],
  ]);
  // 快门行 12dp + 76dp + 24dp，预设条 16dp + 48dp
  const barH = dp(12) + dp(76) + dp(24);
  const barY = bottomY - barH;
  const centerY = barY + dp(12) + dp(38);
  const stripY = barY - dp(48) - dp(16);
  drawPresetChips(c, dp, stripY, dp(40), {
    selectedFill: [39, 49, 67, 204],
    unselectedFill: [12, 16, 24, 140],
    selectedStroke: [213, 228, 255, 153],
    unselectedStroke: [255, 255, 255, 64],
    selectedText: [...CLR.textPrimary, 255],
    unselectedText: [...CLR.textSecondary, 255],
    padX: 16,
    gap: 8,
    radius: 24,
    chipPad: 12,
  });
  // 快门居中：两侧控件同尺寸 64dp，快手中线与画面中线重合
  const sideW = dp(64);
  quickPill(c, dp(24), centerY - sideW / 2, sideW, sideW, dp, "自然标准");
  shutter(c, W / 2, centerY, 72, dp);
  const agentCx = W - dp(24) - sideW / 2;
  c.circle(agentCx, centerY, sideW / 2, [...CLR.aiTile, 255], [...CLR.aiAccent, 102], dp(1));
  sparkle(c, agentCx, centerY, dp(11), [...CLR.aiAccent, 255]);

  c.fillRect(0, bottomY, W, navH, [...CLR.canvas, 255]);
  navRow(c, bottomY + dp(6), dp(52), dp, { pillInset: 0, iconTop: 0, iconSize: 22, labelGap: 3 });
  c.roundRect(W / 2 - dp(54), SCREEN_H - dp(12), dp(108), dp(4), dp(2), [255, 255, 255, 200]);
  statusBar(c, dp);
  legend(c, dp, "after");
  return c;
}

function quickPill(c, x, y, w, h, dp, label) {
  c.roundRect(x, y, w, h, h / 2, [...CLR.glassButton, 140], [...CLR.focusAccent, 153], dp(1));
  centeredText(c, label, x + w / 2, y + h / 2 - dp(4), dp(6), [...CLR.textPrimary, 255]);
}

function glassPill(c, x, y, w, h, radius, fill, stroke) {
  c.roundRect(x, y, w, h, radius, fill, stroke, DENSITY);
}

const PRESETS = [
  ["自然标准", [183, 204, 239]],
  ["暖色胶片", [242, 177, 132]],
  ["褪色复古", [211, 185, 145]],
  ["冷调电影", [143, 187, 226]],
  ["鲜艳夜色", [199, 163, 255]],
  ["柔和人像", [242, 177, 132]],
];

function drawPresetChips(c, dp, y, h, style) {
  let x = dp(style.padX);
  PRESETS.forEach(([name, accent], i) => {
    const selected = i === 0;
    const labelSize = dp(6);
    const padX = dp(style.chipPad || 11);
    const w = padX + dp(10) + dp(7) + textWidth(name, labelSize) + dp(style.chipPad || 12);
    if (x + w > W) return;
    c.roundRect(
      x,
      y,
      w,
      h,
      dp(style.radius),
      selected ? style.selectedFill : style.unselectedFill,
      selected ? style.selectedStroke : style.unselectedStroke,
      dp(1),
    );
    c.circle(x + padX + dp(5), y + h / 2, dp(5), [...accent, 255]);
    drawText(c, name, x + padX + dp(10) + dp(7), y + h / 2 - labelSize * 0.5, labelSize, selected ? style.selectedText : style.unselectedText,
      { ellipsis: false });
    x += w + dp(style.gap);
  });
}

// ---------------------------------------------------------------- 说明条
function legend(c, dp, which) {
  const y = SCREEN_H;
  c.fillRect(0, y, W, H - y, [18, 20, 26, 255]);
  c.fillRect(0, y, W, dp(1), [255, 255, 255, 40]);
  const title = which === "before" ? "BEFORE" : "AFTER";
  const headline = which === "before"
    ? "取景页排版示意（修改前）"
    : "取景页排版示意（修改后）";
  drawText(c, title, dp(16), y + dp(12), dp(7), which === "before" ? [200, 200, 200, 255] : [...CLR.focusAccent, 255]);
  drawText(c, headline, dp(16), y + dp(28), dp(8), [255, 255, 255, 255]);
  const notes = which === "before"
    ? ["MAIN 49AD844  LAYOUT CODE", "TOP BAR 44DP PAD, CARD 188DP FIXED", "SHUTTER ROW NOT CENTERED", "48DP PRESET CHIPS, NAV 64DP"]
    : ["THIS PR, ACTUAL DP SIZES AND COLORS", "TOPBAR INSETS + FLEXIBLE CENTER CARD", "SHUTTER CENTERED, SIDE TILES 64DP", "40DP PRESET CHIPS, NAV + GESTURE INSET"];
  notes.forEach((n, i) => {
    drawText(c, n, dp(16), y + dp(50 + i * 12), dp(5.5), [...CLR.textSecondary, 255]);
  });
  drawText(c, "LAYOUT RENDERER, NOT A DEVICE SCREENSHOT", dp(16), y + dp(52 + notes.length * 12 + 6), dp(5.5), [255, 170, 120, 255]);
}

// ---------------------------------------------------------------- main
const which = process.argv[2];
const out = process.argv[3] || `${which}.png`;
if (which !== "before" && which !== "after") {
  console.error("usage: node render_layout.js <before|after> <out.png>");
  process.exit(2);
}
const canvas = which === "before" ? renderBefore() : renderAfter();
canvas.writePng(out);
console.log(`wrote ${out} (${W}x${H})`);
