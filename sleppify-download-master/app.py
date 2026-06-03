import os
import time as _time
import yt_dlp
from flask import Flask, request, jsonify, Response, render_template

app = Flask(__name__)
app.secret_key = 'downloadmp3'

# ─── Cookies path (relative to this file, so it always works on the server) ───
_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
_COOKIES_PATH = os.path.join(_SCRIPT_DIR, 'cookies.txt')

import contextlib
import tempfile

@contextlib.contextmanager
def get_ydl_opts(request_headers=None):
    """
    Context manager that yields yt-dlp options.
    If the client sends an 'X-Youtube-Cookie' header, it securely creates a temporary 
    Netscape-format cookie file for the lifetime of the block, avoiding bot detection.
    """
    opts = {
        'extractor_args': {'youtube': {'player_client': ['android', 'ios']}},
        'quiet': True,
        'no_warnings': True,
        'no_playlist': True,
    }
    
    tmp_cookie_file = None
    raw_cookie = request_headers.get('X-Youtube-Cookie') if request_headers else None

    if raw_cookie:
        tmp_cookie_file = tempfile.NamedTemporaryFile(mode='w+', delete=False, suffix='.txt', encoding='utf-8')
        tmp_cookie_file.write("# Netscape HTTP Cookie File\n")
        parts = [p.strip() for p in raw_cookie.split(';') if p.strip()]
        for p in parts:
            if '=' in p:
                k, v = p.split('=', 1)
                tmp_cookie_file.write(f".youtube.com\tTRUE\t/\tTRUE\t2147483647\t{k}\t{v}\n")
        tmp_cookie_file.flush()
        opts['cookiefile'] = tmp_cookie_file.name
        tmp_cookie_file.close()
    elif os.path.isfile(_COOKIES_PATH):
        opts['cookiefile'] = _COOKIES_PATH

    try:
        yield opts
    finally:
        if tmp_cookie_file and os.path.isfile(tmp_cookie_file.name):
            try: os.unlink(tmp_cookie_file.name)
            except: pass


@app.errorhandler(500)
def handle_500(e):
    import traceback
    return jsonify({"status": "error", "error_type": type(e).__name__, "error_message": str(e), "traceback": traceback.format_exc()}), 500

@app.errorhandler(Exception)
def handle_exception(e):
    import traceback
    return jsonify({"status": "error", "error_type": type(e).__name__, "error_message": str(e), "traceback": traceback.format_exc()}), 500

@app.errorhandler(404)
def handle_404(e):
    return jsonify({"status": "error", "error": "Not found"}), 404

@app.route('/')
def index():
    return render_template('index.html')

def _stream_and_cleanup(file_path, chunk_size=65536):
    """Generator that streams a file in chunks and deletes it after."""
    try:
        with open(file_path, 'rb') as f:
            while True:
                chunk = f.read(chunk_size)
                if not chunk:
                    break
                yield chunk
    finally:
        try: os.unlink(file_path)
        except: pass
        parent = os.path.dirname(file_path)
        try: os.rmdir(parent)
        except: pass

# Format priority for STREAMING (must return a single URL, no ffmpeg merge):
#  1. 22              → 720p pre-muxed mp4 (ideal, single URL)
#  2. 18              → 360p pre-muxed mp4 (fallback, single URL)
#  3. best[ext=mp4]   → best pre-muxed mp4 (single URL)
#  4. bestaudio[ext=m4a] → best m4a audio (single URL, works for music)
#  5. bestaudio       → any best audio (single URL, absolute fallback)
#
# NOTE: We intentionally avoid 'best' alone because on modern YouTube
# (DASH-only videos) it raises "Requested format is not available".
# bestaudio ALWAYS resolves to a single URL on YouTube.
VIDEO_FORMAT = '22/18/best[ext=mp4]/bestaudio[ext=m4a]/bestaudio'

# Streaming uses the same selector — always returns a single URL.
STREAM_FORMAT = '22/18/best[ext=mp4]/bestaudio[ext=m4a]/bestaudio'

# In-memory stream URL cache (avoids re-resolving on each Range/seek request)
_stream_url_cache = {}  # {video_id: {'url': str, 'content_length': int, 'ts': float}}
_STREAM_CACHE_TTL = 4 * 3600  # 4 hours (googlevideo URLs expire ~6h)


@app.route('/api/info', methods=['GET'])
def api_info():
    """Show server configuration: yt-dlp version, cookies status, player clients."""
    try:
        version = yt_dlp.version.__version__
    except Exception:
        version = "unknown"

    cookies_present = os.path.isfile(_COOKIES_PATH)
    cookies_size = os.path.getsize(_COOKIES_PATH) if cookies_present else 0

    return jsonify({
        "status": "ok",
        "yt_dlp_version": version,
        "cookies_path": _COOKIES_PATH,
        "cookies_present": cookies_present,
        "cookies_size_bytes": cookies_size,
        "player_clients": ["android", "ios"],
        "video_format": VIDEO_FORMAT,
    })


