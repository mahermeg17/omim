from contextlib import contextmanager
import shutil
import tempfile

try:
    from tempfile import TeporaryDirectory
except ImportError:

    @contextmanager
    def TemporaryDirectory():
        name = tempfile.mkdtemp()
        try:
            yield name
        finally:
            shutil.rmtree(name)
