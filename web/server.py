#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
漫画书架 · 本地网页版服务端
==========================

把 PDF / 图片变成可翻页的网页漫画书。

- 只用 Python 标准库 + PyMuPDF(fitz) / Pillow，全程离线，不装任何东西
- 只监听 127.0.0.1，不对外网开放
- 页面图片落在本目录的 data/books/<id>/page_001.jpg

启动：双击 start.bat，或者 `python server.py`
"""

import json
import mimetypes
import os
import re
import shutil
import socket
import sys
import tempfile
import threading
import time
import traceback
import uuid
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, unquote, parse_qs
from urllib.request import urlopen

try:
    import fitz  # PyMuPDF
except ImportError:
    fitz = None

try:
    from PIL import Image, ImageOps

    try:
        RESAMPLE = Image.Resampling.LANCZOS   # Pillow >= 9.1
    except AttributeError:
        RESAMPLE = Image.LANCZOS
except ImportError:
    Image = None
    ImageOps = None
    RESAMPLE = None


APP_MARKER = "mangashelf-web-1"

# 让控制台中文尽量别乱码（配合 start.bat 里的 chcp 65001）
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.abspath(__file__))
DATA_DIR = os.path.join(ROOT, "data")
BOOKS_DIR = os.path.join(DATA_DIR, "books")
LIB_PATH = os.path.join(DATA_DIR, "library.json")

TARGET_WIDTH = 1600      # PDF 渲染宽度
IMAGE_MAX_EDGE = 1800    # 图片导入时的最长边
JPEG_QUALITY = 88
MAX_PAGES = 3000
MAX_FILES = 5000
DEFAULT_PORT = int(os.environ.get("MS_PORT") or "8971")
# 监听地址：默认 0.0.0.0，这样同一 Wi-Fi 下的手机也能访问；只想本机用就设 MS_BIND=127.0.0.1
BIND_ADDR = os.environ.get("MS_BIND") or "0.0.0.0"


def lan_ip():
    """拿到本机在局域网里的 IPv4（UDP connect 只是查路由表，不发包）。"""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except Exception:
        try:
            return socket.gethostbyname(socket.gethostname())
        except Exception:
            return None
    finally:
        s.close()

IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".webp", ".bmp", ".gif", ".avif", ".tif", ".tiff"}

PAGE_RE = re.compile(r"^page_(\d{3,})\.jpg$")
# 只有这种 URL 才允许读磁盘，避免任意文件被当静态资源下载
DATA_FILE_RE = re.compile(r"^/data/books/([A-Za-z0-9]{6,32})/(page_\d{3,4}\.jpg)$")

STATIC_FILES = {
    "/": ("index.html", "text/html; charset=utf-8"),
    "/index.html": ("index.html", "text/html; charset=utf-8"),
    "/app.js": ("app.js", "application/javascript; charset=utf-8"),
    "/style.css": ("style.css", "text/css; charset=utf-8"),
    "/app.ico": ("app.ico", "image/vnd.microsoft.icon"),
}

LIB_LOCK = threading.Lock()
JOBS = {}
JOBS_LOCK = threading.Lock()


# --------------------------------------------------------------------------
# 存储
# --------------------------------------------------------------------------

def ensure_dirs():
    os.makedirs(BOOKS_DIR, exist_ok=True)


def load_lib():
    if not os.path.exists(LIB_PATH):
        return {"books": []}
    try:
        with open(LIB_PATH, "r", encoding="utf-8") as f:
            data = json.load(f)
        if not isinstance(data, dict) or "books" not in data:
            return {"books": []}
        return data
    except Exception:
        return {"books": []}


def save_lib(lib):
    tmp = LIB_PATH + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(lib, f, ensure_ascii=False, indent=2)
    os.replace(tmp, LIB_PATH)


def book_dir(book_id):
    return os.path.join(BOOKS_DIR, book_id)


def list_pages(book_id):
    d = book_dir(book_id)
    if not os.path.isdir(d):
        return []
    names = [n for n in os.listdir(d) if PAGE_RE.match(n)]
    names.sort()
    return names


def page_count(book_id):
    return len(list_pages(book_id))


def create_book(title):
    book_id = uuid.uuid4().hex[:12]
    os.makedirs(book_dir(book_id), exist_ok=True)
    book = {
        "id": book_id,
        "title": title or "未命名漫画",
        "rtl": True,
        "lastPage": 0,
        "createdAt": int(time.time() * 1000),
    }
    with LIB_LOCK:
        lib = load_lib()
        lib["books"].insert(0, book)
        save_lib(lib)
    return book


def get_book(book_id):
    with LIB_LOCK:
        lib = load_lib()
    for b in lib["books"]:
        if b["id"] == book_id:
            return b
    return None


def update_book(book_id, patch):
    with LIB_LOCK:
        lib = load_lib()
        for b in lib["books"]:
            if b["id"] == book_id:
                for k in ("title", "rtl", "lastPage"):
                    if k in patch:
                        b[k] = patch[k]
                save_lib(lib)
                return b
    return None


def delete_book(book_id):
    with LIB_LOCK:
        lib = load_lib()
        before = len(lib["books"])
        lib["books"] = [b for b in lib["books"] if b["id"] != book_id]
        changed = len(lib["books"]) != before
        if changed:
            save_lib(lib)
    shutil.rmtree(book_dir(book_id), ignore_errors=True)
    return changed


def books_for_api():
    """只返回真的有页面的书，避免导入中断留下 0 页的空书。"""
    with LIB_LOCK:
        lib = load_lib()
    out = []
    for b in lib["books"]:
        n = page_count(b["id"])
        if n <= 0:
            continue
        item = dict(b)
        item["pageCount"] = n
        out.append(item)
    return out


# --------------------------------------------------------------------------
# 导入任务
# --------------------------------------------------------------------------

def new_job():
    jid = uuid.uuid4().hex[:12]
    with JOBS_LOCK:
        JOBS[jid] = {
            "status": "running",
            "done": 0,
            "total": 0,
            "bookId": None,
            "title": "",
            "error": None,
        }
    return jid


def patch_job(jid, **kw):
    with JOBS_LOCK:
        if jid in JOBS:
            JOBS[jid].update(kw)


def get_job(jid):
    with JOBS_LOCK:
        job = JOBS.get(jid)
        return dict(job) if job else None


def strip_ext(name):
    base = os.path.splitext(name)[0].strip()
    return base or name or "未命名漫画"


def render_pdf_to_book(raw, title, on_page=None):
    """把 PDF 逐页渲染成 JPEG，返回新建的 Book。失败会自己清理，异常往上抛。"""
    tmpdir = tempfile.mkdtemp(prefix="mangashelf-")
    tmp_pdf = os.path.join(tmpdir, "in.pdf")
    book = None
    try:
        if fitz is None:
            raise RuntimeError("没有安装 PyMuPDF，无法处理 PDF")
        with open(tmp_pdf, "wb") as f:
            f.write(raw)

        doc = fitz.open(tmp_pdf)
        try:
            if doc.needs_pass:
                raise RuntimeError("这个 PDF 有密码保护，暂时不支持导入")
            total = doc.page_count
            if total <= 0:
                raise RuntimeError("这个 PDF 里没有页面")
            if total > MAX_PAGES:
                raise RuntimeError("页数太多（%d 页），超过上限 %d" % (total, MAX_PAGES))

            book = create_book(title)
            dest = book_dir(book["id"])

            for i in range(total):
                page = doc.load_page(i)
                width = page.rect.width or 595.0
                zoom = TARGET_WIDTH / float(width)
                zoom = max(0.2, min(zoom, 8.0))
                pix = page.get_pixmap(matrix=fitz.Matrix(zoom, zoom), alpha=False)
                out = os.path.join(dest, "page_%03d.jpg" % (i + 1))
                with open(out, "wb") as f:
                    f.write(pix.tobytes("jpeg", jpg_quality=JPEG_QUALITY))
                if on_page is not None:
                    on_page(i + 1, total)
        finally:
            doc.close()

        return book
    except Exception:
        if book is not None:
            delete_book(book["id"])
        raise
    finally:
        shutil.rmtree(tmpdir, ignore_errors=True)


def import_pdf(jid, raw, title):
    """单个 PDF 的后台任务入口。"""
    try:
        book = render_pdf_to_book(raw, title, lambda done, total: patch_job(jid, done=done, total=total))
        patch_job(jid, status="done", bookId=book["id"], title=book["title"])
    except Exception as e:
        patch_job(jid, status="error", error=str(e) or "导入失败")


def natural_key(text):
    """自然排序：2.png 排在 10.png 前面，ch2/ 排在 ch10/ 前面。"""
    return [int(part) if part.isdigit() else part.lower() for part in re.split(r"(\d+)", text or "")]


def scan_folder(root_path):
    """递归收集图片和 PDF，按相对路径自然排序。"""
    images = []
    pdfs = []
    for dirpath, dirnames, filenames in os.walk(root_path):
        dirnames[:] = sorted([d for d in dirnames if not d.startswith(".")], key=natural_key)
        for name in filenames:
            if name.startswith("."):
                continue
            ext = os.path.splitext(name)[1].lower()
            if ext != ".pdf" and ext not in IMAGE_EXTS:
                continue
            full = os.path.join(dirpath, name)
            rel = os.path.relpath(full, root_path)
            if ext == ".pdf":
                pdfs.append((rel, full))
            else:
                images.append((rel, full))
    images.sort(key=lambda item: natural_key(item[0]))
    pdfs.sort(key=lambda item: natural_key(item[0]))
    return images, pdfs


def import_path(jid, root_path):
    """把一个本机文件夹直接导成漫画书（不经过浏览器上传）。"""
    books = []
    try:
        if not os.path.isdir(root_path):
            raise RuntimeError("路径不存在或者不是文件夹：%s" % root_path)

        images, pdfs = scan_folder(root_path)
        total = len(images) + len(pdfs)
        if total == 0:
            raise RuntimeError("这个文件夹里没有找到图片或 PDF（支持 jpg/png/webp/bmp/gif/avif/tif 和 pdf）")
        if total > MAX_FILES:
            raise RuntimeError("文件太多（%d 个），上限 %d 个" % (total, MAX_FILES))

        patch_job(jid, total=total)
        title = os.path.basename(os.path.normpath(root_path)) or "未命名漫画"
        done = 0

        # 1) 文件夹里的图片合成一本书，顺序 = 相对路径自然排序
        if images:
            book = create_book(title)
            books.append(book)
            patch_job(jid, bookId=book["id"], title=book["title"])
            for rel, full in images:
                try:
                    with open(full, "rb") as f:
                        raw = f.read()
                    import_image(raw, os.path.basename(full), book["id"], title)
                except Exception as e:
                    sys.stderr.write("  跳过 %s（%s）\n" % (rel, e))
                done += 1
                patch_job(jid, done=done)

        # 2) 文件夹里的 PDF 各自成为一本
        for rel, full in pdfs:
            try:
                with open(full, "rb") as f:
                    raw = f.read()
                b = render_pdf_to_book(raw, strip_ext(os.path.basename(full)))
                books.append(b)
            except Exception as e:
                sys.stderr.write("  跳过 %s（%s）\n" % (rel, e))
            done += 1
            patch_job(jid, done=done)

        if not books:
            raise RuntimeError("没有导入任何内容，这些文件可能都读不了")
        patch_job(jid, status="done", bookId=books[0]["id"], bookCount=len(books))
    except Exception as e:
        for b in books:
            delete_book(b["id"])
        patch_job(jid, status="error", error=str(e) or "导入失败")


def import_image(raw, name, book_id, title_hint):
    """单张图片：修 EXIF 旋转 → 白底合成 → 限尺寸 → JPEG 落盘。"""
    book = get_book(book_id) if book_id else None
    if book is None:
        book = create_book(title_hint or strip_ext(name))

    dest = book_dir(book["id"])
    os.makedirs(dest, exist_ok=True)
    index = page_count(book["id"]) + 1
    out = os.path.join(dest, "page_%03d.jpg" % index)

    if Image is None:
        # 没有 Pillow 就原样保存，浏览器自己能处理 JPEG/PNG 的 EXIF 方向
        with open(out, "wb") as f:
            f.write(raw)
    else:
        src = tempfile.SpooledTemporaryFile()
        src.write(raw)
        src.seek(0)
        try:
            im = Image.open(src)
            im = ImageOps.exif_transpose(im) or im
            if im.mode in ("RGBA", "LA", "P", "PA"):
                im = im.convert("RGBA")
                bg = Image.new("RGB", im.size, (255, 255, 255))
                bg.paste(im, mask=im.split()[-1])
                im = bg
            else:
                im = im.convert("RGB")
            longest = max(im.size)
            if longest > IMAGE_MAX_EDGE:
                scale = IMAGE_MAX_EDGE / float(longest)
                im = im.resize(
                    (max(1, int(im.width * scale)), max(1, int(im.height * scale))),
                    RESAMPLE,
                )
            im.save(out, "JPEG", quality=JPEG_QUALITY, optimize=True)
        finally:
            src.close()

    return book, page_count(book["id"])


# --------------------------------------------------------------------------
# 示例书（首次启动时生成，方便立刻试翻页）
# --------------------------------------------------------------------------

def seed_demo():
    if os.path.exists(LIB_PATH):
        return
    if fitz is None:
        return
    print("  首次启动，正在生成一本示例漫画…")
    try:
        doc = fitz.open()
        total = 20
        for i in range(total):
            page = doc.new_page(width=595, height=842)  # A4
            page.draw_rect(fitz.Rect(28, 28, 567, 814), color=(0.12, 0.12, 0.14), width=2.5)
            page.draw_rect(fitz.Rect(60, 90, 535, 260), color=(0.75, 0.78, 0.85), width=1.5)
            page.draw_rect(fitz.Rect(60, 300, 535, 560), color=(0.75, 0.78, 0.85), width=1.5)
            page.draw_rect(fitz.Rect(60, 600, 535, 760), color=(0.75, 0.78, 0.85), width=1.5)
            try:
                page.insert_text((80, 200), "示例漫画 · 第 %d 页" % (i + 1),
                                 fontsize=34, fontname="china-s", color=(0.1, 0.1, 0.12))
                page.insert_text((80, 350), "这一页只是用来测试翻页的。", fontsize=20,
                                 fontname="china-s", color=(0.35, 0.36, 0.4))
                page.insert_text((80, 390), "点两侧或按 ← → 翻页，", fontsize=20,
                                 fontname="china-s", color=(0.35, 0.36, 0.4))
                page.insert_text((80, 430), "按 Esc 回到书架。", fontsize=20,
                                 fontname="china-s", color=(0.35, 0.36, 0.4))
            except Exception:
                pass
            page.insert_text((80, 660), "PAGE %02d / %02d" % (i + 1, total),
                             fontsize=40, fontname="hebo", color=(0.85, 0.55, 0.15))
            page.insert_text((80, 720), "delete this book from the shelf when you are done",
                             fontsize=12, fontname="helv", color=(0.6, 0.6, 0.65))
        raw = doc.tobytes()
        doc.close()
        jid = new_job()
        import_pdf(jid, raw, "示例漫画（可删除）")
    except Exception:
        traceback.print_exc()


# --------------------------------------------------------------------------
# HTTP
# --------------------------------------------------------------------------

class Server(ThreadingHTTPServer):
    daemon_threads = True

    def handle_error(self, request, client_address):
        exc = sys.exc_info()[1]
        # 浏览器预连接 / 提前断开很常见，不要把它当错误刷屏
        if isinstance(exc, (ConnectionResetError, ConnectionAbortedError, BrokenPipeError)):
            return
        super().handle_error(request, client_address)


class Handler(BaseHTTPRequestHandler):
    server_version = "MangaShelfWeb/1.0"
    protocol_version = "HTTP/1.1"

    # ---------------- 基础工具 ----------------

    def handle_one_request(self):
        # 同一个 handler 实例会在 keep-alive 连接上服务多个请求，请求体缓存必须按请求重置
        self._body_cache = None
        super().handle_one_request()

    def log_message(self, fmt, *args):
        # 静音常规请求日志，只在控制台打错误
        if args and isinstance(args[0], str) and args[0].startswith(("4", "5")):
            sys.stderr.write("[%s] %s\n" % (self.log_date_time_string(), fmt % args))

    def send_json(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def send_error_text(self, code, msg):
        body = msg.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        # 出错时直接断开连接：避免请求体没读完导致后续请求报文错位
        self.send_header("Connection", "close")
        self.close_connection = True
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def send_file(self, path, content_type=None):
        if not os.path.isfile(path):
            self.send_error_text(404, "文件不存在")
            return
        try:
            size = os.path.getsize(path)
            f = open(path, "rb")
        except OSError:
            self.send_error_text(500, "读不到文件")
            return
        with f:
            if content_type is None:
                content_type = mimetypes.guess_type(path)[0] or "application/octet-stream"
            self.send_response(200)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(size))
            self.send_header("Cache-Control", "no-cache")
            self.end_headers()
            try:
                shutil.copyfileobj(f, self.wfile)
            except (BrokenPipeError, ConnectionResetError):
                pass

    def check_origin(self):
        """挡掉别的网站对本机服务发起的写操作（防 DNS rebinding）。"""
        origin = self.headers.get("Origin")
        if not origin or origin == "null":
            return True
        host = self.headers.get("Host") or ""
        return origin in ("http://" + host, "https://" + host)

    def read_body(self):
        """读取并缓存请求体。必须尽早调用，否则 keep-alive 会失步。"""
        cached = getattr(self, "_body_cache", None)
        if cached is not None:
            return cached
        try:
            n = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            n = 0
        if n <= 0:
            self._body_cache = b""
            return self._body_cache
        buf = bytearray()
        while len(buf) < n:
            chunk = self.rfile.read(min(1 << 16, n - len(buf)))
            if not chunk:
                break
            buf += chunk
        self._body_cache = bytes(buf)
        return self._body_cache

    def read_json(self):
        raw = self.read_body()
        if not raw:
            return {}
        try:
            return json.loads(raw.decode("utf-8"))
        except Exception:
            return {}

    def resolve_data_path(self, url_path):
        """只放行 /data/books/<id>/page_NNN.jpg 这一种形式，别的一律拒绝。"""
        m = DATA_FILE_RE.match(url_path)
        if not m:
            return None
        book_id, name = m.group(1), m.group(2)
        target = os.path.realpath(os.path.join(BOOKS_DIR, book_id, name))
        if not target.startswith(os.path.realpath(BOOKS_DIR) + os.sep):
            return None
        return target

    # ---------------- GET ----------------

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path
        query = parse_qs(parsed.query)
        self.read_body()

        try:
            if path == "/api/ping":
                ip = lan_ip()
                self.send_json({
                    "app": APP_MARKER,
                    "python": sys.version.split()[0],
                    "pymupdf": bool(fitz),
                    "pillow": bool(Image),
                    "lan": [ip] if ip else [],
                    "port": self.server.server_address[1],
                })
                return

            if path == "/api/books":
                self.send_json({"books": books_for_api()})
                return

            if path.startswith("/api/jobs/"):
                jid = path[len("/api/jobs/"):]
                job = get_job(jid)
                if job is None:
                    self.send_error_text(404, "任务不存在")
                    return
                if job["status"] == "done" and job["bookId"]:
                    n = page_count(job["bookId"])
                    job["pageCount"] = n
                    book = get_book(job["bookId"])
                    if book:
                        b = dict(book)
                        b["pageCount"] = n
                        job["book"] = b
                self.send_json(job)
                return

            if path in STATIC_FILES:
                name, ctype = STATIC_FILES[path]
                self.send_file(os.path.join(ROOT, name), ctype)
                return

            if path.startswith("/data/"):
                target = self.resolve_data_path(path)
                if target is None:
                    self.send_error_text(403, "禁止访问")
                    return
                self.send_file(target)
                return

            if path == "/favicon.ico":
                self.send_error_text(404, "no icon")
                return

            self.send_error_text(404, "not found")
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception:
            traceback.print_exc()
            try:
                self.send_error_text(500, "服务器内部错误")
            except Exception:
                pass

    # ---------------- POST ----------------

    def do_POST(self):
        parsed = urlparse(self.path)
        path = parsed.path
        query = parse_qs(parsed.query)
        if not self.check_origin():
            self.send_error_text(403, "禁止跨站请求")
            return
        self.read_body()

        try:
            if path == "/api/books":
                body = self.read_json()
                title = (body.get("title") or "").strip() or "未命名漫画"
                book = create_book(title)
                item = dict(book)
                item["pageCount"] = 0
                self.send_json({"book": item}, 201)
                return

            if path == "/api/import-pdf":
                if fitz is None:
                    self.send_error_text(500, "服务端缺少 PyMuPDF，无法处理 PDF")
                    return
                name = unquote(query.get("name", [""])[0]) or "未命名漫画.pdf"
                raw = self.read_body()
                if not raw:
                    self.send_error_text(400, "没有收到文件内容")
                    return
                title = strip_ext(name)
                jid = new_job()
                patch_job(jid, title=title)
                threading.Thread(target=import_pdf, args=(jid, raw, title), daemon=True).start()
                self.send_json({"jobId": jid, "title": title}, 202)
                return

            if path == "/api/import-image":
                name = unquote(query.get("name", [""])[0]) or "image.jpg"
                book_id = (query.get("book", [""])[0] or "").strip()
                title_hint = unquote(query.get("title", [""])[0])
                raw = self.read_body()
                if not raw:
                    self.send_error_text(400, "没有收到文件内容")
                    return
                book, n = import_image(raw, name, book_id, title_hint)
                item = dict(book)
                item["pageCount"] = n
                self.send_json({"book": item, "page": n})
                return

            if path == "/api/import-path":
                # 本机路径导入能读这台电脑上的任意文件，只允许本机请求
                if self.client_address[0] not in ("127.0.0.1", "::1"):
                    self.send_error_text(403, "本机路径导入只能在电脑上操作（手机请用「文件夹」或「文件」）")
                    return
                body = self.read_json()
                target = str(body.get("path") or "").strip().strip('"').strip("'")
                if not target:
                    self.send_error_text(400, "请提供文件夹路径")
                    return
                target = os.path.abspath(os.path.expanduser(target))
                if not os.path.isdir(target):
                    self.send_error_text(400, "路径不存在或者不是文件夹：%s" % target)
                    return
                jid = new_job()
                threading.Thread(target=import_path, args=(jid, target), daemon=True).start()
                self.send_json({"jobId": jid, "path": target}, 202)
                return

            self.send_error_text(404, "not found")
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as e:
            traceback.print_exc()
            try:
                self.send_error_text(500, str(e) or "导入失败")
            except Exception:
                pass

    # ---------------- PATCH ----------------

    def do_PATCH(self):
        parsed = urlparse(self.path)
        path = parsed.path
        if not self.check_origin():
            self.send_error_text(403, "禁止跨站请求")
            return
        self.read_body()
        try:
            m = re.match(r"^/api/books/([0-9a-zA-Z]+)$", path)
            if m:
                body = self.read_json()
                patch = {}
                if "title" in body:
                    patch["title"] = (str(body["title"]).strip() or "未命名漫画")[:120]
                if "rtl" in body:
                    patch["rtl"] = bool(body["rtl"])
                if "lastPage" in body:
                    try:
                        patch["lastPage"] = max(0, int(body["lastPage"]))
                    except (TypeError, ValueError):
                        pass
                book = update_book(m.group(1), patch)
                if book is None:
                    self.send_error_text(404, "书不存在")
                    return
                item = dict(book)
                item["pageCount"] = page_count(book["id"])
                self.send_json({"book": item})
                return
            self.send_error_text(404, "not found")
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception:
            traceback.print_exc()
            self.send_error_text(500, "更新失败")

    # ---------------- DELETE ----------------

    def do_DELETE(self):
        parsed = urlparse(self.path)
        path = parsed.path
        if not self.check_origin():
            self.send_error_text(403, "禁止跨站请求")
            return
        self.read_body()
        try:
            m = re.match(r"^/api/books/([0-9a-zA-Z]+)$", path)
            if m:
                ok = delete_book(m.group(1))
                if not ok:
                    self.send_error_text(404, "书不存在")
                    return
                self.send_json({"ok": True})
                return
            self.send_error_text(404, "not found")
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception:
            traceback.print_exc()
            self.send_error_text(500, "删除失败")


# --------------------------------------------------------------------------
# 启动
# --------------------------------------------------------------------------

def port_free(port):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(0.3)
    try:
        return s.connect_ex(("127.0.0.1", port)) != 0
    finally:
        s.close()


def already_running(port):
    """端口上是不是已经有一个自己人（避免开两份抢同一个 library.json）。"""
    try:
        with urlopen("http://127.0.0.1:%d/api/ping" % port, timeout=0.8) as r:
            data = json.loads(r.read().decode("utf-8"))
        return data.get("app") == APP_MARKER
    except Exception:
        return False


def find_running_instance():
    """本例只允许跑一份：在常用端口段里找已有的实例。"""
    for p in range(DEFAULT_PORT, DEFAULT_PORT + 10):
        if already_running(p):
            return p
    return None


def main():
    ensure_dirs()

    existing = find_running_instance()
    if existing:
        url = "http://127.0.0.1:%d/" % existing
        print()
        print("  漫画书架已经在运行了：%s" % url)
        print("  直接给你打开浏览器，这个窗口可以关掉。")
        try:
            webbrowser.open(url)
        except Exception:
            pass
        return

    if fitz is None:
        print("=" * 60)
        print("  警告：没有找到 PyMuPDF，PDF 导入不可用（图片导入仍可用）")
        print("  安装方法：  pip install pymupdf")
        print("=" * 60)

    port = DEFAULT_PORT
    if not port_free(port):
        chosen = None
        for p in range(DEFAULT_PORT + 1, DEFAULT_PORT + 30):
            if port_free(p):
                chosen = p
                break
        if chosen is None:
            print("找不到可用端口，请关掉占用 %d 的程序后重试" % DEFAULT_PORT)
            input("按回车退出...")
            return
        port = chosen
        print("端口 %d 被占用，改用 %d" % (DEFAULT_PORT, port))

    seed_demo()

    httpd = Server((BIND_ADDR, port), Handler)
    url = "http://127.0.0.1:%d/" % port
    ip = lan_ip()

    print()
    print("  漫画书架已经启动")
    print("  电脑上： %s" % url)
    if ip and BIND_ADDR == "0.0.0.0":
        print("  手机上： http://%s:%d/   （同一个 Wi-Fi 下）" % (ip, port))
    print("  数据：   %s" % DATA_DIR)
    print("  关闭这个窗口（或按 Ctrl+C）就会停止")
    print()

    no_browser = os.environ.get("MS_NO_BROWSER") == "1" or "--no-browser" in sys.argv
    if not no_browser:
        try:
            webbrowser.open(url)
        except Exception:
            pass

    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n已停止")
    finally:
        httpd.server_close()


if __name__ == "__main__":
    main()
