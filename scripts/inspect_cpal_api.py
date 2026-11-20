#!/usr/bin/env python3
"""Inspect cpal 0.18.2's real API surface from the crates.io tarball."""
import io
import re
import tarfile
import urllib.request

req = urllib.request.Request("https://crates.io/api/v1/crates/cpal/0.18.2/download", headers={"User-Agent": "dl"})
data = urllib.request.urlopen(req, timeout=90).read()
tf = tarfile.open(fileobj=io.BytesIO(data), mode="r:gz")

traits = tf.extractfile("cpal-0.18.2/src/traits.rs").read().decode()
i = traits.find("pub trait DeviceTrait")
blk = traits[i:i + 9000]
pat = re.compile(r"fn (\w+)\s*\(&self[^;{}]{0,90}")
for m in pat.finditer(blk):
    n = m.group(1)
    if any(k in n for k in ("supported", "build", "default", "config", "desc", "id")):
        print("DeviceTrait::" + m.group(0).replace("\n", " ")[:110])

for name in tf.getnames():
    if name.endswith(".rs"):
        s = tf.extractfile(name).read().decode()
        if "struct SampleRate" in s:
            i2 = s.find("struct SampleRate")
            print("== SampleRate in", name.split("/")[-1], "::", s[i2:i2 + 130].replace("\n", " "))
            j2 = s.find("impl SampleRate")
            if j2 >= 0:
                print(s[j2:j2 + 330].replace("\n", " "))
            break

# DeviceDescription name accessor
dd = tf.extractfile("cpal-0.18.2/src/device_description.rs").read().decode()
k = dd.find("impl DeviceDescription")
if k >= 0:
    print("==== DeviceDescription methods ====")
    for m in re.finditer(r"pub fn (\w+)\s*\(&self\)\s*->\s*([^\n]*)", dd[k:k + 1500]):
        print(" ", m.group(0).replace("\n", " ")[:80])

# build_output_stream data-callback shape: raw vs generic
i3 = traits.find("fn build_output_stream<")
print("==== build_output_stream generic ====")
print(traits[i3:i3 + 420].replace("\n", " "))
