/**
 * screens/PrismaticSplashScreen.tsx — P3a: the splash, canvas-port.
 *
 * The Android PrismaticSplashScreen.kt port (the pre-13 2D-canvas
 * choreography — the AGSL singularity shader lands with a WebGL pass in
 * a later phase): 4.4 s linear timeline, red core with plasma halo and
 * play triangle, 16 prismatic ribbons, the STREAMIFY wordmark with
 * staggered per-letter genesis, shimmer sweep, breathing neon underline
 * with leading spark, the DEVELOPED BY SIREEN signature, the
 * "120 FPS · SPATIAL DSP · NEURAL RADIO" tagline, and the ascension
 * exit dissolve. Tap anywhere fast-forwards (250 ms), exactly like the
 * APK. Reduced-motion users skip straight through.
 */

import { useEffect, useRef } from 'react';
import { colors, splashRibbons } from '../theme/tokens';

const DURATION_MS = 4400;
const SKIP_MS = 250;
const WORDMARK = 'STREAMIFY';
const SIGNATURE_TAG = 'DEVELOPED BY ';
const SIGNATURE_NAME = 'SIREEN';
const TAGLINE = '120 FPS · SPATIAL DSP · NEURAL RADIO';

function clamp01(x: number): number {
  return x < 0 ? 0 : x > 1 ? 1 : x;
}
function smoothstep(a: number, b: number, x: number): number {
  const t = clamp01((x - a) / (b - a));
  return t * t * (3 - 2 * t);
}
function easeOutCubic(x: number): number {
  const t = clamp01(x);
  return 1 - Math.pow(1 - t, 3);
}

export interface PrismaticSplashScreenProps {
  onComplete: () => void;
}

