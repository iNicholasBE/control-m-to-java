"""ctminv: inventory a Control-M or Autosys estate before migrating it.

Parsers turn definitions into one model (jobs + edges), classify puts every
job into one of four buckets, timegap looks for dependencies that only exist
in the run history, and synth builds a synthetic estate with a known answer.
"""

__version__ = "0.1.0"
