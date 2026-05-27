import subprocess, sys

try:
    print(subprocess.check_output(['ffmpeg', '-version'], stderr=subprocess.STDOUT))
except FileNotFoundError:
    print('ffmpeg not found on PATH')