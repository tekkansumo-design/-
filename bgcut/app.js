import { refineInto, needsRefine, refinePad, guidedRefine, maskBBox, maskToCanvas, maskFromImage } from './mask.js';
import { store } from './store.js';
import { makeZip } from './zip.js';

// ---------------------------------------------------------------------------
// 定数・設定
// ---------------------------------------------------------------------------

const ORT_BASE = 'https://cdn.jsdelivr.net/npm/onnxruntime-web@1.22.0/dist/';
const HF = 'https://huggingface.co/briaai/RMBG-1.4/resolve/main/onnx/';
const NORMS = {
  rmbg: { mean: [0.5, 0.5, 0.5], std: [1, 1, 1] },
  isnet: { mean: [0.485, 0.456, 0.406], std: [1, 1, 1] },
  imagenet: { mean: [0.485, 0.456, 0.406], std: [0.229, 0.224, 0.225] },
};
const MODELS = {
  'rmbg-q': { label: '標準（RMBG-1.4 軽量版・約44MB）', url: HF + 'model_quantized.onnx', size: 1024, norm: 'rmbg' },
  'rmbg': { label: '高画質（RMBG-1.4・約176MB）', url: HF + 'model.onnx', size: 1024, norm: 'rmbg' },
  'custom': { label: 'URL を指定する' },
  'file': { label: '端末の .onnx ファイルを使う' },
};
const LOCAL_MODEL_URL = 'https://bgcut.local/model/';

const ASPECTS = [
  { id: 'orig', label: '元のまま' },
  { id: '1:1', label: '1:1', r: 1 },
  { id: '4:5', label: '4:5', r: 4 / 5 },
  { id: '3:4', label: '3:4', r: 3 / 4 },
  { id: '2:3', label: '2:3', r: 2 / 3 },
  { id: '9:16', label: '9:16', r: 9 / 16 },
  { id: '16:9', label: '16:9', r: 16 / 9 },
];

const BG_PRESETS = [
  { type: 'none', tag: '透明' },
  { type: 'color', color: '#ffffff' },
  { type: 'color', color: '#f1f1f1' },
  { type: 'color', color: '#000000' },
  { type: 'color', color: '#f3e5d0' },
  { type: 'color', color: '#fde2e4' },
  { type: 'color', color: '#dbe8fb' },
  { type: 'color', color: '#ddf3e0' },
  { type: 'color', color: '#fff1c2' },
  { type: 'gradient', colors: ['#fdfbfb', '#e2e5ea'] },
  { type: 'gradient', colors: ['#fbc2eb', '#a6c1ee'] },
  { type: 'gradient', colors: ['#a1c4fd', '#c2e9fb'] },
  { type: 'gradient', colors: ['#ffecd2', '#fcb69f'] },
  { type: 'gradient', colors: ['#d4fc79', '#96e6a1'] },
  { type: 'gradient', colors: ['#4b4f58', '#141518'] },
  { type: 'blur', amount: 24, tag: 'ぼかし' },
];

const DEFAULT_SETTINGS = {
  model: 'rmbg-q',
  customUrl: '',
  customSize: 1024,
  customNorm: 'isnet',
  fileModelName: '',
  maxSide: 2048,
  guided: true,
  format: 'png',
  template: { bg: { type: 'color', color: '#ffffff' }, aspect: 'orig', shadow: false, margin: 0.08 },
};

function loadSettings() {
  try {
    const s = JSON.parse(localStorage.getItem('bgcut-settings') || '{}');
    return { ...DEFAULT_SETTINGS, ...s, template: { ...DEFAULT_SETTINGS.template, ...(s.template || {}) } };
  } catch (_) {
    return structuredClone(DEFAULT_SETTINGS);
  }
}
const settings = loadSettings();
function saveSettings() {
  try { localStorage.setItem('bgcut-settings', JSON.stringify(settings)); } catch (_) {}
}

// ---------------------------------------------------------------------------
// 小物
// ---------------------------------------------------------------------------

const $ = (s) => document.querySelector(s);
const $$ = (s) => [...document.querySelectorAll(s)];
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const nextFrame = () => new Promise((r) => requestAnimationFrame(() => r()));
const uid = () => Date.now().toString(36) + Math.random().toString(36).slice(2, 8);

let toastTimer = 0;
function toast(msg, ms = 2400) {
  const t = $('#toast');
  t.textContent = msg;
  t.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.remove('show'), ms);
}

function canvas(w, h) {
  const c = document.createElement('canvas');
  c.width = Math.max(1, Math.round(w));
  c.height = Math.max(1, Math.round(h));
  return c;
}

function canvasToBlob(c, type = 'image/png', q) {
  return new Promise((resolve, reject) => c.toBlob((b) => (b ? resolve(b) : reject(new Error('画像を作れませんでした'))), type, q));
}

async function decodeImage(blob) {
  try {
    return await createImageBitmap(blob, { imageOrientation: 'from-image' });
  } catch (_) {
    // 古い Safari など
    const url = URL.createObjectURL(blob);
    try {
      const img = new Image();
      img.src = url;
      await img.decode();
      return img;
    } finally {
      URL.revokeObjectURL(url);
    }
  }
}

function baseName(name) { return (name || 'image').replace(/\.[^.]+$/, ''); }
function bgKey(bg) { return JSON.stringify(bg); }

// ---------------------------------------------------------------------------
// 背景削除モデル（ワーカー）
// ---------------------------------------------------------------------------

const seg = {
  worker: null,
  key: '',
  ready: null,
  backend: '',
  pending: new Map(),
  seq: 0,
};

function modelSpec() {
  const m = settings.model;
  if (m === 'custom') {
    return { url: settings.customUrl, size: settings.customSize | 0 || 1024, ...NORMS[settings.customNorm] };
  }
  if (m === 'file') {
    return { url: LOCAL_MODEL_URL + encodeURIComponent(settings.fileModelName), size: settings.customSize | 0 || 1024, ...NORMS[settings.customNorm] };
  }
  const p = MODELS[m] || MODELS['rmbg-q'];
  return { url: p.url, size: p.size, ...NORMS[p.norm] };
}

function workerCall(msg) {
  const id = ++seg.seq;
  return new Promise((resolve, reject) => {
    seg.pending.set(id, { resolve, reject });
    seg.worker.postMessage({ ...msg, id }, msg.pixels ? [msg.pixels.buffer] : []);
  });
}

function ensureWorker() {
  if (seg.worker) return;
  seg.worker = new Worker('segment-worker.js');
  seg.worker.onmessage = (e) => {
    const m = e.data;
    if (m.type === 'progress') { onModelProgress(m); return; }
    const p = seg.pending.get(m.id);
    if (!p) return;
    seg.pending.delete(m.id);
    if (m.type === 'error') p.reject(new Error(m.message));
    else p.resolve(m);
  };
  seg.worker.onerror = (e) => {
    for (const p of seg.pending.values()) p.reject(new Error(e.message || 'ワーカーでエラーが起きました'));
    seg.pending.clear();
  };
}

async function ensureModel() {
  const spec = modelSpec();
  if (!spec.url) throw new Error('設定でモデルの URL を指定してください');
  const key = spec.url;
  if (seg.ready && seg.key === key) return seg.ready;
  ensureWorker();
  seg.key = key;
  showModelStatus('モデルを準備しています…', null);
  seg.ready = workerCall({ type: 'init', ortBase: settings.ortBase || ORT_BASE, modelUrl: spec.url, key })
    .then((r) => {
      seg.backend = r.backend;
      hideModelStatus();
      return r;
    })
    .catch((e) => {
      seg.ready = null;
      seg.key = '';
      showModelStatus('モデルを読み込めませんでした: ' + e.message, null, true);
      throw e;
    });
  return seg.ready;
}

function onModelProgress(m) {
  if (m.phase === 'model') {
    if (m.cached) { showModelStatus('保存済みのモデルを読み込んでいます…', null); return; }
    const mb = (m.loaded / 1048576).toFixed(1);
    const tot = m.total ? (m.total / 1048576).toFixed(0) : '?';
    showModelStatus(`初回だけモデルをダウンロードしています… ${mb} / ${tot} MB`, m.total ? m.loaded / m.total : null);
  } else if (m.phase === 'compile') {
    showModelStatus('モデルを起動しています…', null);
  }
}

