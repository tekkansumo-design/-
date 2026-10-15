// 背景削除モデルを動かすワーカー。画面を固まらせないよう推論はここで行う。
// モデルは一度ダウンロードしたら Cache Storage に保存し、次回からはオフラインでも使える。

let session = null;
let sessionKey = '';
let backend = '';

const MODEL_CACHE = 'bgcut-models-v1';

function post(type, data, transfer) { self.postMessage({ type, ...data }, transfer || []); }

async function fetchModel(url) {
  let cache = null;
  try { cache = await caches.open(MODEL_CACHE); } catch (_) { /* Cache API が使えない環境 */ }
  if (cache) {
    const hit = await cache.match(url);
    if (hit) {
      post('progress', { phase: 'model', loaded: 1, total: 1, cached: true });
      return new Uint8Array(await hit.arrayBuffer());
    }
  }
  const res = await fetch(url);
  if (!res.ok) throw new Error(`モデルを取得できませんでした (HTTP ${res.status})`);
  const total = Number(res.headers.get('content-length')) || 0;
  const reader = res.body.getReader();
  const chunks = [];
  let loaded = 0, last = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    chunks.push(value);
    loaded += value.length;
    const now = Date.now();
    if (now - last > 150) { post('progress', { phase: 'model', loaded, total }); last = now; }
  }
  const buf = new Uint8Array(loaded);
  let off = 0;
  for (const c of chunks) { buf.set(c, off); off += c.length; }
  post('progress', { phase: 'model', loaded, total: loaded });
  if (cache) {
    try {
      await cache.put(url, new Response(buf, { headers: { 'content-type': 'application/octet-stream' } }));
    } catch (_) { /* 容量不足などは無視（次回また取得する） */ }
  }
  return buf;
}

async function init({ ortBase, modelUrl, modelBuffer, key }) {
  if (session && sessionKey === key) return;
  if (!self.ort) {
    importScripts(ortBase + 'ort.webgpu.min.js');
    self.ort.env.wasm.wasmPaths = ortBase;
    self.ort.env.logLevel = 'error';
  }
  const bytes = modelBuffer ? new Uint8Array(modelBuffer) : await fetchModel(modelUrl);
  post('progress', { phase: 'compile' });
  if (session) { try { await session.release(); } catch (_) {} session = null; }
  const tries = [];
  if (self.navigator && self.navigator.gpu) tries.push('webgpu');
  tries.push('wasm');
  let lastErr = null;
  for (const ep of tries) {
    try {
      session = await self.ort.InferenceSession.create(bytes, {
        executionProviders: [ep],
        graphOptimizationLevel: 'all',
      });
      backend = ep;
      break;
    } catch (e) {
      lastErr = e;
    }
  }
  if (!session) throw lastErr || new Error('モデルを読み込めませんでした');
  sessionKey = key;
}

async function run({ pixels, size, mean, std }) {
  const n = size * size;
  const data = new Float32Array(3 * n);
  for (let i = 0, j = 0; i < n; i++, j += 4) {
    data[i] = (pixels[j] / 255 - mean[0]) / std[0];
    data[n + i] = (pixels[j + 1] / 255 - mean[1]) / std[1];
    data[2 * n + i] = (pixels[j + 2] / 255 - mean[2]) / std[2];
  }
  const input = new self.ort.Tensor('float32', data, [1, 3, size, size]);
  const out = await session.run({ [session.inputNames[0]]: input });
  const t = out[session.outputNames[0]];
  const v = t.data;
  // 出力は 1×1×size×size。モデルにより値域が違うので最小-最大で 0〜1 に揃える
  let lo = Infinity, hi = -Infinity;
  for (let i = 0; i < n; i++) { const x = v[i]; if (x < lo) lo = x; if (x > hi) hi = x; }
  const scale = hi > lo ? 255 / (hi - lo) : 0;
  const mask = new Uint8Array(n);
  for (let i = 0; i < n; i++) mask[i] = (v[i] - lo) * scale + 0.5;
  if (t.dispose) t.dispose();
  return mask;
}

self.onmessage = async (e) => {
  const msg = e.data;
  try {
    if (msg.type === 'init') {
      await init(msg);
      post('ready', { id: msg.id, backend });
    } else if (msg.type === 'run') {
      const t0 = performance.now();
      const mask = await run(msg);
      post('result', { id: msg.id, mask, ms: performance.now() - t0, backend }, [mask.buffer]);
    }
  } catch (err) {
    post('error', { id: msg.id, message: String((err && err.message) || err) });
  }
};
