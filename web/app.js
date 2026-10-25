'use strict';

/* ==========================================================================
 * 漫画书架 · 前端
 * 纯原生 JS，无依赖。所有数据通过本地 Python 服务端的 /api 读写。
 * ========================================================================== */

const $ = (id) => document.getElementById(id);

const el = {
  shelf: $('shelf'),
  empty: $('empty'),
  lanHint: $('lan-hint'),
  toast: $('toast'),
  menu: $('menu'),
  drop: $('drop'),
  fileInput: $('file-input'),
  dirInput: $('dir-input'),
  chooser: $('chooser'),
  chFiles: $('ch-files'),
  chDir: $('ch-dir'),
  chPath: $('ch-path'),
  chCancel: $('ch-cancel'),

  reader: $('reader'),
  stage: $('stage'),
  canvas: $('canvas'),
  pageCur: $('page-cur'),
  pageNext: $('page-next'),
  imgCur: $('img-cur'),
  imgNext: $('img-next'),

  chromeTop: $('chrome-top'),
  chromeBottom: $('chrome-bottom'),
  readerTitle: $('reader-title'),
  readerCounter: $('reader-counter'),
  slider: $('slider'),
  pageLabel: $('page-label'),
  btnBack: $('btn-back'),
  btnRtl: $('btn-rtl'),
  btnAnim: $('btn-anim'),
  btnFit: $('btn-fit'),
  zoomBadge: $('zoom-badge'),

  overlay: $('overlay'),
  dlgTitle: $('dlg-title'),
  dlgText: $('dlg-text'),
  dlgBar: $('dlg-bar'),
  dlgClose: $('dlg-close'),
};

const state = {
  books: [],
  book: null,
  page: 0,
  total: 0,
  anim: localStorage.getItem('ms.anim') || 'flip',
  zoom: 1,
  pan: { x: 0, y: 0 },
  chrome: true,
};

/* 两个页面图层：front 是当前显示页，back 是动画/预载用的另一层 */
const layers = {
  front: { wrap: el.pageCur, img: el.imgCur, z: '2' },
  back: { wrap: el.pageNext, img: el.imgNext, z: '1' },
};

let flipping = false;
let flipToken = 0;
let drag = null;
let saveTimer = null;
let toastTimer = null;

/* --------------------------------------------------------------------------
 * 小工具
 * ------------------------------------------------------------------------ */

const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function pageUrl(bookId, index) {
  return `/data/books/${bookId}/page_${String(index + 1).padStart(3, '0')}.jpg`;
}

function stripExt(name) {
  const i = name.lastIndexOf('.');
  const base = i > 0 ? name.slice(0, i) : name;
  return base.trim() || name;
}

function stamp() {
  const d = new Date();
  const p = (n) => String(n).padStart(2, '0');
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

function toast(msg) {
  el.toast.textContent = msg;
  el.toast.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { el.toast.hidden = true; }, 2400);
}

async function api(path, options) {
  const res = await fetch(path, options);
  if (!res.ok) {
    let msg = '';
    try { msg = await res.text(); } catch (e) { /* ignore */ }
    throw new Error(msg || ('请求失败：' + res.status));
  }
  return res.json();
}

function jsonBody(method, body) {
  return {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  };
}

/* --------------------------------------------------------------------------
 * 书架
 * ------------------------------------------------------------------------ */

async function loadBooks() {
  const data = await api('/api/books');
  state.books = data.books || [];
  renderShelf();
}

function renderShelf() {
  el.shelf.innerHTML = '';
  el.empty.hidden = state.books.length > 0;
  for (const book of state.books) el.shelf.appendChild(renderCard(book));
}

function renderCard(book) {
  const pct = book.pageCount > 1
    ? Math.round((book.lastPage / (book.pageCount - 1)) * 100)
    : 0;

  const card = document.createElement('article');
  card.className = 'card';

  const cover = document.createElement('div');
  cover.className = 'cover';
  const img = document.createElement('img');
  img.loading = 'lazy';
  img.alt = '';
  img.draggable = false;
  img.src = pageUrl(book.id, 0);
  cover.appendChild(img);
  if (book.lastPage > 0) {
    const badge = document.createElement('span');
    badge.className = 'badge';
    badge.textContent = `读到 ${book.lastPage + 1} 页`;
    cover.appendChild(badge);
  }

  const title = document.createElement('div');
  title.className = 'title';
  title.textContent = book.title;

  const sub = document.createElement('div');
  sub.className = 'sub';
  sub.textContent = `共 ${book.pageCount} 页 · ${book.rtl ? '右→左' : '左→右'}`;

  const bar = document.createElement('div');
  bar.className = 'bar';
  const fill = document.createElement('i');
  fill.style.width = pct + '%';
  bar.appendChild(fill);

  const kebab = document.createElement('button');
  kebab.className = 'kebab';
  kebab.title = '设置';
  kebab.textContent = '⋮';
  kebab.addEventListener('click', (e) => {
    e.stopPropagation();
    openMenu(kebab, book);
  });

  card.append(cover, title, sub, bar, kebab);
  card.addEventListener('click', () => openReader(book));
  return card;
}

