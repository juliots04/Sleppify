import requests
import yt_dlp
import os
import flask
from flask import app, Flask, render_template, request, flash, redirect, url_for

app = Flask(__name__)
app.secret_key = 'downloadmp3'  # Required for flashing messages


def download_video_as_mp3(youtube_url, output_path='output'):
    # Ensure the output directory exists
    if not os.path.exists(output_path):
        os.makedirs(output_path)

    # Define options for yt-dlp
    ydl_opts = {
        'ffmpeg_location': r'C:\ffmpeg-2025-10-21-git-535d4047d3-essentials_build\bin\ffmpeg.exe',  # Specify the path to ffmpeg
        'format': 'bestaudio/best',
        'postprocessors': [{
            'key': 'FFmpegExtractAudio',
            'preferredcodec': 'mp3',
            'preferredquality': '192',
        }],
        'outtmpl': f'{output_path}/%(title)s.%(ext)s',
    }

    # Download and convert the video
    with yt_dlp.YoutubeDL(ydl_opts) as ydl:
        ydl.download([youtube_url])


@app.route('/', methods=['POST', 'GET'])
def single_track():
    if request.method == 'POST':
        youtube_url = request.form.get('videoLink')  # Replace with your YouTube URL
        if youtube_url:
            try:
                # Call the function to download the video
                download_video_as_mp3(youtube_url)
                flash("MP3 Downloaded Successfully!", "success")  # Flash success message
            except Exception as e:
                flash(f"Error occurred: {str(e)}", "danger")  # Flash error message
        else:
            flash("Error: URL is missing!", "danger")  # Flash error message if URL is missing
        return redirect(url_for('single_track'))  # Redirect to the same route after form submission
    return render_template('index.html')


# Example usage



if __name__ == "__main__":
    app.run(debug=True, host="0.0.0.0")


# hosted url : http://127.0.0.1:5000/single_track