function showModelStatus(text, frac, error = false) {
  const el = $('#modelStatus');
  el.hidden = false;
  el.classList.toggle('error', error);
  el.innerHTML = '';
  const t = document.createElement('div');
  t.textContent = text;
  el.append(t);
  if (!error) {
    const track = document.createElement('div');
    track.className = 'bar-track';
    const fill = document.createElement('div');
    fill.className = 'bar-fill' + (frac == null ? ' indeterminate' : '');
    if (frac != null) fill.style.width = (frac * 100).toFixed(1) + '%';
    track.append(fill);
    el.append(track);
  } else {
    const row = document.createElement('div');
    row.className = 'field-row';
    const retry = document.createElement('button');
    retry.className = 'btn';
    retry.textContent = 'もう一度試す';
    retry.onclick = () => { hideModelStatus(); pumpQueue(); };
    const set = document.createElement('button');
    set.className = 'btn ghost';
    set.textContent = '設定を開く';
    set.onclick = openSettings;
    row.append(retry, set);
    el.append(row);
  }
}
function hideModelStatus() { $('#modelStatus').hidden = true; }

// 画像からマスク（作業解像度、0〜255）を作る
async function segment(item) {
  await ensureModel();
  const spec = modelSpec();
  const s = spec.size;
  const c = canvas(s, s);
  const ctx = c.getContext('2d', { willReadFrequently: true });
  ctx.imageSmoothingQuality = 'high';
  ctx.drawImage(item.src, 0, 0, s, s);
  const pixels = new Uint8ClampedArray(ctx.getImageData(0, 0, s, s).data.buffer);
  const r = await workerCall({ type: 'run', pixels, size: s, mean: spec.mean, std: spec.std });
  // 低解像度のマスクを作業解像度へ拡大
  const mc = maskToCanvas(r.mask, s, s);
  const mask = maskFromImage(mc, item.w, item.h);
  if (settings.guided) {
    await nextFrame();
    guidedRefine(mask, item.srcData, item.w, item.h);
  }
  return mask;
}

// ---------------------------------------------------------------------------
// 写真データ
//   rec  … 一覧に並ぶ軽い記録（Blob と設定だけ）。IndexedDB にそのまま保存する
//   item … 編集・処理のために展開したもの（画素やマスクを持つ）。同時に持つのは少数だけ
// ---------------------------------------------------------------------------

const recs = [];
let current = null; // 編集中の item

function newRec(file) {
  const t = settings.template;
  return {
    id: uid(),
    name: file.name || 'image.png',
    created: Date.now() + recs.length,
    status: 'queued',
    file,
    autoMaskBlob: null,
    maskBlob: null,
    bgBlob: null,
    thumbBlob: null,
    bg: structuredClone(t.bg),
    edge: { shift: 0, feather: 0 },
    layout: { aspect: t.aspect, margin: t.margin, shadow: t.shadow },
    place: null,
  };
}

function persistFields(rec, keys) {
  const f = {};
  for (const k of keys) f[k] = rec[k];
  return store.patch(rec.id, f);
}
const REC_KEYS = ['name', 'created', 'status', 'file', 'autoMaskBlob', 'maskBlob', 'bgBlob', 'thumbBlob', 'bg', 'edge', 'layout', 'place', 'error'];

// rec を展開する（元画像を作業解像度で読み込み、マスクがあれば読み込む）
async function hydrate(rec) {
  const img = await decodeImage(rec.file);
  const iw = img.width, ih = img.height;
  const k = Math.min(1, settings.maxSide / Math.max(iw, ih));
  const w = Math.max(1, Math.round(iw * k)), h = Math.max(1, Math.round(ih * k));
  const src = canvas(w, h);
  const sctx = src.getContext('2d', { willReadFrequently: true });
  sctx.imageSmoothingQuality = 'high';
  sctx.drawImage(img, 0, 0, w, h);
  if (img.close) img.close();
  const srcData = sctx.getImageData(0, 0, w, h).data;
  const item = {
    rec, w, h, src, srcData,
    auto: null, manual: null, final: null,
    cutout: canvas(w, h), stroke: null,
    undo: [], redo: [],
    bgImg: null, blurCache: null, maskVer: 0,
    maskDirty: false,
  };
  if (rec.autoMaskBlob) item.auto = maskFromImage(await decodeImage(rec.autoMaskBlob), w, h);
  if (rec.maskBlob) item.manual = maskFromImage(await decodeImage(rec.maskBlob), w, h);
  else if (item.auto) item.manual = item.auto.slice();
  if (rec.bgBlob) item.bgImg = await decodeImage(rec.bgBlob);
  if (item.manual) {
    item.final = new Uint8Array(w * h);
    rebuildAll(item);
  }
  return item;
}

function rebuildAll(item) {
  item.maskVer++;
  refineInto(item.manual, item.final, item.w, item.h, item.rec.edge);
  updateCutout(item, { x: 0, y: 0, w: item.w, h: item.h });
}

// 切り抜き画像（元画像 + マスクをアルファに）の矩形部分を更新
function updateCutout(item, r) {
  const ctx = item.cutout.getContext('2d');
  const img = ctx.createImageData(r.w, r.h);
  const d = img.data, s = item.srcData, f = item.final, W = item.w;
  for (let y = 0; y < r.h; y++) {
    let si = ((r.y + y) * W + r.x) * 4;
    let mi = (r.y + y) * W + r.x;
    let di = y * r.w * 4;
    for (let x = 0; x < r.w; x++, si += 4, di += 4, mi++) {
      d[di] = s[si]; d[di + 1] = s[si + 1]; d[di + 2] = s[si + 2]; d[di + 3] = f[mi];
    }
  }
  ctx.putImageData(img, r.x, r.y);
}

// 出力フレームの大きさ
function frameSize(item) {
  const a = ASPECTS.find((x) => x.id === item.rec.layout.aspect);
  if (!a || !a.r) return { w: item.w, h: item.h };
  const L = Math.max(item.w, item.h);
  return a.r >= 1 ? { w: L, h: Math.round(L / a.r) } : { w: Math.round(L * a.r), h: L };
}

// 被写体をフレームの中央に余白つきで置く
function autoPlace(item) {
  const fr = frameSize(item);
  const lay = item.rec.layout;
  if (lay.aspect === 'orig') {
    item.rec.place = { x: 0, y: 0, scale: 1 };
    return;
  }
  const bb = maskBBox(item.final || item.manual, item.w, item.h) || { x: 0, y: 0, w: item.w, h: item.h };
  const m = lay.margin;
  const iw = fr.w * (1 - 2 * m), ih = fr.h * (1 - 2 * m);
  const scale = Math.min(3, iw / bb.w, ih / bb.h);
  item.rec.place = {
    x: (fr.w - bb.w * scale) / 2 - bb.x * scale,
    y: (fr.h - bb.h * scale) / 2 - bb.y * scale,
    scale,
  };
}

// 背景を描く（フレーム座標系）
function drawBackground(ctx, item, fw, fh) {
  const bg = item.rec.bg;
  if (bg.type === 'color') {
    ctx.fillStyle = bg.color;
    ctx.fillRect(0, 0, fw, fh);
  } else if (bg.type === 'gradient') {
    const g = ctx.createLinearGradient(0, 0, fw * 0.35, fh);
    g.addColorStop(0, bg.colors[0]);
    g.addColorStop(1, bg.colors[1]);
    ctx.fillStyle = g;
    ctx.fillRect(0, 0, fw, fh);
  } else if (bg.type === 'image' && item.bgImg) {
    drawCover(ctx, item.bgImg, fw, fh);
  } else if (bg.type === 'blur') {
    drawAligned(ctx, blurredSrc(item, bg.amount || 24), item, fw, fh);
  }
}

function drawCover(ctx, img, fw, fh) {
  const iw = img.width, ih = img.height;
  const k = Math.max(fw / iw, fh / ih);
  const w = iw * k, h = ih * k;
  ctx.imageSmoothingQuality = 'high';
  ctx.drawImage(img, (fw - w) / 2, (fh - h) / 2, w, h);
}

