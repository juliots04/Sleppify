import os
import time as _time
import logging
import logging.handlers
import yt_dlp
from flask import Flask, request, jsonify, Response, render_template

app = Flask(__name__)
app.secret_key = 'downloadmp3'

# ─── Cookies path (relative to this file, so it always works on the server) ───
_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
_COOKIES_PATH = os.path.join(_SCRIPT_DIR, 'cookies.txt')

# ─── File logger → logs/sleppify-YYYY-MM-DD.log (daily rotation, keep 7 days) ───
_LOG_DIR = os.path.join(_SCRIPT_DIR, 'logs')
os.makedirs(_LOG_DIR, exist_ok=True)
_log_handler = logging.handlers.TimedRotatingFileHandler(
    filename=os.path.join(_LOG_DIR, 'sleppify.log'),
    when='midnight', interval=1, backupCount=7, encoding='utf-8',
)
_log_handler.suffix = '%Y-%m-%d'
_log_handler.setFormatter(logging.Formatter('%(asctime)s %(levelname)s %(message)s'))
logger = logging.getLogger('sleppify')
logger.setLevel(logging.DEBUG)
logger.addHandler(_log_handler)
# Also mirror to stdout so uWSGI process log still shows everything
logger.addHandler(logging.StreamHandler())

import contextlib
import tempfile

# ─────────────────────────── PO token / anti-bot config ───────────────────────────
# The old code hard-coded player_client=['tv_embedded','web_embedded'] AND po_token=[],
# which DISABLES PO tokens. On a datacenter IP (alwaysdata) that yields the classic
# "[youtube+GetPOT] Requested format is not available" / "confirm you're not a bot" errors.
#
# The fix: let the bgutil PO-token provider supply GVS PO tokens automatically.
#  - HTTP mode:   a Node server on :4416  -> extractor_arg youtubepot-bgutilhttp:base_url
#  - Script mode: a built generate_once.js -> extractor_arg youtubepot-bgutilscript:script_path
# Install the plugin on the host with:  pip install bgutil-ytdlp-pot-provider
# and stand up ONE of the two providers (see SETUP_POTOKEN.md).
#
# Everything below is env-tunable so the same code runs on every host without edits.

# Player clients. "default" lets yt-dlp pick (best forward-compat, works once a PO
# provider is reachable). Override per host, e.g. YT_PLAYER_CLIENTS="web,web_music,tv".
_PLAYER_CLIENTS_ENV = os.environ.get('YT_PLAYER_CLIENTS', 'default').strip()

# bgutil HTTP provider base URL (leave default to match the /api/health probe on :4416).
_POT_BASE_URL = os.environ.get('BGUTIL_POT_BASE_URL', 'http://127.0.0.1:4416').strip()
# bgutil script-mode path (set this INSTEAD of a server on hosts with no long-running
# process, e.g. alwaysdata). Points at .../server/build/generate_once.js.
_POT_SCRIPT_PATH = os.environ.get('BGUTIL_POT_SCRIPT', '').strip()

# Egress proxy. THIS is the load-bearing fix for datacenter-IP bot blocks and for
# region-locked videos: a PO token does NOT lift a datacenter-IP block on its own
# (per yt-dlp maintainers) — you must egress through a residential/mobile IP or WARP,
# and generate the PO token through that SAME egress. Set e.g.
#   YTDLP_PROXY=http://user:pass@host:port   or   socks5://127.0.0.1:40000  (WARP)
# For region unblock, the proxy exit must be in an allowed country.
_YTDLP_PROXY = os.environ.get('YTDLP_PROXY', '').strip()


@contextlib.contextmanager
def get_ydl_opts(request_headers=None):
    extractor_args = {
        'youtubetab': {'skip': ['webpage']},
    }

    # Player client selection (omit entirely when "default" so yt-dlp chooses).
    player_clients = None
    if _PLAYER_CLIENTS_ENV and _PLAYER_CLIENTS_ENV.lower() != 'default':
        player_clients = [c.strip() for c in _PLAYER_CLIENTS_ENV.split(',') if c.strip()]
        extractor_args['youtube'] = {'player_client': player_clients}
        # NOTE: intentionally NOT setting 'po_token': [] — that is what disabled PO tokens.

    # Wire the bgutil PO-token provider. Script mode takes priority if a path is given,
    # otherwise HTTP mode (both keys are harmless if the plugin isn't installed).
    if _POT_SCRIPT_PATH:
        extractor_args['youtubepot-bgutilscript'] = {'script_path': [_POT_SCRIPT_PATH]}
        pot_mode = f'script:{_POT_SCRIPT_PATH}'
    else:
        extractor_args['youtubepot-bgutilhttp'] = {'base_url': [_POT_BASE_URL]}
        pot_mode = f'http:{_POT_BASE_URL}'

    cookies_on = os.path.isfile(_COOKIES_PATH)
    logger.info(f"[YDL_OPTS] player_clients={player_clients or 'default'} pot={pot_mode} cookies={'yes' if cookies_on else 'none'} proxy={'yes' if _YTDLP_PROXY else 'no'}")

    opts = {
        'extractor_args': extractor_args,
        'quiet': False,
        'no_warnings': False,
        'no_playlist': True,
        'verbose': True,
        'compat_opts': {'no-youtube-unavailable-videos'},
    }

    # Use cookies.txt when present (a logged-in account further reduces bot checks).
    if cookies_on:
        opts['cookiefile'] = _COOKIES_PATH

    # Route all yt-dlp traffic through the egress proxy when configured.
    if _YTDLP_PROXY:
        opts['proxy'] = _YTDLP_PROXY

    try:
        yield opts
    finally:
        pass


