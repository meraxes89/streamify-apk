//! AUTHENTICATED RESOLUTION VERIFICATION MATRIX (prb2.1 style)
//!
//!   YT_COOKIES='SID=...; SAPISID=...; __Secure-1PAPISID=...' \
//!   cargo run --example diagnose_auth
//!
//! Tests live: [1] session  [2] player call  [3] format census
//!             [4] stream probes x4 header combos  [5] throughput

use serde_json::Value;
use std::time::Instant;

const VIDEO_ID: &str = "jNQXAC9IVRw";
const BROWSER_UA: &str = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.82 Mobile Safari/537.36";

fn extract_cookie(jar: &str, name: &str) -> Option<String> {
    for part in jar.split(';') {
        let p = part.trim();
        if let Some(eq) = p.find('=') {
            if &p[..eq] == name {
                return Some(p[eq + 1..].to_string());
            }
        }
    }
    None
}

fn sha1_hex(data: &[u8]) -> String {
    // Minimal SHA-1 (public-domain style impl) to avoid new deps
    let mut h: [u32; 5] = [0x67452301, 0xEFCDAB89, 0x98BADCFE, 0x10325476, 0xC3D2E1F0];
    let ml = (data.len() as u64) * 8;
    let mut msg = data.to_vec();
    msg.push(0x80);
    while msg.len() % 64 != 56 {
        msg.push(0);
    }
    msg.extend_from_slice(&ml.to_be_bytes());
    for chunk in msg.chunks(64) {
        let mut w = [0u32; 80];
        for i in 0..16 {
            w[i] = u32::from_be_bytes([
                chunk[i * 4],
                chunk[i * 4 + 1],
                chunk[i * 4 + 2],
                chunk[i * 4 + 3],
            ]);
        }
        for i in 16..80 {
            w[i] = (w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16]).rotate_left(1);
        }
        let (mut a, mut b, mut c, mut d, mut e) = (h[0], h[1], h[2], h[3], h[4]);
        for (i, wi) in w.iter().enumerate() {
            let (f, k) = match i {
                0..=19 => ((b & c) | ((!b) & d), 0x5A827999),
                20..=39 => (b ^ c ^ d, 0x6ED9EBA1),
                40..=59 => ((b & c) | (b & d) | (c & d), 0x8F1BBCDC),
                _ => (b ^ c ^ d, 0xCA62C1D6),
            };
            let tmp = a
                .rotate_left(5)
                .wrapping_add(f)
                .wrapping_add(e)
                .wrapping_add(k)
                .wrapping_add(*wi);
            e = d;
            d = c;
            c = b;
            b = b.rotate_left(30);
            a = tmp;
        }
        h[0] = h[0].wrapping_add(a);
        h[1] = h[1].wrapping_add(b);
        h[2] = h[2].wrapping_add(c);
        h[3] = h[3].wrapping_add(d);
        h[4] = h[4].wrapping_add(e);
    }
    h.iter().map(|x| format!("{x:08x}")).collect()
}

