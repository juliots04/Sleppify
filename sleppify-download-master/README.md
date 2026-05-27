# 🎵 YouTube to MP3 Downloader

## 📝 Description
This is a Flask-based web application that allows users to download YouTube videos as MP3 files. It supports both single video downloads and bulk downloads from an Excel file containing multiple YouTube URLs. The application uses `yt-dlp` for downloading and converting videos to MP3 format.

## ✨ Features
-   ✅ **Single Video Download**: Enter a YouTube URL to download a single video as MP3.
-   📊 **Bulk Download**: Upload an Excel file (.xlsx) with a column named 'LINKS' containing multiple YouTube URLs to download them in bulk.
-   🌐 **Web Interface**: User-friendly web interface built with Flask and Bootstrap.
-   💬 **Flash Messages**: Provides feedback on download success or errors.
-   📁 **File Access**: Option to open downloaded MP3 files directly from the browser (via `app.py`).

## ⚙️ Prerequisites
-   🐍 Python 3.x
-   🎥 FFmpeg (required for audio extraction by yt-dlp)
-   🌍 Internet connection for downloading videos

## 🔧 Installation
1.  📥 Clone or download the project files to your local machine.
2.  📂 Navigate to the project directory:
    ```
    cd path/to/youtube-to-mp3-downloader
    ```
3.  📦 Install the required Python packages:
    ```
    pip install -r requirements.txt
    ```
4.  🔗 Ensure FFmpeg is installed on your system. You can download it from [https://ffmpeg.org/download.html](https://ffmpeg.org/download.html) and add it to your system's PATH.

## 🚀 Usage
1.  ▶️ Run the main application (supports single and bulk downloads):
    ```
    python app.py
    ```
    Or, run the simplified version (only single downloads):
    ```
    python main.py
    ```
2.  🌐 Open your web browser and go to `http://127.0.0.1:5000`.

3.  🎵 **Single Download**:
    -   🔗 Enter a YouTube URL in the input field.
    -   ⬇️ Click "Download Mp3".
    -   📁 The MP3 will be downloaded to the `downloads/` folder (if using `app.py`).

4.  📊 **Bulk Download** (only available when running `app.py`):
    -   📄 Prepare an Excel file (.xlsx) with a column named 'LINKS' containing YouTube URLs.
    -   📤 Upload the file using the bulk upload form.
    -   ⬇️ Click "Download Mp3" to process all URLs in the file.

5.  👀 Monitor the flash messages for download status.

## 📂 Project Structure
```
├── app.py 
├── main.py 
├── requirements.txt
├── static/
│   ├── assets/
│   │   └── bg_3.jpg 
│   ├── script.js 
│   └── style.css
└── templates/
    └── index.html 
```

## 📝 Notes
-   📁 Downloaded MP3 files are saved in the `downloads/` directory when using `app.py`.
-   ⚠️ Ensure you have permission to download and convert YouTube videos in your region.
-   🔧 The application runs in debug mode by default; disable this for production use.
-   📊 For bulk downloads, the Excel file **must** have a column named 'LINKS' with the YouTube URLs.
-   🎵 If using `main.py`, only single downloads are supported, and files are saved to an `output/` directory.