@app.route('/api/video', methods=['POST'])
def api_video():
    """Download 720p mp4 (fallback 360p) to temp file, then stream to client."""
    data = request.get_json()
    if not data or 'url' not in data:
        return jsonify({"error": "URL missing"}), 400

    youtube_url = data['url']

    import tempfile, glob, shutil

    tmpdir = None
    try:
        tmpdir = tempfile.mkdtemp()
        _ffmpeg = os.path.join(_SCRIPT_DIR, 'ffmpeg')
        
        with get_ydl_opts(request.headers) as base_opts:
            dl_opts = {
                **base_opts,
                'format': '22/18/bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best',
                'outtmpl': os.path.join(tmpdir, '%(id)s.%(ext)s'),
                'http_chunk_size': 10485760,
                'merge_output_format': 'mp4',
            }
            if os.path.isfile(_ffmpeg):
                dl_opts['ffmpeg_location'] = _ffmpeg

            print(f"[VIDEO] downloading: {youtube_url}")
            with yt_dlp.YoutubeDL(dl_opts) as ydl:
                ydl.download([youtube_url])

        files = glob.glob(os.path.join(tmpdir, '*'))
        if not files:
            print(f"[VIDEO] no file produced: {youtube_url}")
            shutil.rmtree(tmpdir, ignore_errors=True)
            return jsonify({"error": "Download produced no file"}), 500

        out_file = files[0]
        file_size = os.path.getsize(out_file)
        print(f"[VIDEO] success: {youtube_url} size={file_size}")

        return Response(
            _stream_and_cleanup(out_file),
            mimetype="video/mp4",
            headers={
                "Content-Disposition": "attachment; filename=\"video.mp4\"",
                "Content-Length": str(file_size),
            }
        )
    except Exception as e:
        print(f"[VIDEO] error: {youtube_url} — {e}")
        if tmpdir:
            shutil.rmtree(tmpdir, ignore_errors=True)
        return jsonify({"error": str(e)}), 500



@app.route('/api/stream/<video_id>', methods=['GET'])
def api_stream_cached(video_id):
    """
    Optimized streaming proxy: resolves once, caches URL, then proxies bytes.
    Supports Range requests for seeking without re-resolving yt-dlp each time.
    Streams 720p mp4 (fallback 360p). ExoPlayer should point here: GET /api/stream/<video_id>
    """
    import urllib.request

    now = _time.time()
    cached = _stream_url_cache.get(video_id)

    # Resolve if not cached or expired
    if not cached or (now - cached['ts']) > _STREAM_CACHE_TTL:
        with get_ydl_opts(request.headers) as base_opts:
            ydl_opts = {
                **base_opts,
                'format': VIDEO_FORMAT,
                'skip_download': True,
            }

            try:
                youtube_url = f'https://www.youtube.com/watch?v={video_id}'
                with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                    info = ydl.extract_info(youtube_url, download=False)
                if not info or 'url' not in info:
                    return jsonify({"error": "Could not extract stream URL"}), 500

                stream_url = info['url']
                content_length = info.get('filesize') or info.get('filesize_approx') or 0

                # HEAD request to get accurate Content-Length if needed
                if not content_length:
                    try:
                        head_req = urllib.request.Request(stream_url, method='HEAD',
                            headers={'User-Agent': 'Mozilla/5.0'})
                        head_resp = urllib.request.urlopen(head_req)
                        content_length = int(head_resp.headers.get('Content-Length', 0))
                        head_resp.close()
                    except Exception:
                        pass

                _stream_url_cache[video_id] = {
                    'url': stream_url,
                    'content_length': content_length,
                    'ts': now,
                }
                cached = _stream_url_cache[video_id]
        except Exception as e:
            print(f"[STREAM] resolve error: {video_id} — {e}")
            return jsonify({"error": str(e)}), 500

    # Now proxy from cached URL
    stream_url = cached['url']
    content_length = cached['content_length']

    range_header = request.headers.get('Range')
    range_start = 0
    range_end = content_length - 1 if content_length else None

    if range_header and content_length:
        try:
            range_spec = range_header.replace('bytes=', '')
            parts = range_spec.split('-')
            range_start = int(parts[0]) if parts[0] else 0
            range_end = int(parts[1]) if parts[1] else (content_length - 1)
        except (ValueError, IndexError):
            pass

    # Build upstream Range request
    req_headers = {'User-Agent': 'Mozilla/5.0'}
    if content_length and (range_start > 0 or (range_end is not None and range_end < content_length - 1)):
        req_headers['Range'] = f'bytes={range_start}-{range_end}'

    try:
        upstream_req = urllib.request.Request(stream_url, headers=req_headers)
        upstream_resp = urllib.request.urlopen(upstream_req)
    except urllib.error.HTTPError as he:
        if he.code == 403:
            # URL expired, invalidate cache and retry once
            _stream_url_cache.pop(video_id, None)
            print(f"[STREAM] 403 from CDN for {video_id}, cache invalidated. Client should retry.")
            return jsonify({"error": "Stream URL expired, retry"}), 410
        elif he.code == 416:
            # The client requested a range beyond the file size (file fully downloaded)
            return Response("Requested range not satisfiable", status=416)
        raise

    def generate():
        try:
            while True:
                chunk = upstream_resp.read(65536)
                if not chunk:
                    break
                yield chunk
        finally:
            upstream_resp.close()

    resp_length = (range_end - range_start + 1) if (range_end is not None and content_length) else content_length
    resp_headers = {
        'Content-Type': 'video/mp4',
        'Accept-Ranges': 'bytes',
    }
    if content_length:
        resp_headers['Content-Length'] = str(resp_length)

    if range_header and content_length and range_start > 0:
        resp_headers['Content-Range'] = f'bytes {range_start}-{range_end}/{content_length}'
        return Response(generate(), status=206, headers=resp_headers)
    else:
        return Response(generate(), status=200, headers=resp_headers)