// 元の写真の「背景だけ」をぼかしたもの。被写体がにじんで写り込まないよう、
// 被写体部分を除いて縮小し、穴を周りの色で埋めてから拡大する（ctx.filter 非対応でも同じ見た目）
function blurredSrc(item, amount) {
  const bc = item.blurCache;
  if (bc && bc.amount === amount && bc.ver === item.maskVer) return bc.canvas;
  const W = item.w, H = item.h, L = Math.max(W, H);
  // 背景のみ（被写体のアルファを抜いた）画像
  let cur = canvas(W, H);
  const bctx = cur.getContext('2d');
  const img = bctx.createImageData(W, H);
  const d = img.data, s = item.srcData, f = item.final;
  for (let i = 0, j = 0; i < f.length; i++, j += 4) {
    d[j] = s[j]; d[j + 1] = s[j + 1]; d[j + 2] = s[j + 2];
    const a = 255 - f[i];
    d[j + 3] = a > 200 ? 255 : a < 40 ? 0 : a; // 境界の半透明は控えめに
  }
  bctx.putImageData(img, 0, 0);
  // 段階的に縮小（透明部分は自動的に重み付き平均から外れる）
  const small = Math.max(8, Math.round(L / amount));
  while (Math.max(cur.width, cur.height) * 0.5 > small) {
    const t = canvas(cur.width * 0.5, cur.height * 0.5);
    const tc = t.getContext('2d');
    tc.imageSmoothingQuality = 'high';
    tc.drawImage(cur, 0, 0, t.width, t.height);
    cur = t;
  }
  // 小さい画像上で、背景の色がない画素を周りの色で埋める
  const sw = cur.width, sh = cur.height;
  const sctx = cur.getContext('2d', { willReadFrequently: true });
  const sd = sctx.getImageData(0, 0, sw, sh);
  const px = sd.data;
  let known = new Uint8Array(sw * sh);
  let any = false;
  for (let i = 0; i < known.length; i++) if (px[i * 4 + 3] > 8) { known[i] = 1; any = true; }
  if (!any) { // 全面が被写体なら元画像をそのまま使う
    for (let i = 0; i < known.length; i++) known[i] = 1;
  }
  for (let iter = 0; iter < sw + sh; iter++) {
    let changed = false;
    const next = known.slice();
    for (let y = 0; y < sh; y++) {
      for (let x = 0; x < sw; x++) {
        const i = y * sw + x;
        if (known[i]) continue;
        let r = 0, g = 0, b = 0, n = 0;
        for (let dy = -1; dy <= 1; dy++) {
          for (let dx = -1; dx <= 1; dx++) {
            const xx = x + dx, yy = y + dy;
            if (xx < 0 || yy < 0 || xx >= sw || yy >= sh) continue;
            const k = yy * sw + xx;
            if (!known[k]) continue;
            r += px[k * 4]; g += px[k * 4 + 1]; b += px[k * 4 + 2]; n++;
          }
        }
        if (n) { px[i * 4] = r / n; px[i * 4 + 1] = g / n; px[i * 4 + 2] = b / n; next[i] = 1; changed = true; }
      }
    }
    known = next;
    if (!changed) break;
  }
  for (let i = 0; i < known.length; i++) px[i * 4 + 3] = 255;
  sctx.putImageData(sd, 0, 0);
  // 少しずつ拡大して滑らかにする
  let c = cur;
  while (c.width * 2 < W) {
    const t = canvas(c.width * 2, c.height * 2);
    const tc = t.getContext('2d');
    tc.imageSmoothingQuality = 'high';
    tc.drawImage(c, 0, 0, t.width, t.height);
    c = t;
  }
  item.blurCache = { amount, ver: item.maskVer, canvas: c };
  return c;
}

// 被写体と同じ位置関係で元の背景を置く。フレームを覆いきれないときは拡大する
function drawAligned(ctx, img, item, fw, fh) {
  const p = item.rec.place;
  let x = p.x, y = p.y, w = item.w * p.scale, h = item.h * p.scale;
  // フレーム中心を基準に拡大したとき、四辺がフレームの外に出る倍率
  const cx = fw / 2, cy = fh / 2;
  const need = [
    x > 0 ? cx / (cx - x) : 1,
    y > 0 ? cy / (cy - y) : 1,
    x + w < fw ? cx / (x + w - cx) : 1,
    y + h < fh ? cy / (y + h - cy) : 1,
  ];
  if (need.some((v) => !(v > 0) || v > 20)) { drawCover(ctx, img, fw, fh); return; }
  const k = Math.max(1, ...need);
  x = fw / 2 + (x - fw / 2) * k; y = fh / 2 + (y - fh / 2) * k;
  ctx.imageSmoothingQuality = 'high';
  ctx.drawImage(img, x, y, w * k, h * k);
}

// 合成。ctx はフレーム座標で描ける状態にしておく
function drawComposite(ctx, item, fw, fh, opt = {}) {
  if (!opt.skipBg) drawBackground(ctx, item, fw, fh);
  const p = item.rec.place;
  if (opt.ghost) {
    ctx.save();
    ctx.globalAlpha = 0.28;
    ctx.drawImage(item.src, p.x, p.y, item.w * p.scale, item.h * p.scale);
    ctx.restore();
  }
  ctx.save();
  if (item.rec.layout.shadow) {
    const L = Math.max(fw, fh);
    ctx.shadowColor = 'rgba(0,0,0,0.32)';
    // 影のぼかし量は座標変換の影響を受けないので、描画倍率を掛けて見た目を揃える
    const k = opt.shadowScale || 1;
    ctx.shadowBlur = L * 0.025 * k;
    ctx.shadowOffsetY = L * 0.012 * k;
  }
  ctx.imageSmoothingQuality = 'high';
  ctx.drawImage(item.cutout, p.x, p.y, item.w * p.scale, item.h * p.scale);
  ctx.restore();
}

async function renderOutput(item, fmt) {
  const fr = frameSize(item);
  const c = canvas(fr.w, fr.h);
  const ctx = c.getContext('2d');
  if (fmt === 'jpeg' && item.rec.bg.type === 'none') {
    ctx.fillStyle = '#ffffff';
    ctx.fillRect(0, 0, fr.w, fr.h);
  }
  drawComposite(ctx, item, fr.w, fr.h);
  return canvasToBlob(c, fmt === 'jpeg' ? 'image/jpeg' : 'image/png', 0.92);
}

async function renderThumb(item) {
  const fr = frameSize(item);
  const k = 360 / Math.max(fr.w, fr.h);
  const c = canvas(fr.w * k, fr.h * k);
  const ctx = c.getContext('2d');
  ctx.scale(c.width / fr.w, c.height / fr.h);
  drawComposite(ctx, item, fr.w, fr.h, { shadowScale: k });
  return canvasToBlob(c, 'image/png');
}

// item の変更を rec と IndexedDB へ書き戻す
async function commit(item, { masks = false } = {}) {
  const rec = item.rec;
  const keys = ['bg', 'edge', 'layout', 'place', 'bgBlob', 'status'];
  if (masks && item.manual) {
    rec.maskBlob = await canvasToBlob(maskToCanvas(item.manual, item.w, item.h));
    keys.push('maskBlob');
    item.maskDirty = false;
  }
  rec.thumbBlob = await renderThumb(item);
  keys.push('thumbBlob');
  await persistFields(rec, keys);
  updateTile(rec);
}

// ---------------------------------------------------------------------------
// 全自動処理（取り込み → 背景削除 → テンプレート適用）
// ---------------------------------------------------------------------------

let pumping = false;

async function addFiles(files) {
  const imgs = [...files].filter((f) => f.type.startsWith('image/') || /\.(jpe?g|png|webp|heic|heif|avif|bmp|gif)$/i.test(f.name));
  if (!imgs.length) { toast('画像ファイルが見つかりませんでした'); return; }
  for (const f of imgs) {
    const rec = newRec(f);
    recs.push(rec);
    addTile(rec);
    await persistFields(rec, REC_KEYS);
  }
  updateGridHead();
  pumpQueue();
}

async function pumpQueue() {
  if (pumping) return;
  pumping = true;
  try {
    for (;;) {
      const rec = recs.find((r) => r.status === 'queued');
      if (!rec) break;
      rec.status = 'processing';
      updateTile(rec);
      try {
        await processRec(rec);
      } catch (e) {
        console.error(e);
        if (/モデル/.test(e.message) || !seg.ready) {
          // モデルの問題なら待機に戻して止める（設定を直してからやり直せる）
          rec.status = 'queued';
          updateTile(rec);
          break;
        }
        rec.status = 'error';
        rec.error = e.message;
        updateTile(rec);
        persistFields(rec, ['status', 'error']);
      }
    }
  } finally {
    pumping = false;
  }
}

