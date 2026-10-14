"""Tools for turning raw sensor-logger sessions into anonymized detector fixtures.

Modules:
    session: load and validate a logger session directory.
    geo: haversine distance, cumulative path distance, point offsetting.
    labels: convert logger tags into fixture labels.
    convert_session: CLI and function API producing a fixture CSV + labels JSON.
"""