@app.errorhandler(500)
def handle_500(e):
    import traceback; tb = traceback.format_exc()
    logger.error(f"[FLASK500] {type(e).__name__}: {e}\n{tb}")
    return jsonify({"status": "error", "error_type": type(e).__name__, "error_message": str(e), "traceback": tb}), 500

@app.errorhandler(Exception)
def handle_exception(e):
    import traceback; tb = traceback.format_exc()
    logger.error(f"[FLASK_EXC] {type(e).__name__}: {e}\n{tb}")
    return jsonify({"status": "error", "error_type": type(e).__name__, "error_message": str(e), "traceback": tb}), 500

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


def _stream_and_cleanup_dir(file_path, tmpdir, chunk_size=65536):
    """Generator that streams a file in chunks, then removes its whole temp dir."""
    import shutil
    try:
        with open(file_path, 'rb') as f:
            while True:
                chunk = f.read(chunk_size)
                if not chunk:
                    break
                yield chunk
    finally:
        shutil.rmtree(tmpdir, ignore_errors=True)

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
VIDEO_FORMAT = 'best[ext=mp4]/bestaudio[ext=m4a]/bestaudio'

# Streaming uses the same selector — always returns a single URL.
STREAM_FORMAT = 'best[ext=mp4]/bestaudio[ext=m4a]/bestaudio'

# In-memory stream URL cache (avoids re-resolving on each Range/seek request)
_stream_url_cache = {}  # {video_id: {'url': str, 'content_length': int, 'ts': float}}
_STREAM_CACHE_TTL = 4 * 3600  # 4 hours (googlevideo URLs expire ~6h)

import threading

# Server-side downloads (yt-dlp) are the real bottleneck on free hosting. Cap how many run at
# once so that when a playlist download fans out across the 3 proxies, a single host is not
# overwhelmed (which is what caused timeouts / failed tracks). Tune per host via env var.
_MAX_CONCURRENT_DOWNLOADS = max(1, int(os.environ.get('MAX_CONCURRENT_DOWNLOADS', '2')))
_DOWNLOAD_SEMAPHORE = threading.Semaphore(_MAX_CONCURRENT_DOWNLOADS)

# Audio-only download: a single pre-muxed m4a file — NO ffmpeg merge — which is exactly what the
# app stores and plays (it is audio-first: the offline .mp4 holds music, never video). This is far
# lighter/faster than downloading 720p video and is the main reliability win for playlist offline.
AUDIO_DOWNLOAD_FORMAT = 'bestaudio[ext=m4a]/bestaudio/best'
# Optional full video download (pre-muxed 22/18 first to avoid a merge).
VIDEO_DOWNLOAD_FORMAT = 'best[ext=mp4]/bestaudio[ext=m4a]/bestaudio/best'


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
        "player_clients": _PLAYER_CLIENTS_ENV,
        "pot_mode": ("script:" + _POT_SCRIPT_PATH) if _POT_SCRIPT_PATH else ("http:" + _POT_BASE_URL),
        "proxy_configured": bool(_YTDLP_PROXY),
        "video_format": VIDEO_FORMAT,
    })