fn main() {
    let cookies = std::env::var("YT_COOKIES").unwrap_or_default();
    if cookies.is_empty() {
        eprintln!("Usage: YT_COOKIES='<cookie string from logged-in youtube.com tab>' cargo run --example diagnose_auth");
        std::process::exit(1);
    }

    println!("═══ [1] SESSION ═══");
    let sapisid = extract_cookie(&cookies, "SAPISID")
        .or_else(|| extract_cookie(&cookies, "__Secure-3PAPISID"))
        .or_else(|| extract_cookie(&cookies, "__Secure-1PAPISID"));
    let sapisid = match sapisid {
        Some(s) => {
            println!("  SAPISID ok (len {})", s.len());
            s
        }
        None => {
            println!("  ❌ no SAPISID cookie found");
            std::process::exit(1);
        }
    };
    let origin = "https://music.youtube.com";
    let ts = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_secs();
    let auth_header = format!(
        "SAPISIDHASH {ts}_{}",
        sha1_hex(format!("{ts} {sapisid} {origin}").as_bytes())
    );
    println!("  Authorization minted: {}…", &auth_header[..28]);

    println!("\n═══ [2] WEB_REMIX PLAYER ═══");
    let client = ureq::AgentBuilder::new().build();
    let body = serde_json::json!({
        "context": {"client": {"clientName": "WEB_REMIX", "clientVersion": "1.20240401.01.00", "hl": "en", "gl": "US"}},
        "videoId": VIDEO_ID,
        "contentCheckOk": true,
        "racyCheckOk": true,
        "playbackContext": {"contentPlaybackContext": {"signatureTimestamp": 20073, "autoCaptionsDefaultOn": false}}
    });
    let resp = client
        .post("https://music.youtube.com/youtubei/v1/player?prettyPrint=false")
        .set("Content-Type", "application/json")
        .set("User-Agent", BROWSER_UA)
        .set("Authorization", &auth_header)
        .set("X-Origin", origin)
        .set("Referer", "https://music.youtube.com/")
        .set("Cookie", &cookies)
        .send_json(body);

    let json: Value = match resp {
        Ok(r) => {
            println!("  HTTP {}", r.status());
            match r.into_json() {
                Ok(j) => j,
                Err(e) => {
                    println!("  ❌ json: {e}");
                    return;
                }
            }
        }
        Err(ureq::Error::Status(code, _)) => {
            println!("  ❌ HTTP {code}");
            return;
        }
        Err(e) => {
            println!("  ❌ {e}");
            return;
        }
    };

    let ps = json
        .pointer("/playabilityStatus/status")
        .and_then(|v| v.as_str())
        .unwrap_or("(none)");
    let reason = json
        .pointer("/playabilityStatus/reason")
        .and_then(|v| v.as_str())
        .unwrap_or("");
    println!("  playability={ps} reason={reason:?}");
    if ps != "OK" {
        println!("\n❌ VERDICT: authenticated WEB_REMIX blocked too");
        return;
    }

    println!("\n═══ [3] FORMAT CENSUS ═══");
    let fmts = json
        .pointer("/streamingData/adaptiveFormats")
        .and_then(|f| f.as_array())
        .cloned()
        .unwrap_or_default();
    let sabr_url = json
        .pointer("/streamingData/serverAbrStreamingUrl")
        .and_then(|v| v.as_str())
        .map(String::from);
    let audio_direct: Vec<&Value> = fmts
        .iter()
        .filter(|f| {
            f.get("url").is_some()
                && f.get("mimeType")
                    .and_then(|m| m.as_str())
                    .map_or(false, |m| m.starts_with("audio/"))
        })
        .collect();
    let n_present = audio_direct.iter().any(|f| {
        f.get("url")
            .and_then(|u| u.as_str())
            .map_or(false, |u| u.contains("&n="))
    });
    println!(
        "  total={} direct_urls={} audio_direct={} sabr={}",
        fmts.len(),
        fmts.iter().filter(|f| f.get("url").is_some()).count(),
        audio_direct.len(),
        sabr_url.is_some()
    );
    println!("  n= param present in direct urls: {n_present}");
    if !audio_direct.is_empty() && sabr_url.is_none() {
        println!("  ✅ VERDICT: classic URL path ALIVE with auth — SABR proxy NOT needed yet");
    } else if sabr_url.is_some() {
        println!("  ⚠️ VERDICT: auth still returns SABR shape → UMP proxy required");
    }

    println!("\n═══ [4] STREAM PROBES (best audio) ═══");
    let best = audio_direct
        .iter()
        .max_by_key(|f| f.get("bitrate").and_then(|b| b.as_u64()).unwrap_or(0));
    let Some(best) = best else {
        println!("  nothing to probe");
        return;
    };
    let url = best
        .get("url")
        .and_then(|u| u.as_str())
        .unwrap()
        .to_string();
    let itag = best.get("itag").and_then(|i| i.as_u64()).unwrap_or(0);
    let mime = best
        .get("mimeType")
        .and_then(|m| m.as_str())
        .unwrap_or("?")
        .to_string();
    println!("  chosen itag={itag} mime={mime}");

    let combos: Vec<(&str, Vec<(&str, &str)>)> = vec![
        ("bare (ExoPlayer default today)", vec![]),
        ("UA only", vec![("User-Agent", BROWSER_UA)]),
        (
            "UA + Cookie",
            vec![("User-Agent", BROWSER_UA), ("Cookie", cookies.as_str())],
        ),
        (
            "full bind (UA+Cookie+Origin+Referer)",
            vec![
                ("User-Agent", BROWSER_UA),
                ("Cookie", cookies.as_str()),
                ("Origin", origin),
                ("Referer", "https://music.youtube.com/"),
            ],
        ),
    ];
    let mut best_pass_idx: Option<usize> = None;
    for (i, (label, hdrs)) in combos.iter().enumerate() {
        let mut req = client.get(&url).set("Range", "bytes=0-262143");
        for (k, v) in hdrs {
            req = req.set(k, v);
        }
        match req.call() {
            Ok(r) => {
                let st = r.status();
                let ct = r.content_type().to_string();
                let mut reader = r.into_reader();
                let mut tmp = vec![0u8; 16384];
                let mut body_len = 0usize;
                while body_len < 262_144 {
                    match std::io::Read::read(&mut reader, &mut tmp) {
                        Ok(0) | Err(_) => break,
                        Ok(n) => body_len += n,
                    }
                }
                println!("  [{i}] {label}: HTTP {st} ct={ct} got={body_len}B");
                if (st == 200 || st == 206) && body_len > 100_000 && best_pass_idx.is_none() {
                    best_pass_idx = Some(i);
                }
            }
            Err(ureq::Error::Status(code, _)) => println!("  [{i}] {label}: HTTP {code}"),
            Err(e) => println!("  [{i}] {label}: ERR {e}"),
        }
    }

    println!("\n═══ [5] THROUGHPUT (3s sample on best passing combo) ═══");
    if let Some(i) = best_pass_idx {
        let (_, hdrs) = &combos[i];
        let mut req = client.get(&url).set("Range", "bytes=0-20971519");
        for (k, v) in hdrs {
            req = req.set(k, v);
        }
        if let Ok(mut resp) = req.call() {
            let mut reader = resp.into_reader();
            let start = Instant::now();
            let mut buf = vec![0u8; 65536];
            let mut total = 0usize;
            while start.elapsed().as_secs_f64() < 3.0 {
                match std::io::Read::read(&mut reader, &mut buf) {
                    Ok(0) | Err(_) => break,
                    Ok(n) => total += n,
                }
            }
            let mbps = total as f64 / start.elapsed().as_secs_f64() / (1024.0 * 1024.0);
            println!(
                "  {total} bytes in {:.2}s = {mbps:.2} MB/s {}",
                start.elapsed().as_secs_f64(),
                if mbps > 0.3 {
                    "✅ no throttle"
                } else {
                    "⚠️ throttled?"
                }
            );
        }
    } else {
        println!("  no passing header combo → GVS rejects even with full binding");
    }

    println!("\n═══ FINAL VERDICT ═══");
    if best_pass_idx.is_some() {
        println!("✅ AUTHENTICATED PATH WORKS. Required headers combo index: {best_pass_idx:?}");
        println!("→ Implement Pillar-3 data-source injection + keep WEB_REMIX tier.");
    } else {
        println!("❌ Auth insufficient on this network/account → escalate (SABR/UMP or PO-token research).");
    }
}