/* --------------------------------------------------------------------------
 * 卡片菜单
 * ------------------------------------------------------------------------ */

function closeMenu() { el.menu.hidden = true; }

function openMenu(anchor, book) {
  el.menu.innerHTML = '';

  const add = (label, danger, run) => {
    const b = document.createElement('button');
    b.textContent = label;
    if (danger) b.className = 'danger';
    b.addEventListener('click', () => { closeMenu(); run(); });
    el.menu.appendChild(b);
  };

  add('重命名…', false, async () => {
    const name = window.prompt('输入新的书名', book.title);
    if (name === null) return;
    const t = name.trim();
    if (!t) return;
    try {
      await api(`/api/books/${book.id}`, jsonBody('PATCH', { title: t }));
      await loadBooks();
    } catch (e) { toast('重命名失败：' + e.message); }
  });

  add(`阅读方向：${book.rtl ? '从右往左' : '从左往右'}（点击切换）`, false, async () => {
    try {
      await api(`/api/books/${book.id}`, jsonBody('PATCH', { rtl: !book.rtl }));
      await loadBooks();
    } catch (e) { toast('切换失败：' + e.message); }
  });

  add('删除这本漫画', true, async () => {
    if (!window.confirm(`删除《${book.title}》？页面图片会一起删掉，无法恢复。`)) return;
    try {
      await api(`/api/books/${book.id}`, { method: 'DELETE' });
      await loadBooks();
      toast('已删除');
    } catch (e) { toast('删除失败：' + e.message); }
  });

  el.menu.hidden = false;
  const r = anchor.getBoundingClientRect();
  el.menu.style.top = Math.min(r.bottom + 6, window.innerHeight - 190) + 'px';
  el.menu.style.left = Math.max(8, Math.min(r.left - 150, window.innerWidth - 224)) + 'px';
}

document.addEventListener('click', (e) => {
  if (!el.menu.hidden && !el.menu.contains(e.target)) closeMenu();
});
window.addEventListener('resize', closeMenu);
window.addEventListener('scroll', closeMenu, true);

/* --------------------------------------------------------------------------
 * 导入
 * ------------------------------------------------------------------------ */

function hasFiles(e) {
  const dt = e.dataTransfer;
  if (!dt) return false;
  return Array.from(dt.types || []).indexOf('Files') !== -1;
}

window.addEventListener('dragenter', (e) => {
  if (!hasFiles(e)) return;
  e.preventDefault();
  el.drop.hidden = false;
});
window.addEventListener('dragover', (e) => {
  if (!hasFiles(e)) return;
  e.preventDefault();
  e.dataTransfer.dropEffect = 'copy';
});
window.addEventListener('dragleave', (e) => {
  if (e.relatedTarget === null) el.drop.hidden = true;
});
window.addEventListener('drop', (e) => {
  if (!hasFiles(e)) return;
  e.preventDefault();
  el.drop.hidden = true;
  // 目录条目必须在事件处理期间同步取出：一旦 await，dataTransfer 就失效了
  const entries = entriesFromDataTransfer(e.dataTransfer);
  if (!entries.length) {
    handleFileList(e.dataTransfer.files);
    return;
  }
  runImport(() => groupsFromEntries(entries));
});

/* ---- 导入方式选择 ---- */

function openChooser() { el.chooser.hidden = false; }
function closeChooser() { el.chooser.hidden = true; }

document.querySelectorAll('#btn-import, #btn-import-2').forEach((btn) => {
  btn.addEventListener('click', openChooser);
});

el.chCancel.addEventListener('click', closeChooser);
el.chFiles.addEventListener('click', () => { closeChooser(); el.fileInput.click(); });
el.chDir.addEventListener('click', () => { closeChooser(); el.dirInput.click(); });
el.chPath.addEventListener('click', () => { closeChooser(); askFolderPath(); });