async function processRec(rec) {
  const item = current && current.rec === rec ? current : await hydrate(rec);
  const mask = await segment(item);
  item.auto = mask;
  item.manual = mask.slice();
  item.final = new Uint8Array(item.w * item.h);
  item.undo = []; item.redo = [];
  rebuildAll(item);
  autoPlace(item);
  rec.autoMaskBlob = await canvasToBlob(maskToCanvas(mask, item.w, item.h));
  rec.status = 'done';
  await persistFields(rec, ['autoMaskBlob', 'status']);
  await commit(item, { masks: true });
  if (current && current.rec === rec) {
    // 処理中に編集画面で開かれていたら、処理済みのものに差し替える
    current = item;
    hint('');
    fitView();
    requestRender();
    refreshEditorControls();
  }
}

// ---------------------------------------------------------------------------
// 一覧画面
// ---------------------------------------------------------------------------

const tileEls = new Map();
const thumbUrls = new Map();

function addTile(rec) {
  const el = document.createElement('div');
  el.className = 'tile';
  el.tabIndex = 0;
  el.innerHTML = `
    <div class="thumb"><img alt=""></div>
    <div class="meta"><span class="name"></span></div>
    <div class="state" hidden></div>
    <button class="del" title="削除" aria-label="削除"><svg viewBox="0 0 24 24"><path d="M19 6.4 17.6 5 12 10.6 6.4 5 5 6.4 10.6 12 5 17.6 6.4 19 12 13.4 17.6 19 19 17.6 13.4 12z"/></svg></button>`;
  el.querySelector('.name').textContent = rec.name;
  el.addEventListener('click', (e) => {
    if (e.target.closest('.del')) { removeRec(rec); return; }
    openEditor(rec);
  });
  el.addEventListener('keydown', (e) => { if (e.key === 'Enter') openEditor(rec); });
  $('#grid').append(el);
  tileEls.set(rec.id, el);
  updateTile(rec);
}

function updateTile(rec) {
  const el = tileEls.get(rec.id);
  if (!el) return;
  const img = el.querySelector('img');
  const blob = rec.thumbBlob || rec.file;
  const old = thumbUrls.get(rec.id);
  if (!old || old.blob !== blob) {
    if (old) URL.revokeObjectURL(old.url);
    const url = URL.createObjectURL(blob);
    thumbUrls.set(rec.id, { blob, url });
    img.src = url;
  }
  img.classList.toggle('checker', !!rec.thumbBlob && rec.bg.type === 'none');
  const st = el.querySelector('.state');
  st.hidden = rec.status === 'done';
  if (rec.status === 'queued') st.innerHTML = '<div>待機中</div>';
  else if (rec.status === 'processing') st.innerHTML = '<div><div class="spinner"></div>背景を削除中…</div>';
  else if (rec.status === 'error') { st.textContent = '失敗しました。タップで開き直せます'; }
  updateGridHead();
}

async function removeRec(rec) {
  if (rec.status === 'processing') { toast('処理中の写真は終わってから削除してください'); return; }
  const i = recs.indexOf(rec);
  if (i >= 0) recs.splice(i, 1);
  const el = tileEls.get(rec.id);
  if (el) el.remove();
  tileEls.delete(rec.id);
  const u = thumbUrls.get(rec.id);
  if (u) URL.revokeObjectURL(u.url);
  thumbUrls.delete(rec.id);
  await store.remove(rec.id);
  updateGridHead();
}

function updateGridHead() {
  $('#gridHead').hidden = recs.length === 0;
  const done = recs.filter((r) => r.status === 'done').length;
  $('#gridCount').textContent = done === recs.length ? `${recs.length} 枚` : `${done} / ${recs.length} 枚 完了`;
}

// 背景の見本ボタンを作る
function buildSwatches(container, getBg, setBg, { withImage = false } = {}) {
  container.innerHTML = '';
  const make = (bg) => {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'swatch';
    if (bg.type === 'none') b.classList.add('checker');
    else if (bg.type === 'color') b.style.background = bg.color;
    else if (bg.type === 'gradient') b.style.background = `linear-gradient(160deg, ${bg.colors[0]}, ${bg.colors[1]})`;
    else if (bg.type === 'blur') b.style.background = 'radial-gradient(circle at 35% 40%, #9fb6d8, #6b7f9e 55%, #3e4a5e)';
    if (bg.tag) { const t = document.createElement('span'); t.className = 'tag'; t.textContent = bg.tag; b.append(t); }
    b.title = bg.tag || bg.color || '';
    b.dataset.key = bgKey(bg);
    b.onclick = () => setBg(structuredClone(bg));
    return b;
  };
  for (const bg of BG_PRESETS) container.append(make(bg));
  // 好きな色
  const pick = document.createElement('label');
  pick.className = 'swatch icon';
  pick.title = '好きな色';
  pick.style.background = 'conic-gradient(#f43, #fb3, #6d4, #3cf, #66f, #d4f, #f43)';
  const ci = document.createElement('input');
  ci.type = 'color';
  ci.value = '#7c6cff';
  ci.oninput = () => setBg({ type: 'color', color: ci.value, custom: true });
  pick.append(ci);
  pick.dataset.key = 'custom';
  container.append(pick);
  if (withImage) {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'swatch icon';
    b.title = '画像を背景にする';
    b.innerHTML = '<svg viewBox="0 0 24 24"><path d="M21 19V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2ZM8.5 13.5l2.5 3 3.5-4.5 4.5 6H5l3.5-4.5Z"/></svg>';
    b.dataset.key = 'image';
    b.onclick = () => $('#bgFileInput').click();
    container.append(b);
  }
  markSwatch(container, getBg());
}

function markSwatch(container, bg) {
  const key = bg.type === 'image' ? 'image' : bg.custom ? 'custom' : bgKey(bg);
  for (const el of container.children) el.classList.toggle('active', el.dataset.key === key);
}

function initHome() {
  const fi = $('#fileInput');
  fi.onchange = () => { if (fi.files.length) addFiles(fi.files); fi.value = ''; };
  const drop = $('#drop');
  for (const ev of ['dragenter', 'dragover']) drop.addEventListener(ev, (e) => { e.preventDefault(); drop.classList.add('over'); });
  for (const ev of ['dragleave', 'drop']) drop.addEventListener(ev, () => drop.classList.remove('over'));
  drop.addEventListener('drop', (e) => { e.preventDefault(); if (e.dataTransfer.files.length) addFiles(e.dataTransfer.files); });
  document.addEventListener('dragover', (e) => e.preventDefault());
  document.addEventListener('drop', (e) => { e.preventDefault(); if (!$('#home').hidden && e.dataTransfer.files.length) addFiles(e.dataTransfer.files); });
  document.addEventListener('paste', (e) => {
    const files = [...(e.clipboardData?.files || [])];
    if (files.length && !$('#home').hidden) addFiles(files);
  });

  const t = settings.template;
  const sw = $('#tplSwatches');
  buildSwatches(sw, () => t.bg, (bg) => { t.bg = bg; saveSettings(); markSwatch(sw, bg); });
  const sel = $('#tplAspect');
  for (const a of ASPECTS) {
    const o = document.createElement('option');
    o.value = a.id;
    o.textContent = a.id === 'orig' ? '元の写真のまま' : `${a.label}（被写体を中央に配置）`;
    sel.append(o);
  }
  sel.value = t.aspect;
  sel.onchange = () => { t.aspect = sel.value; saveSettings(); };
  const sh = $('#tplShadow');
  sh.checked = t.shadow;
  sh.onchange = () => { t.shadow = sh.checked; saveSettings(); };

  $('#btnExportAll').onclick = exportAll;
  $('#btnClearAll').onclick = async () => {
    if (!confirm('一覧の写真をすべて削除します。よろしいですか？')) return;
    for (const r of [...recs]) if (r.status !== 'processing') await removeRec(r);
  };
}

async function exportAll() {
  const done = recs.filter((r) => r.status === 'done');
  if (!done.length) { toast('保存できる写真がまだありません'); return; }
  const btn = $('#btnExportAll');
  btn.disabled = true;
  const files = [];
  const used = new Set();
  try {
    for (let i = 0; i < done.length; i++) {
      btn.textContent = `書き出し中 ${i + 1}/${done.length}`;
      const rec = done[i];
      const item = current && current.rec === rec ? current : await hydrate(rec);
      if (!rec.place) autoPlace(item);
      const fmt = settings.format;
      const blob = await renderOutput(item, fmt);
      let name = `${baseName(rec.name)}_bgcut.${fmt === 'jpeg' ? 'jpg' : 'png'}`;
      for (let n = 2; used.has(name); n++) name = `${baseName(rec.name)}_bgcut_${n}.${fmt === 'jpeg' ? 'jpg' : 'png'}`;
      used.add(name);
      files.push({ name, blob });
      await nextFrame();
    }
    const zip = await makeZip(files);
    downloadBlob(zip, `bgcut_${new Date().toISOString().slice(0, 10)}.zip`);
  } catch (e) {
    toast('書き出しに失敗しました: ' + e.message);
  } finally {
    btn.disabled = false;
    btn.textContent = 'すべて保存 (ZIP)';
  }
}

