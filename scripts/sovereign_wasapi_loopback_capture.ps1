# Sovereign HAL Phase 1 (D5): the Windows WASAPI loopback capture harness.
#
# Captures the OS-mixed output of the default render endpoint through
# IAudioCaptureClient with AUDCLNT_STREAMFLAGS_LOOPBACK — the documented
# WASAPI loopback mode (no virtual audio driver needed; the runner's
# RDP/remote audio endpoint is the render device).
#
# The interop is deliberately minimal .NET Add-Type (no NuGet packages):
# CoInitializeEx -> IMMDeviceEnumerator -> GetDefaultAudioEndpoint ->
# Activate(IAudioClient) -> Initialize(loopback, float, 100ms buffer) ->
# IAudioCaptureClient::GetNextPacketSize/GetBuffer loop -> write a
# float32 WAV. The shared Python verifier FFT-proves the capture.

param(
    [int]$Seconds = 4,
    [string]$OutFile = "capture.wav"
)

$ErrorActionPreference = "Stop"

$src = @"
using System;
using System.Runtime.InteropServices;

namespace SovereignLoopback {
    public static class Wasapi {
        [DllImport("ole32.dll")]
        public static extern int CoInitializeEx(IntPtr pvReserved, uint dwCoInit);
        [DllImport("ole32.dll")]
        public static extern int CoCreateInstance(ref Guid clsid, IntPtr pUnkOuter, uint dwClsContext, ref Guid riid, out object ppv);

        // CLSID_MMDeviceEnumerator / IID_IMMDeviceEnumerator / IID_IAudioClient
        public static readonly Guid CLSID_MMDeviceEnumerator = new Guid("BCDE0395-E52F-467C-8E3D-C4579291692E");
        public static readonly Guid IID_IMMDeviceEnumerator = new Guid("A95664D2-9614-4F35-A746-DE8DB63617E6");
        public static readonly Guid IID_IAudioClient = new Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2");

        [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        public interface IMMDeviceEnumerator {
            int EnumAudioEndpoints(int dataFlow, int stateMask, out IntPtr devices);
            int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice endpoint);
        }

