import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))

FIXTURES = os.path.join(HERE, "fixtures")
REPO = os.path.dirname(os.path.dirname(HERE))


def fixture(name):
    return os.path.join(FIXTURES, name)