function downloadBlob(blob, name) {
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = name;
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(a.href), 30000);
}

// ---------------------------------------------------------------------------
// 編集画面
// ---------------------------------------------------------------------------

const view = { zoom: 1, panX: 0, panY: 0, dpr: 1, cw: 0, ch: 0 };
const ui = {
  tab: 'bg',
  mode: 'erase',
  brushSize: 40,
  brushHard: 0.6,
  ghost: true,
  comparing: false,
  cursor: null, // 画面座標のブラシ位置
  spaceDown: false,
};
let renderQueued = false;
let saveTimer = 0;

function scheduleSave(masks = false) {
  if (!current) return;
  if (masks) current.maskDirty = true;
  clearTimeout(saveTimer);
  const item = current;
  saveTimer = setTimeout(() => commit(item, { masks: item.maskDirty }), 700);
}

async function flushSave() {
  clearTimeout(saveTimer);
  if (current && current.manual) await commit(current, { masks: current.maskDirty });
}

async function openEditor(rec) {
  if (current && current.rec !== rec) await flushSave();
  $('#home').hidden = true;
  $('#homeBar').hidden = true;
  $('#editor').hidden = false;
  $('#editBar').hidden = false;
  $('#editTitle').textContent = rec.name;
  hint('読み込み中…', 0);
  if (!current || current.rec !== rec) {
    current = null;
    try {
      current = await hydrate(rec);
    } catch (e) {
      toast('写真を開けませんでした: ' + e.message);
      closeEditor();
      return;
    }
  }
  if (!current.manual && rec.status === 'error') {
    // 失敗していたものは開いたときにやり直す
    rec.status = 'queued';
    updateTile(rec);
    pumpQueue();
  }
  if (current.manual && !rec.place) autoPlace(current);
  hint(current.manual ? '' : '背景を削除しています…', current.manual ? 1 : 0);
  resizeStage();
  fitView();
  refreshEditorControls();
  requestRender();
}

async function closeEditor() {
  await flushSave();
  $('#editor').hidden = true;
  $('#editBar').hidden = true;
  $('#home').hidden = false;
  $('#homeBar').hidden = false;
  // 処理待ちに使う可能性がなければ解放してメモリを空ける
  if (current && current.rec.status !== 'processing') current = null;
  updateGridHead();
}

let hintTimer = 0;
function hint(text, ms = 1600) {
  const el = $('#stageHint');
  clearTimeout(hintTimer);
  if (!text) { el.classList.remove('show'); return; }
  el.textContent = text;
  el.classList.add('show');
  if (ms) hintTimer = setTimeout(() => el.classList.remove('show'), ms);
}

function resizeStage() {
  const st = $('#stage');
  const c = $('#view');
  view.dpr = Math.min(3, window.devicePixelRatio || 1);
  view.cw = st.clientWidth;
  view.ch = st.clientHeight;
  c.width = Math.max(1, Math.round(view.cw * view.dpr));
  c.height = Math.max(1, Math.round(view.ch * view.dpr));
}

function fitView() {
  if (!current) return;
  const fr = frameSize(current);
  const pad = 20;
  const z = Math.min((view.cw - pad * 2) / fr.w, (view.ch - pad * 2) / fr.h);
  view.zoom = Math.max(0.01, z);
  view.panX = (view.cw - fr.w * view.zoom) / 2;
  view.panY = (view.ch - fr.h * view.zoom) / 2;
}

function requestRender() {
  if (renderQueued) return;
  renderQueued = true;
  requestAnimationFrame(render);
}

let checkerPattern = null;
function getChecker(ctx) {
  if (!checkerPattern) {
    const c = canvas(16, 16);
    const x = c.getContext('2d');
    x.fillStyle = '#ffffff'; x.fillRect(0, 0, 16, 16);
    x.fillStyle = '#d0d3d9'; x.fillRect(0, 0, 8, 8); x.fillRect(8, 8, 8, 8);
    checkerPattern = ctx.createPattern(c, 'repeat');
  }
  return checkerPattern;
}

function render() {
  renderQueued = false;
  const c = $('#view');
  const ctx = c.getContext('2d');
  ctx.setTransform(1, 0, 0, 1, 0, 0);
  ctx.clearRect(0, 0, c.width, c.height);
  const item = current;
  if (!item) return;
  const fr = item.manual ? frameSize(item) : { w: item.w, h: item.h };
  const d = view.dpr;
  ctx.setTransform(d * view.zoom, 0, 0, d * view.zoom, d * view.panX, d * view.panY);
  ctx.save();
  ctx.beginPath();
  ctx.rect(0, 0, fr.w, fr.h);
  ctx.clip();
  if (!item.manual || ui.comparing) {
    // 処理前・比較中は元の写真
    if (item.manual) {
      const p = item.rec.place;
      ctx.drawImage(item.src, p.x, p.y, item.w * p.scale, item.h * p.scale);
    } else {
      ctx.drawImage(item.src, 0, 0);
    }
  } else {
    if (item.rec.bg.type === 'none' || (item.rec.bg.type === 'image' && !item.bgImg)) {
      const pat = getChecker(ctx);
      if (pat.setTransform) pat.setTransform(new DOMMatrix().scale(1 / view.zoom));
      ctx.fillStyle = pat;
      ctx.fillRect(0, 0, fr.w, fr.h);
    }
    drawComposite(ctx, item, fr.w, fr.h, { ghost: ui.tab === 'fix' && ui.ghost, shadowScale: view.zoom * d });
  }
  ctx.restore();
  // フレームの外枠
  ctx.setTransform(1, 0, 0, 1, 0, 0);
  ctx.strokeStyle = 'rgba(127,127,127,.5)';
  ctx.lineWidth = 1;
  ctx.strokeRect(d * view.panX - 0.5, d * view.panY - 0.5, fr.w * view.zoom * d + 1, fr.h * view.zoom * d + 1);
  // ブラシの位置
  if (ui.cursor && ui.tab === 'fix' && ui.mode !== 'pan' && item.manual) {
    const r = (ui.brushSize / 2) * d;
    ctx.beginPath();
    ctx.arc(ui.cursor.x * d, ui.cursor.y * d, r, 0, Math.PI * 2);
    ctx.lineWidth = 2 * d;
    ctx.strokeStyle = 'rgba(0,0,0,.55)';
    ctx.stroke();
    ctx.lineWidth = 1 * d;
    ctx.strokeStyle = ui.mode === 'erase' ? '#ff5a5a' : '#38d27a';
    ctx.stroke();
    if (ui.brushHard < 0.98) {
      ctx.beginPath();
      ctx.setLineDash([3 * d, 3 * d]);
      ctx.arc(ui.cursor.x * d, ui.cursor.y * d, Math.max(1, r * ui.brushHard), 0, Math.PI * 2);
      ctx.stroke();
      ctx.setLineDash([]);
    }
  }
}

// 画面座標 → 元画像（作業解像度）座標
function toSrc(sx, sy) {
  const p = current.rec.place;
  const fx = (sx - view.panX) / view.zoom, fy = (sy - view.panY) / view.zoom;
  return { x: (fx - p.x) / p.scale, y: (fy - p.y) / p.scale };
}

// ---- ブラシ ----

const stroke = { active: false, last: null, rect: null, base: null };

function beginStroke(sx, sy) {
  const item = current;
  if (!item.stroke) item.stroke = new Uint8Array(item.w * item.h);
  stroke.base = item.manual.slice();
  pushUndo(stroke.base);
  stroke.active = true;
  stroke.rect = null;
  stroke.last = toSrc(sx, sy);
  stamp(stroke.last.x, stroke.last.y);
  flushStroke();
}

function moveStroke(sx, sy) {
  const p = toSrc(sx, sy);
  const R = brushRadiusSrc();
  const dx = p.x - stroke.last.x, dy = p.y - stroke.last.y;
  const dist = Math.hypot(dx, dy);
  const step = Math.max(1, R * 0.25);
  const n = Math.floor(dist / step);
  for (let i = 1; i <= n; i++) stamp(stroke.last.x + (dx * i) / n, stroke.last.y + (dy * i) / n);
  if (n > 0) stroke.last = p;
  flushStroke();
}