el.fileInput.addEventListener('change', () => {
  const files = Array.from(el.fileInput.files || []);
  el.fileInput.value = '';
  if (files.length) handleFileList(files);
});

el.dirInput.addEventListener('change', () => {
  const files = Array.from(el.dirInput.files || []);
  el.dirInput.value = '';
  if (files.length) runImport(() => groupsFromFileList(files));
});

const IMAGE_RE = /\.(png|jpe?g|webp|gif|bmp|avif|tiff?)$/i;
const isImageFile = (f) => /^image\//.test(f.type) || IMAGE_RE.test(f.name);
const isPdfFile = (f) => /\.pdf$/i.test(f.name) || f.type === 'application/pdf';
const naturalCompare = (a, b) => a.localeCompare(b, 'zh-CN', { numeric: true, sensitivity: 'base' });

/* ---- 把"文件 / 文件夹"整理成一组组待导入的书 ---- */

/** 同步取出拖拽的目录条目（dataTransfer 在 await 之后就不可用了） */
function entriesFromDataTransfer(dt) {
  const items = dt.items ? Array.from(dt.items) : [];
  const out = [];
  for (const item of items) {
    if (item.kind !== 'file') continue;
    const entry = item.webkitGetAsEntry ? item.webkitGetAsEntry() : null;
    if (entry) {
      out.push({ entry });
    } else {
      const file = item.getAsFile();
      if (file) out.push({ file });
    }
  }
  return out;
}

function entryFile(entry) {
  return new Promise((resolve) => {
    try { entry.file((f) => resolve(f), () => resolve(null)); }
    catch (e) { resolve(null); }
  });
}

/** 递归遍历一个目录条目；readEntries 每次最多返回 100 个，要循环读到空 */
function walkEntry(dirEntry, prefix, out) {
  return new Promise((resolve) => {
    const reader = dirEntry.createReader();
    const readBatch = () => {
      reader.readEntries((batch) => {
        if (!batch || !batch.length) { resolve(); return; }
        let pending = batch.length;
        const step = () => { pending -= 1; if (pending === 0) readBatch(); };
        for (const entry of batch) {
          if (entry.isFile) {
            entryFile(entry).then((file) => {
              if (file) out.push({ file, path: prefix + entry.name });
              step();
            });
          } else if (entry.isDirectory) {
            walkEntry(entry, prefix + entry.name + '/', out).then(step);
          } else {
            step();
          }
        }
      }, () => resolve());
    };
    readBatch();
  });
}

async function groupsFromEntries(entries) {
  const groups = [];
  const plain = [];
  for (const it of entries) {
    if (!it.entry) { if (it.file) plain.push(it.file); continue; }
    const entry = it.entry;
    if (entry.isDirectory) {
      const items = [];
      await walkEntry(entry, '', items);
      if (items.length) groups.push({ root: entry.name, items });
    } else {
      const file = await entryFile(entry);
      if (file) plain.push(file);
    }
  }
  if (plain.length) {
    groups.push({ root: '', items: plain.map((file) => ({ file, path: file.name })) });
  }
  return groups;
}

/** 浏览器文件夹选择器返回的 FileList：用 webkitRelativePath 的第一段分组 */
function groupsFromFileList(files) {
  const map = new Map();
  for (const file of files) {
    const rel = file.webkitRelativePath || file.name;
    const slash = rel.indexOf('/');
    const root = slash > 0 ? rel.slice(0, slash) : '';
    if (!map.has(root)) map.set(root, []);
    map.get(root).push({ file, path: rel });
  }
  const groups = [];
  for (const [root, items] of map) groups.push({ root, items });
  return groups;
}

function handleFileList(files) {
  const items = Array.from(files || []).map((file) => ({ file, path: file.name }));
  if (!items.length) return;
  runImport(async () => [{ root: '', items }]);
}

/* ---- 导入主流程 ---- */

async function runImport(buildGroups) {
  let groups;
  try {
    groups = await buildGroups();
  } catch (err) {
    showDialogError(err && err.message ? err.message : '读取文件夹失败');
    return;
  }
  groups = groups.filter((g) => g.items && g.items.length);
  if (!groups.length) {
    toast('没有找到可导入的图片或 PDF');
    return;
  }

  try {
    for (const group of groups) await importGroup(group);
  } catch (err) {
    showDialogError(err && err.message ? err.message : '导入失败');
    await loadBooks();
    return;
  }

  hideOverlay();
  await loadBooks();
  toast(groups.length > 1 ? `导入完成，共 ${groups.length} 本` : '导入完成');
}