export function PrismaticSplashScreen({ onComplete }: PrismaticSplashScreenProps): JSX.Element {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const doneRef = useRef(false);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (canvas === null) return;
    const ctx = canvas.getContext('2d');
    if (ctx === null) {
      onComplete();
      return;
    }
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      onComplete();
      return;
    }

    let raf = 0;
    const start = performance.now();
    // Fast-forward state: once tapped, the timeline warps to 1 in SKIP_MS.
    let warpFrom = -1;
    let warpStart = 0;

    const finish = () => {
      if (doneRef.current) return;
      doneRef.current = true;
      cancelAnimationFrame(raf);
      onComplete();
    };

    const draw = (now: number) => {
      let p = clamp01((now - start) / DURATION_MS);
      if (warpFrom >= 0) {
        const k = clamp01((now - warpStart) / SKIP_MS);
        p = clamp01(warpFrom + (1 - warpFrom) * k);
      }

      // ---- DPR-aware sizing ---------------------------------------------
      const dpr = Math.min(2, window.devicePixelRatio || 1);
      const w = window.innerWidth;
      const h = window.innerHeight;
      if (canvas.width !== w * dpr || canvas.height !== h * dpr) {
        canvas.width = w * dpr;
        canvas.height = h * dpr;
      }
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

      // ---- background + global dissolve ----------------------------------
      ctx.globalCompositeOperation = 'source-over';
      ctx.fillStyle = colors.bgSplash;
      ctx.fillRect(0, 0, w, h);
      const exitAlpha = 1 - clamp01((p - 0.86) / 0.14); // ascension curtain
      ctx.globalAlpha = exitAlpha;

      // ---- red core + halo + play triangle (p 0.35→0.88) ------------------
      const coreT = smoothstep(0.35, 0.88, p);
      const coreDie = 1 - smoothstep(0.85, 1.0, p);
      if (coreT > 0 && coreDie > 0) {
        const cx = w / 2;
        const cy = h / 2;
        const r = 26 * (1 + 2.8 * coreT);
        // plasma halo
        const halo = ctx.createRadialGradient(cx, cy, r * 0.4, cx, cy, r * 2.6);
        halo.addColorStop(0, `rgba(255, 0, 0, ${0.34 * coreT * coreDie})`);
        halo.addColorStop(0.6, `rgba(255, 0, 51, ${0.12 * coreT * coreDie})`);
        halo.addColorStop(1, 'rgba(255, 0, 51, 0)');
        ctx.globalCompositeOperation = 'screen';
        ctx.fillStyle = halo;
        ctx.fillRect(cx - r * 2.8, cy - r * 2.8, r * 5.6, r * 5.6);
        // core disc
        ctx.beginPath();
        ctx.arc(cx, cy, r, 0, Math.PI * 2);
        ctx.fillStyle = `rgba(255, 0, 0, ${0.9 * coreT * coreDie})`;
        ctx.fill();
        // white play triangle
        const ts = r * 0.62;
        ctx.beginPath();
        ctx.moveTo(cx - ts * 0.42, cy - ts * 0.55);
        ctx.lineTo(cx - ts * 0.42, cy + ts * 0.55);
        ctx.lineTo(cx + ts * 0.62, cy);
        ctx.closePath();
        ctx.fillStyle = `rgba(255, 255, 255, ${0.95 * coreT * coreDie})`;
        ctx.fill();
      }

      // ---- prismatic ribbons (p 0.65→1.0) ----------------------------------
      const ribbonT = smoothstep(0.65, 0.78, p);
      if (ribbonT > 0) {
        ctx.globalCompositeOperation = 'screen';
        ctx.lineWidth = 14;
        ctx.lineCap = 'round';
        const t = now / 1000;
        for (let i = 0; i < 16; i += 1) {
          const color = splashRibbons[i % splashRibbons.length];
          const yc = h * (0.18 + 0.64 * ((i * 37) % 16) / 15);
          const phase = (i * 1.7 + t * (0.6 + (i % 5) * 0.22)) % (Math.PI * 2);
          const amp = h * 0.05 + (i % 4) * 9;
          ctx.beginPath();
          for (let x = -20; x <= w + 20; x += 26) {
            const y =
              yc +
              Math.sin(x / (170 + (i % 5) * 40) + phase) * amp * (0.5 + 0.5 * ribbonT);
            if (x === -20) ctx.moveTo(x, y);
            else ctx.lineTo(x, y);
          }
          ctx.strokeStyle = color;
          ctx.globalAlpha = exitAlpha * 0.16 * ribbonT * (1 - 0.4 * ((i % 3) / 2));
          ctx.stroke();
        }
      }

      // ---- wordmark lockup ---------------------------------------------------
      ctx.globalCompositeOperation = 'source-over';
      const fontSize = Math.min(36, Math.max(22, w / 12));
      ctx.font = `900 ${fontSize}px system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif`;
      const letters = WORDMARK.split('');
      const letterW = letters.map((ch) => ctx.measureText(ch).width);
      const spacing = 8;
      const totalW = letterW.reduce((a, b) => a + b, 0) + spacing * (letters.length - 1);
      // center → upper-third drift (p 0.35→0.65), −48px exit lift (p>0.92)
      const drift = smoothstep(0.35, 0.65, p) * h * 0.155;
      const lift = smoothstep(0.92, 1.0, p) * 48;
      const scale = 1 + 0.04 * smoothstep(0.86, 1.0, p);
      const baseY = h / 2 - drift - lift;
      ctx.save();
      ctx.translate(w / 2, baseY);
      ctx.scale(scale, scale);
      ctx.textBaseline = 'alphabetic';

      // per-letter genesis (staggered index*0.038, rise + fade from p≈0.06)
      let x = -totalW / 2;
      for (let i = 0; i < letters.length; i += 1) {
        const birth = 0.06 + i * 0.038;
        const a = clamp01((p - birth) / 0.12);
        const rise = (1 - a) * fontSize * 0.5;
        ctx.globalAlpha = exitAlpha * a;
        ctx.fillStyle = '#ffffff';
        ctx.fillText(letters[i], x, rise);
        x += letterW[i] + spacing;
      }

      // shimmer pass: a clipped band sweeping the wordmark, forever
      const shimmerX = (((p * 1.35) % 1) * (totalW + 620)) - 310;
      const bandW = 240;
      const shimmer = ctx.createLinearGradient(shimmerX - bandW, 0, shimmerX + bandW, 0);
      shimmer.addColorStop(0, 'rgba(255,255,255,0)');
      shimmer.addColorStop(0.35, 'rgba(255,255,255,0.75)');
      shimmer.addColorStop(0.5, 'rgba(255,0,0,0.85)');
      shimmer.addColorStop(0.65, 'rgba(255,255,255,0.75)');
      shimmer.addColorStop(1, 'rgba(255,255,255,0)');
      ctx.save();
      ctx.beginPath();
      ctx.rect(-totalW / 2 - 8, -fontSize, totalW + 16, fontSize * 1.45);
      ctx.clip();
      ctx.fillStyle = shimmer;
      ctx.fillRect(shimmerX - bandW, -fontSize, bandW * 2, fontSize * 1.45);
      ctx.restore();

      // neon underline (load-progress level): sweeps to p→0.78, breathes after
      const sweep = easeOutCubic(p / 0.78);
      const breathe = 0.78 + 0.17 * Math.sin(now / 260);
      const underAlpha = p < 0.78 ? 1 : breathe * (1 - smoothstep(0.9, 1.0, p));
      const underY = fontSize * 0.55;
      const underW = totalW * sweep;
      const under = ctx.createLinearGradient(-totalW / 2, 0, -totalW / 2 + underW, 0);
      under.addColorStop(0, 'rgba(255,0,0,0.25)');
      under.addColorStop(0.5, `rgba(255,255,255,${underAlpha})`);
      under.addColorStop(1, 'rgba(255,0,0,0.25)');
      ctx.strokeStyle = under;
      ctx.lineWidth = 2.2;
      ctx.beginPath();
      ctx.moveTo(-totalW / 2, underY);
      ctx.lineTo(-totalW / 2 + underW, underY);
      ctx.stroke();
      // leading spark while sweeping
      if (p < 0.78) {
        const sx = -totalW / 2 + underW;
        const spark = ctx.createRadialGradient(sx, underY, 0, sx, underY, 22);
        spark.addColorStop(0, `rgba(255,255,255,${0.9 * (1 - p / 0.78)})`);
        spark.addColorStop(0.4, 'rgba(255,0,0,0.35)');
        spark.addColorStop(1, 'rgba(255,0,0,0)');
        ctx.fillStyle = spark;
        ctx.fillRect(sx - 22, underY - 22, 44, 44);
      }

      // signature (rises p 0.16→0.26, never leaves)
      const sigA = clamp01((p - 0.16) / 0.1);
      if (sigA > 0) {
        ctx.globalAlpha = exitAlpha * sigA;
        const sigY = underY + 16;
        ctx.font = `700 12px system-ui, sans-serif`;
        const tagW = ctx.measureText(SIGNATURE_TAG).width;
        ctx.fillStyle = 'rgba(255,255,255,0.82)';
        ctx.fillText(SIGNATURE_TAG, -totalW / 2, sigY + 12);
        ctx.font = `900 15px system-ui, sans-serif`;
        ctx.fillStyle = colors.primary;
        ctx.fillText(SIGNATURE_NAME, -totalW / 2 + tagW + 4, sigY + 12);
      }

      // tagline (one cycle p 0.60→0.90)
      const tagA = clamp01((p - 0.6) / 0.08) * (1 - clamp01((p - 0.82) / 0.08));
      if (tagA > 0) {
        ctx.globalAlpha = exitAlpha * tagA;
        ctx.font = `500 10px system-ui, sans-serif`;
        ctx.fillStyle = colors.textSecondary;
        const tw = ctx.measureText(TAGLINE).width;
        ctx.fillText(TAGLINE, -tw / 2, underY + 42);
      }

      ctx.restore();
      ctx.globalAlpha = 1;

      if (p >= 1) {
        finish();
        return;
      }
      raf = requestAnimationFrame(draw);
    };

    raf = requestAnimationFrame(draw);

    // Tap anywhere: gravitational fast-forward (250 ms to the curtain).
    const onTap = () => {
      if (warpFrom < 0 && !doneRef.current) {
        warpFrom = clamp01((performance.now() - start) / DURATION_MS);
        warpStart = performance.now();
      }
    };
    canvas.addEventListener('pointerdown', onTap);
    return () => {
      cancelAnimationFrame(raf);
      canvas.removeEventListener('pointerdown', onTap);
    };
  }, [onComplete]);

  return (
    <div className="splash" role="presentation">
      <canvas ref={canvasRef} className="splash__canvas" aria-label="Streamify" />
      <span className="sr-only">Streamify — loading</span>
    </div>
  );
}
