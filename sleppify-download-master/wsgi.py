import sys
import os

# Ruta del proyecto
sys.path.insert(0, os.path.dirname(__file__))

# Paquetes instalados con pip en alwaysdata
_home = os.path.expanduser('~')
_site = os.path.join(_home, '.local', 'lib', 'python3.12', 'site-packages')
if _site not in sys.path:
    sys.path.insert(0, _site)

from app import app as application

if __name__ == "__main__":
    application.run()