function endStroke() {
  if (!stroke.active) return;
  stroke.active = false;
  const item = current;
  if (stroke.rect) {
    // ストローク用バッファを掃除
    const r = stroke.rect;
    for (let y = r.y; y < r.y + r.h; y++) item.stroke.fill(0, y * item.w + r.x, y * item.w + r.x + r.w);
  }
  stroke.base = null;
  item.maskVer++;
  refreshUndoButtons();
  requestRender();
  scheduleSave(true);
}

function cancelStroke() {
  if (!stroke.active) return;
  const item = current;
  item.manual.set(stroke.base);
  item.undo.pop();
  const r = stroke.rect;
  stroke.active = false;
  if (r) {
    for (let y = r.y; y < r.y + r.h; y++) item.stroke.fill(0, y * item.w + r.x, y * item.w + r.x + r.w);
    applyRect(r);
  }
  stroke.base = null;
  refreshUndoButtons();
}

function brushRadiusSrc() {
  return Math.max(0.5, ui.brushSize / 2 / (view.zoom * current.rec.place.scale));
}

let pendingRect = null;
function stamp(cx, cy) {
  const item = current;
  const R = brushRadiusSrc();
  const W = item.w, H = item.h;
  const x0 = Math.max(0, Math.floor(cx - R)), y0 = Math.max(0, Math.floor(cy - R));
  const x1 = Math.min(W - 1, Math.ceil(cx + R)), y1 = Math.min(H - 1, Math.ceil(cy + R));
  if (x1 < x0 || y1 < y0) return;
  const hard = ui.brushHard;
  const inner = R * hard;
  const soft = Math.max(0.75, R - inner);
  const st = item.stroke, base = stroke.base, m = item.manual;
  const erase = ui.mode === 'erase';
  for (let y = y0; y <= y1; y++) {
    const dy = y + 0.5 - cy;
    const row = y * W;
    for (let x = x0; x <= x1; x++) {
      const dx = x + 0.5 - cx;
      const dd = Math.sqrt(dx * dx + dy * dy);
      if (dd > R) continue;
      let a = dd <= inner ? 255 : 255 * (1 - (dd - inner) / soft);
      if (a <= 0) continue;
      const i = row + x;
      if (a <= st[i]) continue;
      st[i] = a;
      const v = base[i];
      m[i] = erase ? (v * (255 - a)) / 255 : v + ((255 - v) * a) / 255;
    }
  }
  const r = { x: x0, y: y0, w: x1 - x0 + 1, h: y1 - y0 + 1 };
  pendingRect = unionRect(pendingRect, r);
  stroke.rect = unionRect(stroke.rect, r);
}

function unionRect(a, b) {
  if (!a) return { ...b };
  const x = Math.min(a.x, b.x), y = Math.min(a.y, b.y);
  return { x, y, w: Math.max(a.x + a.w, b.x + b.w) - x, h: Math.max(a.y + a.h, b.y + b.h) - y };
}

function flushStroke() {
  if (!pendingRect) return;
  applyRect(pendingRect);
  pendingRect = null;
}

// manual の変更を final・切り抜き画像へ反映（境界調整の影響範囲ぶん広げる）
function applyRect(r) {
  const item = current;
  const pad = needsRefine(item.rec.edge) ? refinePad(item.rec.edge) : 0;
  const x0 = Math.max(0, r.x - pad), y0 = Math.max(0, r.y - pad);
  const x1 = Math.min(item.w, r.x + r.w + pad), y1 = Math.min(item.h, r.y + r.h + pad);
  const rr = { x: x0, y: y0, w: x1 - x0, h: y1 - y0 };
  refineInto(item.manual, item.final, item.w, item.h, item.rec.edge, rr);
  updateCutout(item, rr);
  requestRender();
}

// ---- 元に戻す / やり直す ----

const UNDO_MAX = 15;
function pushUndo(snapshot) {
  const item = current;
  item.undo.push(snapshot || item.manual.slice());
  if (item.undo.length > UNDO_MAX) item.undo.shift();
  item.redo = [];
}
function undo() {
  const item = current;
  if (!item || !item.undo.length || stroke.active) return;
  item.redo.push(item.manual);
  item.manual = item.undo.pop();
  rebuildAll(item);
  refreshUndoButtons();
  requestRender();
  scheduleSave(true);
}
function redo() {
  const item = current;
  if (!item || !item.redo.length || stroke.active) return;
  item.undo.push(item.manual);
  item.manual = item.redo.pop();
  rebuildAll(item);
  refreshUndoButtons();
  requestRender();
  scheduleSave(true);
}
function refreshUndoButtons() {
  $('#btnUndo').disabled = !current || !current.undo.length;
  $('#btnRedo').disabled = !current || !current.redo.length;
}

// ---- ポインタ操作 ----

const pointers = new Map();
let gesture = null; // { type: 'stroke' | 'pan' | 'move' | 'pinch', ... }

function stagePos(e) {
  const r = $('#view').getBoundingClientRect();
  return { x: e.clientX - r.left, y: e.clientY - r.top };
}

function singleAction(e) {
  if (!current || !current.manual) return 'pan';
  if (ui.spaceDown || e.button === 1 || e.button === 2) return 'pan';
  if (ui.tab === 'fix' && ui.mode !== 'pan') return 'stroke';
  if (ui.tab === 'layout') return 'move';
  return 'pan';
}

function pinchInfo() {
  const [a, b] = [...pointers.values()];
  return { cx: (a.x + b.x) / 2, cy: (a.y + b.y) / 2, dist: Math.max(1, Math.hypot(a.x - b.x, a.y - b.y)) };
}

function initStage() {
  const c = $('#view');
  c.addEventListener('contextmenu', (e) => e.preventDefault());
  c.addEventListener('pointerdown', (e) => {
    if (!current) return;
    c.setPointerCapture(e.pointerId);
    const p = stagePos(e);
    pointers.set(e.pointerId, p);
    if (pointers.size === 2) {
      if (gesture && gesture.type === 'stroke') cancelStroke();
      const pi = pinchInfo();
      gesture = {
        type: 'pinch', start: pi,
        zoom: view.zoom, panX: view.panX, panY: view.panY,
        place: current.rec.place ? { ...current.rec.place } : null,
        subject: ui.tab === 'layout' && current.manual,
      };
      ui.cursor = null;
      requestRender();
      return;
    }
    if (pointers.size > 2) return;
    const type = singleAction(e);
    gesture = { type, sx: p.x, sy: p.y, panX: view.panX, panY: view.panY, place: current.rec.place ? { ...current.rec.place } : null };
    if (type === 'stroke') {
      ui.cursor = p;
      beginStroke(p.x, p.y);
    }
  });
  c.addEventListener('pointermove', (e) => {
    if (!current) return;
    const p = stagePos(e);
    if (e.pointerType !== 'touch' || pointers.has(e.pointerId)) ui.cursor = p;
    if (!pointers.has(e.pointerId)) { requestRender(); return; }
    pointers.set(e.pointerId, p);
    if (!gesture) return;
    if (gesture.type === 'stroke') {
      moveStroke(p.x, p.y);
    } else if (gesture.type === 'pan') {
      view.panX = gesture.panX + (p.x - gesture.sx);
      view.panY = gesture.panY + (p.y - gesture.sy);
    } else if (gesture.type === 'move') {
      const pl = current.rec.place;
      pl.x = gesture.place.x + (p.x - gesture.sx) / view.zoom;
      pl.y = gesture.place.y + (p.y - gesture.sy) / view.zoom;
    } else if (gesture.type === 'pinch' && pointers.size >= 2) {
      const pi = pinchInfo();
      const g = gesture;
      const k = pi.dist / g.start.dist;
      if (g.subject) {
        // 配置タブ: 被写体を拡大縮小・移動
        const s0 = g.place.scale, s1 = Math.max(0.05, Math.min(8, s0 * k));
        const fx0 = (g.start.cx - view.panX) / view.zoom, fy0 = (g.start.cy - view.panY) / view.zoom;
        const fx1 = (pi.cx - view.panX) / view.zoom, fy1 = (pi.cy - view.panY) / view.zoom;
        const pl = current.rec.place;
        pl.scale = s1;
        pl.x = fx1 - (fx0 - g.place.x) * (s1 / s0);
        pl.y = fy1 - (fy0 - g.place.y) * (s1 / s0);
      } else {
        const z = Math.max(0.02, Math.min(40, g.zoom * k));
        const fx = (g.start.cx - g.panX) / g.zoom, fy = (g.start.cy - g.panY) / g.zoom;
        view.zoom = z;
        view.panX = pi.cx - fx * z;
        view.panY = pi.cy - fy * z;
      }
    }
    requestRender();
  });
  const up = (e) => {
    if (!pointers.has(e.pointerId)) return;
    pointers.delete(e.pointerId);
    if (gesture && gesture.type === 'stroke') endStroke();
    if (gesture && (gesture.type === 'move' || (gesture.type === 'pinch' && gesture.subject))) scheduleSave();
    if (pointers.size === 0) gesture = null;
    else if (gesture && gesture.type === 'pinch' && pointers.size === 1) {
      // 1本指が残ったら移動として続ける（誤ってブラシが入らないように）
      const [p] = [...pointers.values()];
      gesture = { type: gesture.subject ? 'move' : 'pan', sx: p.x, sy: p.y, panX: view.panX, panY: view.panY, place: { ...current.rec.place } };
    }
    if (e.pointerType === 'touch') ui.cursor = null;
    requestRender();
  };
  c.addEventListener('pointerup', up);
  c.addEventListener('pointercancel', (e) => { if (gesture && gesture.type === 'stroke') cancelStroke(); up(e); });
  c.addEventListener('pointerleave', (e) => { if (e.pointerType !== 'touch' && !pointers.has(e.pointerId)) { ui.cursor = null; requestRender(); } });
  c.addEventListener('wheel', (e) => {
    if (!current) return;
    e.preventDefault();
    const p = stagePos(e);
    if (e.ctrlKey || !e.deltaX || Math.abs(e.deltaY) > Math.abs(e.deltaX) * 2) {
      const k = Math.exp(-e.deltaY * (e.ctrlKey ? 0.01 : 0.0015));
      const z = Math.max(0.02, Math.min(40, view.zoom * k));
      const fx = (p.x - view.panX) / view.zoom, fy = (p.y - view.panY) / view.zoom;
      view.zoom = z;
      view.panX = p.x - fx * z;
      view.panY = p.y - fy * z;
    } else {
      view.panX -= e.deltaX;
      view.panY -= e.deltaY;
    }
    requestRender();
  }, { passive: false });

  new ResizeObserver(() => {
    if ($('#editor').hidden) return;
    const first = !view.cw;
    resizeStage();
    if (first) fitView();
    requestRender();
  }).observe($('#stage'));
}