@app.route('/api/video', methods=['POST'])
def api_video():
    """
    Download a single track server-side (yt-dlp) and stream it back to the client.

    Body JSON: {"url": "<youtube url>"} or {"video_id": "<id>"}; optional {"format": "audio"|"video"}.
    Defaults to AUDIO (single-file m4a, no ffmpeg merge) — light and reliable for offline music; the
    app saves the bytes as .mp4 and plays the audio. Concurrency is capped so a playlist fan-out
    across the 3 proxy servers doesn't overwhelm one free-tier host (the cause of the old failures).
    The whole track is downloaded to a temp dir first, then streamed with a correct Content-Length
    (so the client can show real progress and detect truncation), and the temp dir is removed after.
    """
    data = request.get_json(silent=True) or {}
    youtube_url = data.get('url')
    if not youtube_url:
        vid = str(data.get('video_id') or data.get('id') or '').strip()
        if vid:
            youtube_url = f'https://www.youtube.com/watch?v={vid}'
    if not youtube_url:
        return jsonify({"error": "URL missing"}), 400

    want = str(data.get('format') or request.args.get('format') or 'audio').strip().lower()
    if want == 'video':
        fmt, mimetype = VIDEO_DOWNLOAD_FORMAT, 'video/mp4'
    else:
        want, fmt, mimetype = 'audio', AUDIO_DOWNLOAD_FORMAT, 'audio/mp4'

    import glob, shutil

    # Wait briefly for a free download slot; if the host is saturated, tell the client to retry
    # (it will fall back to another of the 3 proxies) instead of piling on.
    if not _DOWNLOAD_SEMAPHORE.acquire(timeout=45):
        return jsonify({"error": "Server busy, retry"}), 503

    tmpdir = None
    out_file = None
    file_size = 0
    try:
        tmpdir = tempfile.mkdtemp()
        _ffmpeg = os.path.join(_SCRIPT_DIR, 'ffmpeg')

        with get_ydl_opts(request.headers) as base_opts:
            dl_opts = {
                **base_opts,
                'format': fmt,
                'outtmpl': os.path.join(tmpdir, '%(id)s.%(ext)s'),
                'http_chunk_size': 10485760,
                'merge_output_format': 'mp4',
                'socket_timeout': 30,
                'retries': 3,
                'fragment_retries': 5,
                'concurrent_fragment_downloads': 4,
            }
            if os.path.isfile(_ffmpeg):
                dl_opts['ffmpeg_location'] = _ffmpeg

            try:
                ydl_version = yt_dlp.version.__version__
            except Exception:
                ydl_version = 'unknown'
            logger.info(f"[VIDEO] start want={want} fmt={fmt} url={youtube_url} yt_dlp={ydl_version} clients={dl_opts.get('extractor_args',{}).get('youtube',{}).get('player_client')}")
            with yt_dlp.YoutubeDL(dl_opts) as ydl:
                ydl.download([youtube_url])

        candidates = [f for f in glob.glob(os.path.join(tmpdir, '*'))
                      if os.path.isfile(f) and os.path.getsize(f) > 0]
        if not candidates:
            raise RuntimeError("Download produced no file")
        out_file = max(candidates, key=os.path.getsize)
        file_size = os.path.getsize(out_file)
        logger.info(f"[VIDEO] success want={want} url={youtube_url} size={file_size}")
    except Exception as e:
        import traceback
        if tmpdir:
            shutil.rmtree(tmpdir, ignore_errors=True)
        logger.error(f"[VIDEO] error url={youtube_url} err={e}\n{traceback.format_exc()}")
        return jsonify({"error": str(e)}), 502
    finally:
        # Free the slot as soon as the heavy server-side download is done — streaming the finished
        # file from disk is cheap and must not keep a download slot occupied.
        _DOWNLOAD_SEMAPHORE.release()

    return Response(
        _stream_and_cleanup_dir(out_file, tmpdir),
        mimetype=mimetype,
        headers={
            "Content-Length": str(file_size),
            "Content-Disposition": "attachment; filename=\"track.mp4\"",
            "Accept-Ranges": "none",
            "X-Download-Format": want,
        }
    )



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
                logger.error(f"[STREAM] resolve_error video_id={video_id} err={e}")
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
            logger.warning(f"[STREAM] 403 cdn_expired video_id={video_id}")
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

    if range_header and content_length:
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

    # PO-token provider status. Script mode needs no server, just the built script;
    # HTTP mode needs the Node server reachable (default :4416 from BGUTIL_POT_BASE_URL).
    if _POT_SCRIPT_PATH:
        pot_ok = os.path.isfile(_POT_SCRIPT_PATH)
        pot_status = f"script:{'ok' if pot_ok else 'missing'}"
    else:
        # Derive host/port from the configured base URL (default http://127.0.0.1:4416).
        try:
            from urllib.parse import urlparse
            _u = urlparse(_POT_BASE_URL)
            _host, _port = (_u.hostname or '127.0.0.1'), (_u.port or 4416)
        except Exception:
            _host, _port = '127.0.0.1', 4416
        pot_ok = False
        for family, addr in [(_socket.AF_INET6, '::1'), (_socket.AF_INET, _host)]:
            try:
                s = _socket.socket(family, _socket.SOCK_STREAM)
                s.settimeout(1)
                s.connect((addr, _port))
                s.close()
                pot_ok = True
                break
            except Exception:
                pass
        pot_status = f"http:{'running' if pot_ok else 'not_running'}"

    cookies_present = os.path.isfile(_COOKIES_PATH)

    return jsonify({
        "status": "ok" if pot_ok else "degraded",
        "yt_dlp_version": version,
        "bgutil_pot_provider": pot_status,
        "cookies_present": cookies_present,
        "cookies_path": _COOKIES_PATH,
        "player_clients": _PLAYER_CLIENTS_ENV,
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
