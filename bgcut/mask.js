// マスク（0〜255 の 1 チャンネル配列）を扱う処理。
// すべて Uint8Array / Float32Array 上で行い、ブラウザの filter 対応差に左右されないようにする。

const clamp255 = (v) => (v < 0 ? 0 : v > 255 ? 255 : v);

// 半径 r の箱型ぼかしを横→縦の順で 1 回かける（in-place, Float32Array）。
function boxBlur(buf, w, h, r, tmp) {
  if (r < 1) return buf;
  const t = tmp || new Float32Array(w * h);
  const norm = 1 / (2 * r + 1);
  for (let y = 0; y < h; y++) {
    const row = y * w;
    let acc = 0;
    for (let i = -r; i <= r; i++) acc += buf[row + Math.min(w - 1, Math.max(0, i))];
    for (let x = 0; x < w; x++) {
      t[row + x] = acc * norm;
      const add = Math.min(w - 1, x + r + 1);
      const sub = Math.max(0, x - r);
      acc += buf[row + add] - buf[row + sub];
    }
  }
  for (let x = 0; x < w; x++) {
    let acc = 0;
    for (let i = -r; i <= r; i++) acc += t[Math.min(h - 1, Math.max(0, i)) * w + x];
    for (let y = 0; y < h; y++) {
      buf[y * w + x] = acc * norm;
      const add = Math.min(h - 1, y + r + 1);
      const sub = Math.max(0, y - r);
      acc += t[add * w + x] - t[sub * w + x];
    }
  }
  return buf;
}

// 境界調整に必要な余白（この範囲より外の画素は結果に影響しない）
export function refinePad(edge) {
  const s = Math.abs(edge.shift | 0);
  const f = edge.feather | 0;
  return (s ? 2 * s * 2 : 0) + f * 2 + 2;
}

export function needsRefine(edge) {
  return (edge.shift | 0) !== 0 || (edge.feather | 0) > 0;
}

// manual マスクの矩形 rect に境界調整（拡張/縮小・ぼかし）をかけて out に書き込む。
// rect = {x, y, w, h}。省略時は全体。
export function refineInto(manual, out, w, h, edge, rect) {
  rect = rect || { x: 0, y: 0, w, h };
  if (!needsRefine(edge)) {
    for (let y = rect.y; y < rect.y + rect.h; y++) {
      const o = y * w + rect.x;
      out.set(manual.subarray(o, o + rect.w), o);
    }
    return;
  }
  const pad = refinePad(edge);
  const x0 = Math.max(0, rect.x - pad), y0 = Math.max(0, rect.y - pad);
  const x1 = Math.min(w, rect.x + rect.w + pad), y1 = Math.min(h, rect.y + rect.h + pad);
  const rw = x1 - x0, rh = y1 - y0;
  const buf = new Float32Array(rw * rh);
  for (let y = 0; y < rh; y++) {
    const o = (y + y0) * w + x0;
    for (let x = 0; x < rw; x++) buf[y * rw + x] = manual[o + x];
  }
  const tmp = new Float32Array(rw * rh);
  const s = edge.shift | 0;
  if (s) {
    // ぼかしてからしきい値を動かすと、境界を外側/内側へ |s| px ずらせる
    const r = 2 * Math.abs(s);
    boxBlur(buf, rw, rh, r, tmp);
    const level = (s > 0 ? 0.25 : 0.75) * 255;
    for (let i = 0; i < buf.length; i++) buf[i] = clamp255((buf[i] - level) * 5 + 127.5);
  }
  const f = edge.feather | 0;
  if (f > 0) {
    boxBlur(buf, rw, rh, f, tmp);
    boxBlur(buf, rw, rh, f, tmp);
  }
  for (let y = rect.y; y < rect.y + rect.h; y++) {
    const bo = (y - y0) * rw + (rect.x - x0);
    const o = y * w + rect.x;
    for (let x = 0; x < rect.w; x++) out[o + x] = buf[bo + x] + 0.5;
  }
}