        [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        public interface IMMDevice {
            int Activate(ref Guid iid, uint dwClsCtx, IntPtr activationParams, [MarshalAs(UnmanagedType.IUnknown)] out object iface);
        }

        [ComImport, Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        public interface IAudioClient {
            int Initialize(int shareMode, int streamFlags, long bufferDuration, long periodicity, IntPtr format, IntPtr audioSessionGuid);
            int GetBufferSize(out uint bufferFrames);
            int GetStreamFormat(out IntPtr format);
            int GetCurrentPadding(out uint padding);
            int IsFormatSupported(int shareMode, IntPtr format, out IntPtr closestMatch);
            int GetService(ref Guid iid, [MarshalAs(UnmanagedType.IUnknown)] out object service);
        }

        [ComImport, Guid("C8ADBD64-E71E-48a0-A4DE-185C395CD317"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        public interface IAudioCaptureClient {
            int GetBuffer(out IntPtr data, out uint frames, out uint flags, out long devicePosition, out long qpcPosition, out uint bytes);
            int ReleaseBuffer();
            int GetNextPacketSize(out uint frames);
        }
    }
}
"@

Add-Type -TypeDefinition $src -Language CSharp

# AUDCLNT_STREAMFLAGS_LOOPBACK = 0x00020000; SHAREMODE_SHARED = 0.
$LOOPBACK = 0x00020000
$eRender = 0; $eConsole = 0

[void][SovereignLoopback.Wasapi]::CoInitializeEx([IntPtr]::Zero, 0x2)  # COINIT_MULTITHREADED

# PowerShell cannot marshal a raw P/Invoke `out object` (the round-16
# 'Specified OLE variant is invalid'): the RCW comes from
# Activator::CreateInstance(GetTypeFromCLSID), and casting to the
# ComImport interface makes the CLR perform the QI.
$clsid = [SovereignLoopback.Wasapi]::CLSID_MM_DEVICE_ENUMERATOR
$enumType = [Type]::GetTypeFromCLSID($clsid)
if ($null -eq $enumType) { throw "GetTypeFromCLSID(MMDeviceEnumerator) failed" }
$enumeratorObj = [Activator]::CreateInstance($enumType)
if ($null -eq $enumeratorObj) { throw "Activator::CreateInstance(MMDeviceEnumerator) failed" }
$enumerator = [SovereignLoopback.Wasapi.IMMDeviceEnumerator]$enumeratorObj

$device = $null
$enumerator.GetDefaultAudioEndpoint($eRender, $eConsole, [ref]$device) | Out-Null

$audioClient = $null
$iidAc = [SovereignLoopback.Wasapi]::IID_IAudioClient
$device.Activate([ref]$iidAc, 0x17, [IntPtr]::Zero, [ref]$audioClient) | Out-Null

# 100 ms buffer @ 48 kHz 100ns units (REFERENCE_TIME): 4800 * 100ns * ...
$bufferDuration = 1000000  # 100 ms in 100-ns units
$hr = $audioClient.Initialize(0, $LOOPBACK, $bufferDuration, 0, [IntPtr]::Zero, [IntPtr]::Zero)
if ($hr -ne 0) { throw "IAudioClient.Initialize(loopback) failed: 0x$($hr.ToString('X8'))" }

# Discover the loopback stream's real format (the endpoint mix format).
$formatPtr = [IntPtr]::Zero
$audioClient.GetStreamFormat([ref]$formatPtr) | Out-Null
# WAVEFORMATEX: wFormatTag(2) nChannels(2) nSamplesPerSec(4) nAvgBytesPerSec(4) nBlockAlign(2) wBitsPerSample(2) cbSize(2)
$formatTag = [Runtime.InteropServices.Marshal]::ReadInt16($formatPtr, 0)
$channels = [Runtime.InteropServices.Marshal]::ReadInt16($formatPtr, 2)
$rate = [Runtime.InteropServices.Marshal]::ReadInt32($formatPtr, 4)
$bits = [Runtime.InteropServices.Marshal]::ReadInt16($formatPtr, 14)
Write-Output "loopback format: tag=$formatTag ch=$channels rate=$rate bits=$bits"
if ($channels -ne 2 -or $rate -lt 44100 -or $rate -gt 192000) {
    throw "unexpected loopback endpoint format (ch=$channels rate=$rate)"
}

$iidCap = [Guid]"C8ADBD64-E71E-48a0-A4DE-185C395CD317"
$capture = $null
$audioClient.GetService([ref]$iidCap, [ref]$capture) | Out-Null

# The .NET WAV writer: float32 samples.
$samples = New-Object System.Collections.Generic.List[float]
$deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
while ([DateTime]::UtcNow -lt $deadline) {
    Start-Sleep -Milliseconds 20
    $packet = 0
    while ($true) {
        $frames = 0
        $capture.GetNextPacketSize([ref]$frames) | Out-Null
        if ($frames -eq 0) { break }
        $data = [IntPtr]::Zero; $pktFrames = 0; $flags = 0; $devPos = 0; $qpc = 0; $pktBytes = 0
        $capture.GetBuffer([ref]$data, [ref]$pktFrames, [ref]$flags, [ref]$devPos, [ref]$qpc, [ref]$pktBytes) | Out-Null
        if ($pktFrames -gt 0 -and $flags -band 0x2) {
            # SILENT packet: emit zeros (keeps the stream time-aligned).
            for ($i = 0; $i -lt $pktFrames * $channels; $i++) { $samples.Add(0.0) }
        } elseif ($pktFrames -gt 0) {
            $floatCount = $pktFrames * $channels
            if ($bits -eq 32 -and $formatTag -ne 1) {
                # IEEE float (the mix format on modern Windows).
                for ($i = 0; $i -lt $floatCount; $i++) {
                    $samples.Add([Runtime.InteropServices.Marshal]::ReadSingle($data, $i * 4))
                }
            } else {
                # 16-bit PCM fallback.
                for ($i = 0; $i -lt $floatCount; $i++) {
                    $v = [Runtime.InteropServices.Marshal]::ReadInt16($data, $i * 2)
                    $samples.Add($v / 32768.0)
                }
            }
        }
        $capture.ReleaseBuffer() | Out-Null
    }
}

# Write the float32 WAV.
$rateOut = $rate
$chOut = $channels
$dataBytes = $samples.Count * 4
$fs = [System.IO.File]::Create($OutFile)
$bw = New-Object System.IO.BinaryWriter($fs)
$bw.Write([System.Text.Encoding]::ASCII.GetBytes("RIFF"))
$bw.Write([uint32](36 + $dataBytes))
$bw.Write([System.Text.Encoding]::ASCII.GetBytes("WAVE"))
$bw.Write([System.Text.Encoding]::ASCII.GetBytes("fmt "))
$bw.Write([uint32]16)
$bw.Write([uint16]3)   # IEEE float
$bw.Write([uint16]$chOut)
$bw.Write([uint32]$rateOut)
$bw.Write([uint32]($rateOut * $chOut * 4))
$bw.Write([uint16]($chOut * 4))
$bw.Write([uint16]32)  # bits per sample
$bw.Write([System.Text.Encoding]::ASCII.GetBytes("data"))
$bw.Write([uint32]$dataBytes)
foreach ($s in $samples) { $bw.Write([float]$s) }
$bw.Close(); $fs.Close()
Write-Output "captured $($samples.Count) samples ($($samples.Count / ($chOut * $rateOut).ToString()) s) -> $OutFile"