// ---- 操作パネル ----

function setTab(tab) {
  ui.tab = tab;
  for (const b of $$('.tabs button')) b.classList.toggle('active', b.dataset.tab === tab);
  for (const p of $$('.panel')) p.hidden = p.dataset.panel !== tab;
  updateStageCursor();
  if (tab === 'fix') hint('なぞって消す／戻す。2本指で拡大・移動');
  requestRender();
}

function updateStageCursor() {
  const st = $('#stage');
  st.classList.toggle('cursor-none', ui.tab === 'fix' && ui.mode !== 'pan' && !ui.spaceDown);
  st.classList.toggle('cursor-move', ui.tab === 'layout');
  st.classList.toggle('cursor-grab', (ui.tab !== 'fix' && ui.tab !== 'layout') || ui.mode === 'pan' || ui.spaceDown);
}

function setBgForCurrent(bg) {
  if (!current) return;
  current.rec.bg = bg;
  markSwatch($('#bgSwatches'), bg);
  $('#blurField').hidden = bg.type !== 'blur';
  requestRender();
  scheduleSave();
}

function refreshEditorControls() {
  const item = current;
  if (!item) return;
  const rec = item.rec;
  markSwatch($('#bgSwatches'), rec.bg);
  $('#blurField').hidden = rec.bg.type !== 'blur';
  if (rec.bg.type === 'blur') $('#optBlur').value = rec.bg.amount || 24;
  $('#optShadow').checked = !!rec.layout.shadow;
  $('#edgeShift').value = rec.edge.shift;
  $('#edgeFeather').value = rec.edge.feather;
  $('#optMargin').value = Math.round(rec.layout.margin * 100);
  for (const ch of $('#aspectChips').children) ch.classList.toggle('active', ch.dataset.id === rec.layout.aspect);
  updateOutputs();
  refreshUndoButtons();
  const ready = !!item.manual;
  for (const el of $$('.tools button, .tools input')) el.disabled = !ready;
  $('#btnExport').disabled = !ready;
  $('#btnCompare').disabled = !ready;
}

function updateOutputs() {
  $('#brushSizeOut').textContent = ui.brushSize + 'px';
  $('#brushHardOut').textContent = Math.round(ui.brushHard * 100) + '%';
  const s = +$('#edgeShift').value;
  $('#edgeShiftOut').textContent = s === 0 ? '0' : s > 0 ? `+${s}px` : `${s}px`;
  $('#edgeFeatherOut').textContent = $('#edgeFeather').value + 'px';
  $('#marginOut').textContent = $('#optMargin').value + '%';
}