async function importGroup(group) {
  const files = group.items.map((it) => it.file);
  const pdfs = files.filter(isPdfFile);
  const images = group.items.filter((it) => isImageFile(it.file));

  if (!pdfs.length && !images.length) {
    throw new Error(group.root ? `「${group.root}」里没有图片或 PDF` : '这些文件不是 PDF 或图片');
  }

  // 文件夹里的 PDF：一个 PDF 一本书
  for (const pdf of pdfs) await importPdf(pdf);

  if (images.length) {
    let title;
    if (group.root) title = group.root;
    else if (images.length === 1) title = stripExt(images[0].file.name);
    else title = `图片漫画 ${stamp()}`;
    await importImages(images, title, group.root);
  }
}

function upload(url, file, contentType, onProgress) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('POST', url, true);
    xhr.setRequestHeader('Content-Type', contentType || 'application/octet-stream');
    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable && onProgress) onProgress(e.loaded / e.total);
    };
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        try { resolve(JSON.parse(xhr.responseText)); } catch (e) { resolve({}); }
      } else {
        reject(new Error(xhr.responseText || ('HTTP ' + xhr.status)));
      }
    };
    xhr.onerror = () => reject(new Error('网络错误，服务端可能已经停止'));
    xhr.send(file);
  });
}

async function pollJob(jobId, onTick) {
  const deadline = Date.now() + 30 * 60 * 1000;
  for (;;) {
    if (Date.now() > deadline) throw new Error('导入超时，先换个小的文件试试');
    let job;
    try {
      job = await api(`/api/jobs/${jobId}`);
    } catch (e) {
      throw new Error('查询进度失败：' + e.message);
    }
    if (onTick) onTick(job);
    if (job.status === 'done') return job;
    if (job.status === 'error') throw new Error(job.error || '导入失败');
    await sleep(200);
  }
}

async function importPdf(file) {
  showProgress('正在导入 PDF', file.name, 0);

  const res = await upload(
    `/api/import-pdf?name=${encodeURIComponent(file.name)}`,
    file,
    'application/pdf',
    (p) => setProgress(p * 0.4, `上传中 ${Math.round(p * 100)}%`),
  );

  if (!res.jobId) throw new Error('服务端没有返回任务号');
  setProgress(0.4, '正在渲染页面…');

  const job = await pollJob(res.jobId, (j) => {
    if (j.total > 0) {
      setProgress(0.4 + 0.6 * (j.done / j.total), `正在渲染 ${j.done} / ${j.total} 页`);
    }
  });
  setProgress(1, `完成，共 ${job.pageCount || job.total} 页`);
}

