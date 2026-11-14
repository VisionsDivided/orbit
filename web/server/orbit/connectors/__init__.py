# Copyright 2026 The Orbit authors
# SPDX-License-Identifier: Apache-2.0
"""Media-server connectors (ADR-001 D6): one contract (base.py), one module per server kind."""
from .base import Connector, ConnectorError, Linked  # noqa: F401
from .jellyfin import Jellyfin
from .plex import Plex

KINDS = {"jellyfin": Jellyfin, "plex": Plex}