// ガイデッドフィルタで、低解像度から拡大したマスクの境界を元画像の輪郭に沿わせる。
// 髪の毛や細い部分の境界がぼやけるのを抑える。境界付近（不確かな帯）にだけ適用する。
export function guidedRefine(mask, rgba, w, h) {
  const n = w * h;
  const r = Math.max(2, Math.round(Math.max(w, h) / 1024 * 4));
  const eps = 1e-3;
  const I = new Float32Array(n), p = new Float32Array(n);
  for (let i = 0; i < n; i++) {
    const j = i * 4;
    I[i] = (0.299 * rgba[j] + 0.587 * rgba[j + 1] + 0.114 * rgba[j + 2]) / 255;
    p[i] = mask[i] / 255;
  }
  const tmp = new Float32Array(n);
  const meanI = boxBlur(Float32Array.from(I), w, h, r, tmp);
  const meanP = boxBlur(Float32Array.from(p), w, h, r, tmp);
  const corrIP = new Float32Array(n), corrII = new Float32Array(n);
  for (let i = 0; i < n; i++) { corrIP[i] = I[i] * p[i]; corrII[i] = I[i] * I[i]; }
  boxBlur(corrIP, w, h, r, tmp);
  boxBlur(corrII, w, h, r, tmp);
  // a, b を corrIP, corrII に上書きして使い回す
  const a = corrIP, b = corrII;
  for (let i = 0; i < n; i++) {
    const varI = b[i] - meanI[i] * meanI[i];
    const covIP = a[i] - meanI[i] * meanP[i];
    const ai = covIP / (varI + eps);
    a[i] = ai;
    b[i] = meanP[i] - ai * meanI[i];
  }
  boxBlur(a, w, h, r, tmp);
  boxBlur(b, w, h, r, tmp);
  // meanP をもう一度広めにぼかして「境界付近か」を判定する
  const band = meanP;
  boxBlur(band, w, h, r, tmp);
  for (let i = 0; i < n; i++) {
    const u = band[i];
    if (u <= 0.01 || u >= 0.99) continue;
    // 帯の中心ほどガイデッドフィルタの結果を強く使う
    const wgt = Math.min(1, (1 - Math.abs(2 * u - 1)) * 3);
    const q = a[i] * I[i] + b[i];
    const v = (q < 0 ? 0 : q > 1 ? 1 : q) * wgt + p[i] * (1 - wgt);
    mask[i] = v * 255 + 0.5;
  }
  return mask;
}

// しきい値以上の画素を含む外接矩形（被写体の位置）。見つからなければ null。
export function maskBBox(mask, w, h, th = 24) {
  let x0 = w, y0 = h, x1 = -1, y1 = -1;
  for (let y = 0; y < h; y++) {
    const o = y * w;
    for (let x = 0; x < w; x++) {
      if (mask[o + x] > th) {
        if (x < x0) x0 = x;
        if (x > x1) x1 = x;
        if (y < y0) y0 = y;
        y1 = y;
      }
    }
  }
  if (x1 < 0) return null;
  return { x: x0, y: y0, w: x1 - x0 + 1, h: y1 - y0 + 1 };
}

// マスクを PNG 保存用に canvas へ（グレースケールを RGB に入れる。乗算済みアルファの劣化を避けるため）
export function maskToCanvas(mask, w, h) {
  const c = document.createElement('canvas');
  c.width = w; c.height = h;
  const ctx = c.getContext('2d');
  const img = ctx.createImageData(w, h);
  const d = img.data;
  for (let i = 0, j = 0; i < mask.length; i++, j += 4) {
    d[j] = d[j + 1] = d[j + 2] = mask[i];
    d[j + 3] = 255;
  }
  ctx.putImageData(img, 0, 0);
  return c;
}

// グレースケール画像（任意サイズ）を w×h のマスク配列に読み込む（拡大縮小込み）
export function maskFromImage(img, w, h) {
  const c = document.createElement('canvas');
  c.width = w; c.height = h;
  const ctx = c.getContext('2d', { willReadFrequently: true });
  ctx.imageSmoothingEnabled = true;
  ctx.imageSmoothingQuality = 'high';
  ctx.drawImage(img, 0, 0, w, h);
  const d = ctx.getImageData(0, 0, w, h).data;
  const m = new Uint8Array(w * h);
  for (let i = 0, j = 0; i < m.length; i++, j += 4) m[i] = d[j];
  return m;
}