/** 直接让服务端读本机文件夹（不上传字节，大文件夹快很多） */
async function askFolderPath() {
  const raw = window.prompt(
    '粘贴文件夹的完整路径（会递归包含子文件夹）\n例如：D:\\漫画\\海贼王\\第01卷',
    '',
  );
  if (raw === null) return;
  const path = raw.trim().replace(/^["']|["']$/g, '').trim();
  if (!path) return;

  try {
    await importPath(path);
  } catch (err) {
    showDialogError(err && err.message ? err.message : '导入失败');
    await loadBooks();
    return;
  }
  hideOverlay();
  await loadBooks();
  toast('导入完成');
}

async function importPath(path) {
  showProgress('正在读取文件夹', path, 0.02);
  const res = await api('/api/import-path', jsonBody('POST', { path }));
  if (!res.jobId) throw new Error('服务端没有返回任务号');

  const job = await pollJob(res.jobId, (j) => {
    if (j.total > 0) {
      const frac = Math.min(0.99, j.done / j.total);
      setProgress(frac, `正在导入 ${j.done} / ${j.total} 个文件`);
    }
  });

  const n = job.bookCount || 1;
  setProgress(1, n > 1 ? `完成，共 ${n} 本` : `完成，共 ${job.pageCount || job.total} 页`);
}

async function importImages(items, title, label) {
  items.sort((a, b) => naturalCompare(a.path, b.path));
  const prefix = label ? `${label} · ` : '';
  showProgress('正在导入图片', `${prefix}共 ${items.length} 张`, 0);

  const created = await api('/api/books', jsonBody('POST', { title }));
  const bookId = created.book.id;

  let done = 0;
  try {
    for (const it of items) {
      const url = `/api/import-image?name=${encodeURIComponent(it.file.name)}&book=${bookId}`;
      await upload(url, it.file, it.file.type || 'application/octet-stream', null);
      done += 1;
      setProgress(done / items.length, `${prefix}已导入 ${done} / ${items.length} 张`);
    }
  } catch (err) {
    try { await api(`/api/books/${bookId}`, { method: 'DELETE' }); } catch (e) { /* ignore */ }
    throw new Error(`第 ${done + 1} 张图片失败：${err.message}`);
  }
  setProgress(1, `${prefix}完成，共 ${done} 页`);
}

/* --------------------------------------------------------------------------
 * 弹窗
 * ------------------------------------------------------------------------ */

function showProgress(title, text, frac) {
  el.overlay.hidden = false;
  el.dlgTitle.textContent = title;
  el.dlgText.textContent = text || '';
  el.dlgClose.hidden = true;
  setProgress(frac, text);
}

function setProgress(frac, text) {
  el.dlgBar.style.width = Math.round(clamp(frac, 0, 1) * 100) + '%';
  if (typeof text === 'string' && text) el.dlgText.textContent = text;
}

function showDialogError(msg) {
  el.overlay.hidden = false;
  el.dlgTitle.textContent = '导入失败';
  el.dlgText.textContent = msg;
  el.dlgBar.style.width = '0%';
  el.dlgClose.hidden = false;
}

function hideOverlay() { el.overlay.hidden = true; }

el.dlgClose.addEventListener('click', hideOverlay);

/* --------------------------------------------------------------------------
 * 阅读器
 * ------------------------------------------------------------------------ */

function openReader(book) {
  state.book = book;
  state.total = book.pageCount;
  state.page = clamp(book.lastPage || 0, 0, Math.max(0, state.total - 1));
  state.zoom = 1;
  state.pan = { x: 0, y: 0 };
  flipping = false;
  flipToken += 1;

  applyZoom();
  resetLayers();
  layers.front.img.src = pageUrl(book.id, state.page);
  layers.back.img.removeAttribute('src');

  el.readerTitle.textContent = book.title;
  el.reader.hidden = false;
  document.body.classList.add('reading');
  setChrome(true);
  updateHud();
  closeMenu();
}

function closeReader() {
  flushSave();
  flipping = false;
  flipToken += 1;
  el.reader.hidden = true;
  document.body.classList.remove('reading');
  el.reader.classList.remove('no-chrome');
  layers.front.img.removeAttribute('src');
  layers.back.img.removeAttribute('src');
  state.book = null;
  loadBooks().catch(() => {});
}

function resetLayers() {
  for (const layer of [layers.front, layers.back]) {
    layer.wrap.style.transition = 'none';
    layer.wrap.style.transform = 'translateX(0)';
  }
  layers.front.wrap.style.opacity = '1';
  layers.front.wrap.style.zIndex = '2';
  layers.back.wrap.style.opacity = '0';
  layers.back.wrap.style.zIndex = '1';
}

function hardSet(page) {
  if (!state.book) return;
  layers.front.img.src = pageUrl(state.book.id, page);
  layers.back.img.removeAttribute('src');
  resetLayers();
}

function goTo(target, animate) {
  if (!state.book || flipping) return;
  const total = state.total;
  const t = clamp(Math.round(target), 0, Math.max(0, total - 1));
  if (t === state.page) { updateHud(); return; }

  const from = state.page;
  state.page = t;
  updateHud();
  scheduleSave();

  const useAnim = animate !== false && state.anim !== 'none' && Math.abs(t - from) === 1;
  if (!useAnim) { hardSet(t); return; }

  flipping = true;
  flipTo(t, t > from);
}

function flipTo(target, forward) {
  const rtl = !!(state.book && state.book.rtl);
  // 前进时新页从哪边进入：LTR 从右，RTL 从左；后退反之
  const enterFromRight = forward ? !rtl : rtl;
  const dir = enterFromRight ? 1 : -1;

  const out = layers.front;
  const inc = layers.back;
  const dur = 300;
  const easing = 'cubic-bezier(.22,.61,.36,1)';

  inc.img.src = pageUrl(state.book.id, target);

  const token = ++flipToken;
  const ready = inc.img.decode ? inc.img.decode().catch(() => {}) : Promise.resolve();

  ready.then(() => {
    if (token !== flipToken) return;
    inc.wrap.style.transition = 'none';
    inc.wrap.style.transform = `translateX(${dir * 100}%)`;
    inc.wrap.style.opacity = '1';
    inc.wrap.style.zIndex = '3';

    out.wrap.style.transition = 'none';
    out.wrap.style.transform = 'translateX(0)';
    out.wrap.style.opacity = '1';
    out.wrap.style.zIndex = '2';

    void inc.wrap.offsetWidth; // 强制回流，让起始位置先生效

    inc.wrap.style.transition = `transform ${dur}ms ${easing}`;
    out.wrap.style.transition = `transform ${dur}ms ${easing}, opacity ${dur}ms ${easing}`;
    inc.wrap.style.transform = 'translateX(0)';
    out.wrap.style.transform = `translateX(${-dir * 100}%)`;
    out.wrap.style.opacity = '0.2';

    setTimeout(() => {
      if (token !== flipToken) return;
      // 交换角色：滑进来的那层变成"当前页"，不需要再改 src
      const tmp = layers.front;
      layers.front = layers.back;
      layers.back = tmp;

      layers.front.wrap.style.transition = 'none';
      layers.front.wrap.style.transform = 'translateX(0)';
      layers.front.wrap.style.opacity = '1';
      layers.front.wrap.style.zIndex = '2';

      layers.back.wrap.style.transition = 'none';
      layers.back.wrap.style.transform = 'translateX(0)';
      layers.back.wrap.style.opacity = '0';
      layers.back.wrap.style.zIndex = '1';
      layers.back.img.removeAttribute('src');

      flipping = false;
    }, dur + 30);
  });
}

function nextPage() {
  if (!state.book) return;
  if (state.page < state.total - 1) goTo(state.page + 1);
}

function prevPage() {
  if (!state.book) return;
  if (state.page > 0) goTo(state.page - 1);
}

/* ---------------------------- 顶部 / 底部栏 ----------------------------- */

function setChrome(visible) {
  state.chrome = visible;
  el.chromeTop.classList.toggle('off', !visible);
  el.chromeBottom.classList.toggle('off', !visible);
  el.reader.classList.toggle('no-chrome', !visible);
}

function toggleChrome() { setChrome(!state.chrome); }

function updateHud() {
  if (!state.book) return;
  const n = state.page + 1;
  el.readerCounter.textContent = `${n} / ${state.total}`;
  el.pageLabel.textContent = `第 ${n} 页，共 ${state.total} 页`;
  el.slider.max = String(Math.max(1, state.total));
  el.slider.value = String(n);
  el.btnRtl.textContent = state.book.rtl ? '从右往左' : '从左往右';
  el.btnAnim.textContent = state.anim === 'none' ? '翻页动画：关' : '翻页动画：开';
}

/* -------------------------------- 缩放 --------------------------------- */

function applyZoom() {
  el.canvas.style.transform =
    `translate(${state.pan.x}px, ${state.pan.y}px) scale(${state.zoom})`;
  el.zoomBadge.hidden = state.zoom <= 1.0001;
  el.zoomBadge.textContent = Math.round(state.zoom * 100) + '%';
}

function clampPan() {
  const rect = el.stage.getBoundingClientRect();
  const maxX = (rect.width * (state.zoom - 1)) / 2;
  const maxY = (rect.height * (state.zoom - 1)) / 2;
  state.pan.x = clamp(state.pan.x, -maxX, maxX);
  state.pan.y = clamp(state.pan.y, -maxY, maxY);
}

function setZoom(z) {
  state.zoom = clamp(z, 1, 5);
  if (state.zoom <= 1.0001) {
    state.zoom = 1;
    state.pan = { x: 0, y: 0 };
  }
  clampPan();
  applyZoom();
}

function resetZoom() { state.zoom = 1; state.pan = { x: 0, y: 0 }; applyZoom(); }

/* ------------------------------ 指针交互 -------------------------------- */

/* 支持：单击分区翻页 / 单指拖动翻页 / 放大后单指平移 / 双指捏合缩放（手机）*/
const activePointers = new Map();
let pinch = null;

function twoPointerInfo() {
  const pts = [...activePointers.values()];
  const a = pts[0];
  const b = pts[1];
  return {
    dist: Math.hypot(a.x - b.x, a.y - b.y) || 1,
    cx: (a.x + b.x) / 2,
    cy: (a.y + b.y) / 2,
  };
}

el.stage.addEventListener('pointerdown', (e) => {
  if (e.pointerType === 'mouse' && e.button !== 0) return;
  activePointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
  try { el.stage.setPointerCapture(e.pointerId); } catch (err) { /* ignore */ }

  if (activePointers.size === 1) {
    drag = { x: e.clientX, y: e.clientY, lx: e.clientX, ly: e.clientY, moved: false, id: e.pointerId };
  } else {
    // 出现第二根手指：这次手势不再当作点击或翻页
    drag = null;
    if (activePointers.size === 2) {
      const info = twoPointerInfo();
      pinch = { dist: info.dist, zoom: state.zoom, cx: info.cx, cy: info.cy };
    }
  }
});

el.stage.addEventListener('pointermove', (e) => {
  if (!activePointers.has(e.pointerId)) return;
  activePointers.set(e.pointerId, { x: e.clientX, y: e.clientY });

  // 双指捏合
  if (pinch && activePointers.size >= 2) {
    const info = twoPointerInfo();
    const zoom = clamp(pinch.zoom * (info.dist / pinch.dist), 1, 5);
    state.zoom = zoom;
    if (zoom <= 1.0001) {
      state.zoom = 1;
      state.pan = { x: 0, y: 0 };
    } else {
      state.pan.x += info.cx - pinch.cx;
      state.pan.y += info.cy - pinch.cy;
      pinch.cx = info.cx;
      pinch.cy = info.cy;
      clampPan();
    }
    applyZoom();
    return;
  }

  if (!drag || e.pointerId !== drag.id) return;
  const dx = e.clientX - drag.x;
  const dy = e.clientY - drag.y;
  if (!drag.moved && Math.abs(dx) + Math.abs(dy) > 6) drag.moved = true;

  if (state.zoom > 1) {
    state.pan.x += e.clientX - drag.lx;
    state.pan.y += e.clientY - drag.ly;
    clampPan();
    applyZoom();
  }
  drag.lx = e.clientX;
  drag.ly = e.clientY;
});

el.stage.addEventListener('pointerup', (e) => {
  const hadPinch = pinch !== null;
  activePointers.delete(e.pointerId);
  if (activePointers.size < 2) pinch = null;

  if (hadPinch) { drag = null; return; }   // 捏合结束后不触发翻页/点击
  if (!drag || e.pointerId !== drag.id) return;

  const dx = e.clientX - drag.x;
  const moved = drag.moved;
  drag = null;

  if (!moved) { handleStageClick(e.clientX); return; }
  if (state.zoom > 1) return;

  if (Math.abs(dx) > 60) {
    const forward = state.book && state.book.rtl ? dx > 0 : dx < 0;
    if (forward) nextPage(); else prevPage();
  }
});

el.stage.addEventListener('pointercancel', (e) => {
  activePointers.delete(e.pointerId);
  if (activePointers.size < 2) pinch = null;
  drag = null;
});

let centerClickTimer = null;

function handleStageClick(clientX) {
  if (!state.book) return;
  if (state.zoom > 1) { toggleChrome(); return; }

  const rect = el.stage.getBoundingClientRect();
  const f = (clientX - rect.left) / Math.max(1, rect.width);
  const rtl = !!state.book.rtl;

  // 两侧：立刻翻页（不等双击判定，手感更跟手）
  if (f < 0.32) { if (rtl) nextPage(); else prevPage(); return; }
  if (f > 0.68) { if (rtl) prevPage(); else nextPage(); return; }

  // 中间：单击切换工具栏，双击缩放。只在中间做延迟，避免误翻页。
  if (centerClickTimer) {
    clearTimeout(centerClickTimer);
    centerClickTimer = null;
    if (state.zoom > 1) resetZoom(); else setZoom(2.2);
    return;
  }
  centerClickTimer = setTimeout(() => {
    centerClickTimer = null;
    toggleChrome();
  }, 240);
}

/* -------------------------------- 滚轮 ---------------------------------- */

let wheelAcc = 0;
let wheelLock = 0;

el.stage.addEventListener('wheel', (e) => {
  if (el.reader.hidden) return;
  e.preventDefault();

  if (e.ctrlKey || e.metaKey) {
    setZoom(state.zoom * (e.deltaY > 0 ? 0.9 : 1.1));
    return;
  }
  if (state.zoom > 1) {
    state.pan.y -= e.deltaY;
    clampPan();
    applyZoom();
    return;
  }

  const now = Date.now();
  if (now - wheelLock < 200) return;
  wheelAcc += e.deltaY;
  if (Math.abs(wheelAcc) > 24) {
    if (wheelAcc > 0) nextPage(); else prevPage();
    wheelAcc = 0;
    wheelLock = now;
  }
}, { passive: false });

/* -------------------------------- 键盘 ---------------------------------- */

document.addEventListener('keydown', (e) => {
  if (!el.chooser.hidden) {
    if (e.key === 'Escape') closeChooser();
    return;
  }
  if (!el.overlay.hidden) {
    if (e.key === 'Escape' && !el.dlgClose.hidden) hideOverlay();
    return;
  }
  if (el.reader.hidden) return;
  if (!state.book) return;

  const rtl = !!state.book.rtl;
  switch (e.key) {
    case 'Escape': e.preventDefault(); closeReader(); break;
    case 'ArrowRight': e.preventDefault(); if (rtl) prevPage(); else nextPage(); break;
    case 'ArrowLeft': e.preventDefault(); if (rtl) nextPage(); else prevPage(); break;
    case 'ArrowDown': case ' ': case 'PageDown': e.preventDefault(); nextPage(); break;
    case 'ArrowUp': case 'PageUp': e.preventDefault(); prevPage(); break;
    case 'Home': e.preventDefault(); goTo(0, false); break;
    case 'End': e.preventDefault(); goTo(state.total - 1, false); break;
    case '+': case '=': e.preventDefault(); setZoom(state.zoom + 0.25); break;
    case '-': case '_': e.preventDefault(); setZoom(state.zoom - 0.25); break;
    case '0': e.preventDefault(); resetZoom(); break;
    case 'f': case 'F': e.preventDefault(); toggleFullscreen(); break;
    default: break;
  }
});

function toggleFullscreen() {
  if (document.fullscreenElement) {
    document.exitFullscreen().catch(() => {});
  } else {
    document.documentElement.requestFullscreen().catch(() => {});
  }
}

/* --------------------------------------------------------------------------
 * 阅读进度保存
 * ------------------------------------------------------------------------ */

function scheduleSave() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(flushSave, 500);
}

function flushSave() {
  clearTimeout(saveTimer);
  saveTimer = null;
  if (!state.book) return;
  const id = state.book.id;
  const page = state.page;
  state.book.lastPage = page;
  fetch(`/api/books/${id}`, jsonBody('PATCH', { lastPage: page })).catch(() => {});
}

/* --------------------------------------------------------------------------
 * 工具栏按钮
 * ------------------------------------------------------------------------ */

el.btnBack.addEventListener('click', closeReader);

el.btnRtl.addEventListener('click', async () => {
  if (!state.book) return;
  const rtl = !state.book.rtl;
  state.book.rtl = rtl;
  updateHud();
  try {
    await api(`/api/books/${state.book.id}`, jsonBody('PATCH', { rtl }));
  } catch (e) {
    toast('保存失败：' + e.message);
  }
});

el.btnAnim.addEventListener('click', () => {
  state.anim = state.anim === 'none' ? 'flip' : 'none';
  localStorage.setItem('ms.anim', state.anim);
  updateHud();
  toast(state.anim === 'none' ? '已关闭翻页动画' : '已开启翻页动画');
});

el.btnFit.addEventListener('click', resetZoom);

el.slider.addEventListener('input', () => {
  const target = parseInt(el.slider.value, 10) - 1;
  if (Number.isFinite(target)) goTo(target, false);
});

/* --------------------------------------------------------------------------
 * 启动
 * ------------------------------------------------------------------------ */

(async function init() {
  try {
    await loadBooks();
  } catch (err) {
    toast('连不上本地服务：' + (err && err.message ? err.message : err));
  }
  applyZoom();
  showLanHint();
})();

/** 在电脑上打开时，提示"手机用这个地址" */
async function showLanHint() {
  if (localStorage.getItem('ms.lanHint') === 'off') return;
  if (!/^(127\.0\.0\.1|localhost|\[::1\])$/.test(location.hostname)) return;

  let info;
  try {
    info = await api('/api/ping');
  } catch (e) {
    return;
  }
  const ip = (info.lan || [])[0];
  if (!ip) return;

  el.lanHint.innerHTML = '';

  const label = document.createElement('span');
  label.textContent = '手机上打开这个地址（同一个 Wi-Fi）：';

  const code = document.createElement('code');
  code.textContent = `http://${ip}:${info.port || location.port}/`;

  const close = document.createElement('button');
  close.className = 'lan-close';
  close.textContent = '×';
  close.title = '不再提示';
  close.addEventListener('click', () => {
    el.lanHint.hidden = true;
    localStorage.setItem('ms.lanHint', 'off');
  });

  el.lanHint.append(label, code, close);
  el.lanHint.hidden = false;
}
