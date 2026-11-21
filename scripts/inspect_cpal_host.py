#!/usr/bin/env python3
"""Inspect cpal 0.18.2's Linux host selection + pipewire/pulse host entry points."""
import io
import re
import tarfile
import urllib.request

req = urllib.request.Request("https://crates.io/api/v1/crates/cpal/0.18.2/download", headers={"User-Agent": "dl"})
data = urllib.request.urlopen(req, timeout=90).read()
tf = tarfile.open(fileobj=io.BytesIO(data), mode="r:gz")

pm = tf.extractfile("cpal-0.18.2/src/platform/mod.rs").read().decode()
i = pm.find("default_host")
print("==== default_host dispatch ====")
print(pm[i : i + 900])
