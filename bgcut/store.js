// 作業中の写真を IndexedDB に保存し、あとで開き直して修整を続けられるようにする。

const DB_NAME = 'bgcut';
const STORE = 'items';
let dbp = null;

function open() {
  if (dbp) return dbp;
  dbp = new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, 1);
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains(STORE)) db.createObjectStore(STORE, { keyPath: 'id' });
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
  return dbp;
}

function tx(mode, fn) {
  return open().then((db) => new Promise((resolve, reject) => {
    const t = db.transaction(STORE, mode);
    const st = t.objectStore(STORE);
    const out = fn(st);
    t.oncomplete = () => resolve(out && out.result !== undefined ? out.result : out);
    t.onerror = () => reject(t.error);
    t.onabort = () => reject(t.error);
  }));
}

export const store = {
  async all() {
    try {
      const rows = await tx('readonly', (st) => st.getAll());
      return rows.sort((a, b) => a.created - b.created);
    } catch (_) {
      return [];
    }
  },
  // rec のうち渡されたフィールドだけを上書きする
  async patch(id, fields) {
    try {
      await open().then((db) => new Promise((resolve, reject) => {
        const t = db.transaction(STORE, 'readwrite');
        const st = t.objectStore(STORE);
        const g = st.get(id);
        g.onsuccess = () => st.put({ ...(g.result || { id }), ...fields });
        t.oncomplete = resolve;
        t.onerror = () => reject(t.error);
      }));
    } catch (e) {
      console.warn('保存に失敗しました', e);
    }
  },
  async remove(id) {
    try { await tx('readwrite', (st) => st.delete(id)); } catch (_) {}
  },
  async clear() {
    try { await tx('readwrite', (st) => st.clear()); } catch (_) {}
  },
};