function initEditor() {
  initStage();
  $('#btnBack').onclick = closeEditor;
  $('#btnUndo').onclick = undo;
  $('#btnRedo').onclick = redo;
  $('#btnFit').onclick = () => { fitView(); requestRender(); };
  const cmp = $('#btnCompare');
  const cmpOn = (e) => { e.preventDefault(); ui.comparing = true; cmp.classList.add('pressed'); requestRender(); };
  const cmpOff = () => { if (!ui.comparing) return; ui.comparing = false; cmp.classList.remove('pressed'); requestRender(); };
  cmp.addEventListener('pointerdown', cmpOn);
  cmp.addEventListener('pointerup', cmpOff);
  cmp.addEventListener('pointerleave', cmpOff);
  cmp.addEventListener('pointercancel', cmpOff);

  for (const b of $$('.tabs button')) b.onclick = () => setTab(b.dataset.tab);

  // 背景
  buildSwatches($('#bgSwatches'), () => (current ? current.rec.bg : settings.template.bg), setBgForCurrent, { withImage: true });
  const bfi = $('#bgFileInput');
  bfi.onchange = async () => {
    const f = bfi.files[0];
    bfi.value = '';
    if (!f || !current) return;
    try {
      current.bgImg = await decodeImage(f);
      current.rec.bgBlob = f;
      setBgForCurrent({ type: 'image' });
    } catch (e) {
      toast('その画像は読み込めませんでした');
    }
  };
  $('#optShadow').onchange = (e) => { if (!current) return; current.rec.layout.shadow = e.target.checked; requestRender(); scheduleSave(); };
  $('#optBlur').oninput = (e) => { if (!current) return; current.rec.bg = { type: 'blur', amount: +e.target.value, tag: 'ぼかし' }; requestRender(); scheduleSave(); };
  $('#btnApplyBgAll').onclick = async () => {
    if (!current) return;
    const src = current.rec;
    const others = recs.filter((r) => r !== src);
    for (const r of others) {
      r.bg = structuredClone(src.bg);
      r.bgBlob = src.bgBlob;
      r.layout = { ...r.layout, shadow: src.layout.shadow };
      if (r.status === 'done') {
        const it = await hydrate(r);
        await commit(it);
      } else {
        await persistFields(r, ['bg', 'bgBlob', 'layout']);
      }
    }
    // 次に取り込む写真にも同じ背景を使う（画像背景は写真ごとなので色・グラデーション等のみ）
    if (src.bg.type !== 'image') {
      settings.template.bg = structuredClone(src.bg);
      settings.template.shadow = src.layout.shadow;
      saveSettings();
      markSwatch($('#tplSwatches'), settings.template.bg);
      $('#tplShadow').checked = settings.template.shadow;
    }
    toast(`${others.length} 枚に同じ背景を適用しました`);
  };

  // 修整
  for (const b of $$('[data-panel=fix] .seg button')) {
    b.onclick = () => {
      ui.mode = b.dataset.mode;
      for (const x of $$('[data-panel=fix] .seg button')) x.classList.toggle('active', x === b);
      updateStageCursor();
      requestRender();
    };
  }
  $('#brushSize').oninput = (e) => { ui.brushSize = +e.target.value; updateOutputs(); ui.cursor = { x: view.cw / 2, y: view.ch / 2 }; requestRender(); };
  $('#brushHard').oninput = (e) => { ui.brushHard = +e.target.value / 100; updateOutputs(); };
  $('#brushSize').onchange = () => { ui.cursor = null; requestRender(); };
  $('#optGhost').onchange = (e) => { ui.ghost = e.target.checked; requestRender(); };
  $('#btnResetMask').onclick = () => {
    if (!current || !current.auto) return;
    pushUndo();
    current.manual = current.auto.slice();
    rebuildAll(current);
    refreshUndoButtons();
    requestRender();
    scheduleSave(true);
    toast('自動の結果に戻しました（元に戻すで取り消せます）');
  };

  // 境界
  let edgeTimer = 0;
  const onEdge = () => {
    updateOutputs();
    if (!current) return;
    current.rec.edge = { shift: +$('#edgeShift').value, feather: +$('#edgeFeather').value };
    clearTimeout(edgeTimer);
    edgeTimer = setTimeout(() => { rebuildAll(current); requestRender(); scheduleSave(); }, 40);
  };
  $('#edgeShift').oninput = onEdge;
  $('#edgeFeather').oninput = onEdge;

  // 配置
  const chips = $('#aspectChips');
  for (const a of ASPECTS) {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'chip';
    b.textContent = a.label;
    b.dataset.id = a.id;
    b.onclick = () => {
      if (!current) return;
      current.rec.layout.aspect = a.id;
      autoPlace(current);
      refreshEditorControls();
      fitView();
      requestRender();
      scheduleSave();
    };
    chips.append(b);
  }
  $('#optMargin').oninput = (e) => {
    updateOutputs();
    if (!current) return;
    current.rec.layout.margin = +e.target.value / 100;
    if (current.rec.layout.aspect === 'orig') { current.rec.layout.aspect = '1:1'; refreshEditorControls(); fitView(); }
    autoPlace(current);
    requestRender();
    scheduleSave();
  };
  $('#btnRecenter').onclick = () => { if (!current) return; autoPlace(current); requestRender(); scheduleSave(); };

  // キーボード
  window.addEventListener('keydown', (e) => {
    if ($('#editor').hidden || e.target.closest('input, select, textarea, dialog')) return;
    const mod = e.ctrlKey || e.metaKey;
    if (mod && e.key.toLowerCase() === 'z') { e.preventDefault(); e.shiftKey ? redo() : undo(); }
    else if (mod && e.key.toLowerCase() === 'y') { e.preventDefault(); redo(); }
    else if (e.key === ' ') { e.preventDefault(); if (!ui.spaceDown) { ui.spaceDown = true; updateStageCursor(); } }
    else if (e.key === '[' || e.key === ']') {
      ui.brushSize = Math.max(6, Math.min(160, ui.brushSize + (e.key === ']' ? 6 : -6)));
      $('#brushSize').value = ui.brushSize;
      updateOutputs();
      requestRender();
    } else if (e.key === 'e' && ui.tab === 'fix') $('[data-mode=erase]').click();
    else if (e.key === 'r' && ui.tab === 'fix') $('[data-mode=restore]').click();
  });
  window.addEventListener('keyup', (e) => { if (e.key === ' ') { ui.spaceDown = false; updateStageCursor(); } });

  // 保存ダイアログ
  const dlg = $('#exportDlg');
  const fmtBtns = $$('#fmtSeg button');
  const setFmt = (f) => {
    settings.format = f;
    saveSettings();
    for (const b of fmtBtns) b.classList.toggle('active', b.dataset.fmt === f);
    if (current) {
      const fr = frameSize(current);
      const transparentNote = f === 'jpeg' && current.rec.bg.type === 'none' ? '（JPEG は透明にできないため白背景になります）' : '';
      $('#exportInfo').textContent = `${fr.w} × ${fr.h}px ${transparentNote}`;
    }
  };
  for (const b of fmtBtns) b.onclick = () => setFmt(b.dataset.fmt);
  $('#btnExport').onclick = () => {
    if (!current || !current.manual) return;
    setFmt(settings.format);
    $('#btnShare').hidden = !(navigator.canShare && navigator.canShare({ files: [new File([''], 'a.png', { type: 'image/png' })] }));
    dlg.showModal();
  };
  const outName = () => `${baseName(current.rec.name)}_bgcut.${settings.format === 'jpeg' ? 'jpg' : 'png'}`;
  $('#btnDownload').onclick = async () => {
    const blob = await renderOutput(current, settings.format);
    downloadBlob(blob, outName());
    dlg.close();
  };
  $('#btnShare').onclick = async () => {
    const blob = await renderOutput(current, settings.format);
    const file = new File([blob], outName(), { type: blob.type });
    try { await navigator.share({ files: [file] }); dlg.close(); } catch (_) { /* キャンセル */ }
  };
}

// ---------------------------------------------------------------------------
// 設定
// ---------------------------------------------------------------------------

function openSettings() {
  $('#settingsDlg').showModal();
}

function initSettings() {
  const sel = $('#setModel');
  for (const [id, m] of Object.entries(MODELS)) {
    const o = document.createElement('option');
    o.value = id;
    o.textContent = m.label;
    sel.append(o);
  }
  const sync = () => {
    $('#customModel').hidden = settings.model !== 'custom' && settings.model !== 'file';
    $('#setModelUrl').closest('.field').hidden = settings.model !== 'custom';
    $('#fileModel').hidden = settings.model !== 'file';
    $('#fileModelName').textContent = settings.fileModelName || '未選択';
    $('#settingsInfo').textContent =
      (seg.backend ? `実行方式: ${seg.backend === 'webgpu' ? 'WebGPU（高速）' : 'WebAssembly'}。` : '') +
      'モデルは初回だけダウンロードされ、以後は端末に保存されます。RMBG-1.4 は非商用ライセンスです。';
  };
  sel.value = settings.model;
  sel.onchange = () => { settings.model = sel.value; saveSettings(); sync(); };
  $('#setModelUrl').value = settings.customUrl;
  $('#setModelUrl').onchange = (e) => { settings.customUrl = e.target.value.trim(); saveSettings(); };
  $('#setModelSize').value = settings.customSize;
  $('#setModelSize').onchange = (e) => { settings.customSize = +e.target.value || 1024; saveSettings(); };
  $('#setModelNorm').value = settings.customNorm;
  $('#setModelNorm').onchange = (e) => { settings.customNorm = e.target.value; saveSettings(); };
  $('#setMaxSide').value = String(settings.maxSide);
  $('#setMaxSide').onchange = (e) => { settings.maxSide = +e.target.value; saveSettings(); };
  $('#setGuided').checked = settings.guided;
  $('#setGuided').onchange = (e) => { settings.guided = e.target.checked; saveSettings(); };
  $('#btnPickModel').onclick = () => $('#modelFileInput').click();
  $('#modelFileInput').onchange = async (e) => {
    const f = e.target.files[0];
    e.target.value = '';
    if (!f) return;
    try {
      const cache = await caches.open('bgcut-models-v1');
      await cache.put(LOCAL_MODEL_URL + encodeURIComponent(f.name), new Response(f));
      settings.fileModelName = f.name;
      saveSettings();
      sync();
      toast('モデルを端末に保存しました');
    } catch (err) {
      toast('モデルを保存できませんでした: ' + err.message);
    }
  };
  $('#btnClearCache').onclick = async () => {
    try { await caches.delete('bgcut-models-v1'); } catch (_) {}
    seg.ready = null; seg.key = '';
    toast('モデルのキャッシュを削除しました');
  };
  $('#btnSettings').onclick = () => { sync(); openSettings(); };
  $('#settingsDlg').addEventListener('close', () => {
    // モデルが変わっていれば次の処理で読み込み直す。待機中があれば再開
    if (recs.some((r) => r.status === 'queued')) pumpQueue();
  });
  sync();
}

// ---------------------------------------------------------------------------
// 起動
// ---------------------------------------------------------------------------

async function restore() {
  const rows = await store.all();
  for (const r of rows) {
    if (!r.file) { store.remove(r.id); continue; }
    if (r.status === 'processing') r.status = 'queued';
    recs.push(r);
    addTile(r);
  }
  updateGridHead();
  if (recs.some((r) => r.status === 'queued')) pumpQueue();
}

initHome();
initEditor();
initSettings();
restore();

if ('serviceWorker' in navigator && location.protocol === 'https:') {
  navigator.serviceWorker.register('sw.js').catch(() => {});
}

// テスト・デバッグ用
window.bgcut = { recs, get current() { return current; }, settings, seg };