@app.route('/api/health', methods=['GET'])
def api_health():
    """Health check - returns yt-dlp version, cookies status, bgutil POT server status."""
    import socket as _socket
    try:
        version = yt_dlp.version.__version__
    except Exception:
        version = "unknown"

    # Check bgutil POT server (try IPv6 first, then IPv4)
    bgutil_ok = False
    for family, addr in [(_socket.AF_INET6, '::1'), (_socket.AF_INET, '127.0.0.1')]:
        try:
            s = _socket.socket(family, _socket.SOCK_STREAM)
            s.settimeout(1)
            s.connect((addr, 4416))
            s.close()
            bgutil_ok = True
            break
        except Exception:
            pass

    cookies_present = os.path.isfile(_COOKIES_PATH)

    return jsonify({
        "status": "ok" if bgutil_ok else "degraded",
        "yt_dlp_version": version,
        "bgutil_pot_server": "running" if bgutil_ok else "not_running",
        "cookies_present": cookies_present,
        "cookies_path": _COOKIES_PATH,
        "player_clients": ["android", "ios"],
    })


@app.route('/api/test', methods=['POST'])
def api_test():
    """Debug endpoint: tries to extract info and returns detailed error."""
    import traceback
    data = request.get_json()
    if not data or 'url' not in data:
        return jsonify({"error": "URL missing"}), 400

    youtube_url = data['url']
    result = {}

    try:
        with get_ydl_opts(request.headers) as base_opts:
            ydl_opts_permissive = {
                **base_opts,
                'format': 'bestvideo*+bestaudio*/best',
                'quiet': True,
                'no_warnings': True,
                'skip_download': True,
            }
            with yt_dlp.YoutubeDL(ydl_opts_permissive) as ydl:
                info = ydl.extract_info(youtube_url, download=False)
                if not info:
                    return jsonify({"status": "fail", "reason": "No info extracted"})

                formats_count = len(info.get('formats', []))
                result.update({
                    "title": info.get('title'),
                    "duration": info.get('duration'),
                    "total_formats_available": formats_count,
                    "video_accessible": True,
                })
    except BaseException as e:
        result["video_accessible"] = False
        result["access_error"] = str(e)
        return jsonify({"status": "error", **result, "traceback": traceback.format_exc()}), 500

    try:
        with get_ydl_opts(request.headers) as base_opts:
            ydl_opts_stream = {
                **base_opts,
                'format': VIDEO_FORMAT,
                'quiet': True,
                'no_warnings': True,
                'skip_download': True,
            }
            with yt_dlp.YoutubeDL(ydl_opts_stream) as ydl:
                info2 = ydl.extract_info(youtube_url, download=False)
                stream_url = info2.get('url') if info2 else None
                result.update({
                    "status": "ok",
                    "stream_format_id": info2.get('format_id', '') if info2 else None,
                    "stream_ext": info2.get('ext', '') if info2 else None,
                    "stream_url_ok": bool(stream_url),
                    "stream_url_prefix": (stream_url or "")[:100] if stream_url else None,
                })
    except BaseException as e:
        result.update({
            "status": "partial_error",
            "stream_format_error": str(e),
            "traceback": traceback.format_exc()
        })
        return jsonify(result), 500

    return jsonify(result)


if __name__ == "__main__":
    app.run(debug=True, host="0.0.0.0")
