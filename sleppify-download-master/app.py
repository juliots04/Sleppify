import os
import time as _time
import yt_dlp
from flask import Flask, request, jsonify, Response, render_template

app = Flask(__name__)
app.secret_key = 'downloadmp3'

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

# Offline download: 720p pre-muxed mp4 (h264+aac 192kbps, no ffmpeg merge needed). Falls back to 360p.
VIDEO_FORMAT = '22/18'

# Streaming: same 720p pre-muxed mp4 — instant seek via Range requests, no ffmpeg needed.
STREAM_FORMAT = '22/18'

# In-memory stream URL cache (avoids re-resolving on each Range/seek request)
_stream_url_cache = {}  # {video_id: {'url': str, 'content_length': int, 'ts': float}}
_STREAM_CACHE_TTL = 4 * 3600  # 4 hours (googlevideo URLs expire ~6h)

@app.route('/api/video', methods=['POST'])
def api_video():
    """Download 720p mp4 (fallback 360p) to temp file, then stream to client. No cookies needed."""
    data = request.get_json()
    if not data or 'url' not in data:
        return jsonify({"error": "URL missing"}), 400

    youtube_url = data['url']

    import tempfile, glob, shutil

    tmpdir = None
    try:
        tmpdir = tempfile.mkdtemp()
        dl_opts = {
            'format': VIDEO_FORMAT,
            'quiet': True,
            'no_warnings': True,
            'no_playlist': True,
            'outtmpl': os.path.join(tmpdir, '%(id)s.%(ext)s'),
            'http_chunk_size': 10485760,
        }

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

@app.route('/api/resolve', methods=['GET', 'POST'])
def api_resolve():
    """Resolve direct stream URL for 720p mp4 (fallback 360p). No cookies needed."""
    if request.method == 'POST':
        data = request.get_json()
        youtube_url = data.get('url') if data else None
    else:
        youtube_url = request.args.get('url')

    if not youtube_url:
        return jsonify({"error": "URL missing"}), 400

    ydl_opts = {
        'format': VIDEO_FORMAT,
        'quiet': True,
        'no_warnings': True,
        'no_playlist': True,
        'skip_download': True,
    }

    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(youtube_url, download=False)
            if not info or 'url' not in info:
                return jsonify({"error": "Could not extract stream URL"}), 500

            return jsonify({
                "status": "ok",
                "url": info['url'],
                "duration": info.get('duration'),
                "title": info.get('title', ''),
            })
    except Exception as e:
        print(f"[RESOLVE] error: {youtube_url} — {e}")
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
        ydl_opts = {
            'format': VIDEO_FORMAT,
            'quiet': True,
            'no_warnings': True,
            'no_playlist': True,
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


@app.route('/api/streaming', methods=['GET', 'POST'])
def api_streaming():
    """Stream 720p mp4 (fallback 360p) via proxy. Supports Range requests for seeking. No cookies needed."""
    import urllib.request

    if request.method == 'POST':
        data = request.get_json()
        youtube_url = data.get('url') if data else None
    else:
        youtube_url = request.args.get('url')

    if not youtube_url:
        return jsonify({"error": "URL missing"}), 400

    ydl_opts = {
        'format': VIDEO_FORMAT,
        'quiet': True,
        'no_warnings': True,
        'no_playlist': True,
        'skip_download': True,
    }

    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(youtube_url, download=False)
            if not info or 'url' not in info:
                return jsonify({"error": "Could not extract direct stream URL"}), 500

            stream_url = info['url']
            content_length = info.get('filesize') or info.get('filesize_approx') or 0

            if not content_length:
                try:
                    head_req = urllib.request.Request(stream_url, method='HEAD',
                        headers={'User-Agent': 'Mozilla/5.0'})
                    head_resp = urllib.request.urlopen(head_req)
                    content_length = int(head_resp.headers.get('Content-Length', 0))
                    head_resp.close()
                except Exception:
                    pass

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

            req_headers = {'User-Agent': 'Mozilla/5.0'}
            if range_start > 0 or (range_end and range_end < content_length - 1):
                req_headers['Range'] = f'bytes={range_start}-{range_end}'

            upstream_req = urllib.request.Request(stream_url, headers=req_headers)
            upstream_resp = urllib.request.urlopen(upstream_req)

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

    except Exception as e:
        print(f"[STREAMING] error: {youtube_url} — {e}")
        return jsonify({"error": str(e)}), 500

@app.route('/api/resolve-for-stream', methods=['GET', 'POST'])
def api_resolve_for_stream():
    """Resolve direct googlevideo.com stream URL (720p mp4, fallback 360p) for use with CF Worker proxy.
    Returns JSON: {url, duration, title, content_length}. No cookies needed."""
    if request.method == 'POST':
        data = request.get_json()
        youtube_url = data.get('url') if data else None
        video_id = data.get('video_id') if data else None
    else:
        youtube_url = request.args.get('url')
        video_id = request.args.get('video_id')

    if not youtube_url and video_id:
        youtube_url = f'https://www.youtube.com/watch?v={video_id}'
    if not youtube_url:
        return jsonify({"error": "URL or video_id missing"}), 400

    ydl_opts = {
        'format': VIDEO_FORMAT,
        'quiet': True,
        'no_warnings': True,
        'no_playlist': True,
        'skip_download': True,
    }

    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(youtube_url, download=False)
            if not info or 'url' not in info:
                return jsonify({"error": "Could not extract stream URL"}), 500

            return jsonify({
                "status": "ok",
                "url": info['url'],
                "duration": info.get('duration'),
                "title": info.get('title', ''),
                "content_length": info.get('filesize') or info.get('filesize_approx') or 0,
                "ext": info.get('ext', 'mp4'),
                "itag": info.get('format_id', ''),
            })
    except Exception as e:
        print(f"[RESOLVE-STREAM] error: {youtube_url} — {e}")
        return jsonify({"error": str(e)}), 500

@app.route('/api/health', methods=['GET'])
def api_health():
    """Health check - returns yt-dlp version, bgutil POT server status."""
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

    return jsonify({
        "status": "ok" if bgutil_ok else "degraded",
        "yt_dlp_version": version,
        "bgutil_pot_server": "running" if bgutil_ok else "not_running",
    })

@app.route('/api/test', methods=['POST'])
def api_test():
    """Debug endpoint: tries to extract info and returns detailed error. No cookies needed."""
    import traceback
    data = request.get_json()
    if not data or 'url' not in data:
        return jsonify({"error": "URL missing"}), 400

    youtube_url = data['url']
    ydl_opts = {
        'format': VIDEO_FORMAT,
        'quiet': False,
        'no_warnings': False,
        'no_playlist': True,
        'skip_download': True,
    }

    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(youtube_url, download=False)
            if not info:
                return jsonify({"status": "fail", "reason": "No info extracted"})

            stream_url = info.get('url')
            formats_count = len(info.get('formats', []))

            return jsonify({
                "status": "ok",
                "title": info.get('title'),
                "duration": info.get('duration'),
                "ext": info.get('ext'),
                "has_direct_url": bool(stream_url),
                "total_formats": formats_count,
                "format_id": info.get('format_id', ''),
                "sample_url_prefix": (stream_url or "")[:100] if stream_url else None,
            })
    except BaseException as e:
        return jsonify({
            "status": "error",
            "error_type": type(e).__name__,
            "error_message": str(e),
            "traceback": traceback.format_exc()
        }), 500

if __name__ == "__main__":
    app.run(debug=True, host="0.0.0.0")
